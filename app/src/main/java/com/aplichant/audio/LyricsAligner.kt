package com.aplichant.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Calage automatique des paroles, sans reconnaissance vocale :
 *
 * 1. [vocalScore] estime, pour chaque trame de ~23 ms, la quantité de voix présente dans la musique
 *    en comparant son spectre à celui du backing track (musique − backing ≈ voix). Le décalage
 *    temporel et la différence de volume entre les deux fichiers sont estimés automatiquement.
 * 2. [voicedRuns] transforme ce score en passages chantés séparés par des silences.
 * 3. [assignLines] répartit les lignes de paroles sur ces passages, dans l'ordre, en visant pour
 *    chaque ligne une durée chantée proportionnelle à son nombre de syllabes et en préférant couper
 *    sur les silences les plus longs.
 */
object LyricsAligner {

    /** Fréquence d'analyse (le signal est ré-échantillonné à cette fréquence avant analyse). */
    const val ANALYSIS_RATE = 11025
    private const val FFT_SIZE = 512
    private const val HOP = 256

    /** Durée d'une trame d'analyse en millisecondes. */
    const val FRAME_MS = HOP * 1000.0 / ANALYSIS_RATE

    // Bande de fréquences de la voix chantée utilisée pour la détection.
    private const val LOW_HZ = 250.0
    private const val HIGH_HZ = 3500.0
    private const val BINS_PER_BAND = 8

    /** Passage chanté [start, end) en indices de trames. */
    data class Run(val start: Int, val end: Int) {
        val length: Int get() = end - start
    }

    // ------------------------------------------------------------------ Analyse spectrale

    /** Spectre en log-énergie par bande pour chaque trame : [trame][bande]. */
    fun bandEnergies(mono: ShortArray): Array<FloatArray> {
        val binHz = ANALYSIS_RATE.toDouble() / FFT_SIZE
        val firstBin = (LOW_HZ / binHz).toInt()
        val lastBin = min(FFT_SIZE / 2 - 1, (HIGH_HZ / binHz).toInt())
        val bands = max(1, (lastBin - firstBin + 1) / BINS_PER_BAND)
        val frames = if (mono.size < FFT_SIZE) 0 else (mono.size - FFT_SIZE) / HOP + 1
        val window = FloatArray(FFT_SIZE) { (0.5 - 0.5 * cos(2 * PI * it / (FFT_SIZE - 1))).toFloat() }
        val re = FloatArray(FFT_SIZE)
        val im = FloatArray(FFT_SIZE)
        return Array(frames) { f ->
            val off = f * HOP
            for (i in 0 until FFT_SIZE) {
                re[i] = mono[off + i] / 32768f * window[i]
                im[i] = 0f
            }
            fft(re, im)
            FloatArray(bands) { b ->
                var e = 0.0
                val from = firstBin + b * BINS_PER_BAND
                for (k in from until from + BINS_PER_BAND) e += re[k] * re[k] + im[k] * im[k]
                ln(e + 1e-9).toFloat()
            }
        }
    }

    /**
     * Score de présence de voix par trame de la musique. Si [backing] est null, on se contente de
     * l'énergie dans la bande vocale (bien moins fiable).
     */
    fun vocalScore(song: ShortArray, backing: ShortArray?): FloatArray {
        val s = bandEnergies(song)
        if (s.isEmpty()) return FloatArray(0)
        val bands = s[0].size
        if (backing == null) {
            val total = FloatArray(s.size) { f -> s[f].sum() / bands }
            val med = median(total.copyOf())
            return FloatArray(s.size) { max(0f, total[it] - med) }
        }
        val b = bandEnergies(backing)
        if (b.isEmpty()) return vocalScore(song, null)
        val lag = estimateLag(s, b)
        // Écart de niveau par bande entre musique et backing, mesuré là où il n'y a pas de voix
        // (la médiane ignore les passages chantés, minoritaires).
        val gain = FloatArray(bands) { band ->
            val diffs = ArrayList<Float>()
            for (f in s.indices) {
                val g = f + lag
                if (g in b.indices) diffs.add(s[f][band] - b[g][band])
            }
            if (diffs.isEmpty()) 0f else median(diffs.toFloatArray())
        }
        return FloatArray(s.size) { f ->
            val g = f + lag
            if (g !in b.indices) {
                0f
            } else {
                var score = 0f
                for (band in 0 until bands) {
                    // Marge de 0,7 (≈ 3 dB) pour ignorer les petites différences d'encodage.
                    score += max(0f, s[f][band] - b[g][band] - gain[band] - 0.7f)
                }
                score / bands
            }
        }
    }

    /**
     * Décalage (en trames) tel que la trame f de la musique corresponde à la trame f + lag du
     * backing. Recherche sur ±3 s par corrélation des enveloppes d'énergie.
     */
    fun estimateLag(song: Array<FloatArray>, backing: Array<FloatArray>): Int {
        val es = FloatArray(song.size) { song[it].sum() }
        val eb = FloatArray(backing.size) { backing[it].sum() }
        normalize(es)
        normalize(eb)
        val maxLag = (3000 / FRAME_MS).toInt()
        var best = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for (lag in -maxLag..maxLag) {
            var sum = 0.0
            var n = 0
            for (f in es.indices) {
                val g = f + lag
                if (g in eb.indices) {
                    sum += es[f] * eb[g]
                    n++
                }
            }
            if (n > es.size / 4) {
                val score = sum / n
                if (score > bestScore) {
                    bestScore = score
                    best = lag
                }
            }
        }
        return best
    }

    // ------------------------------------------------------------------ Passages chantés

    /** Découpe le score en passages chantés (silences courts comblés, bruits isolés retirés). */
    fun voicedRuns(score: FloatArray): List<Run> {
        if (score.isEmpty()) return emptyList()
        val smooth = movingAverage(score, 2)
        val sorted = smooth.copyOf().also { it.sort() }
        val p95 = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)]
        if (p95 <= 1e-4f) return emptyList()
        val threshold = 0.2f * p95
        val voiced = BooleanArray(smooth.size) { smooth[it] > threshold }

        val runs = ArrayList<Run>()
        var i = 0
        while (i < voiced.size) {
            if (!voiced[i]) {
                i++; continue
            }
            val start = i
            while (i < voiced.size && voiced[i]) i++
            runs.add(Run(start, i))
        }
        // Comble les micro-silences (< ~140 ms) à l'intérieur d'une phrase.
        val merged = ArrayList<Run>()
        for (r in runs) {
            val last = merged.lastOrNull()
            if (last != null && r.start - last.end < 6) merged[merged.size - 1] = Run(last.start, r.end)
            else merged.add(r)
        }
        // Retire les bruits très courts (< ~90 ms).
        return merged.filter { it.length >= 4 }
    }

    // ------------------------------------------------------------------ Attribution des lignes

    /** Nombre approximatif de syllabes d'une ligne (groupes de voyelles). */
    fun syllables(line: String): Int {
        val vowels = "aeiouyàâäéèêëîïôöùûüœæ"
        var count = 0
        var inVowel = false
        for (c in line.lowercase()) {
            val v = c in vowels
            if (v && !inVowel) count++
            inVowel = v
        }
        return max(1, count)
    }

    /**
     * Attribue à chaque ligne (de poids [weights], ex. nombre de syllabes) un intervalle
     * [début, fin) en trames, pris sur les passages chantés [runs]. Renvoie null s'il n'y a
     * rien à caler.
     */
    fun assignLines(weights: List<Int>, runs: List<Run>, score: FloatArray? = null): List<Pair<Int, Int>>? {
        val n = weights.size
        if (n == 0 || runs.isEmpty()) return null
        var rs = runs
        // Pas assez de passages : on coupe les plus longs en leur point le plus faible.
        if (rs.size < n) rs = splitRuns(rs, n, score)
        if (rs.size < n) return proportional(weights, rs.first().start, rs.last().end)

        val m = rs.size
        val totalVoiced = rs.sumOf { it.length }.toDouble()
        val totalWeight = weights.sum().toDouble()
        val prefix = IntArray(m + 1)
        for (j in 0 until m) prefix[j + 1] = prefix[j] + rs[j].length
        fun gapBefore(j: Int) = if (j == 0) 50 else rs[j].start - rs[j - 1].end

        val inf = Double.MAX_VALUE / 4
        // dp[i][j] : coût minimal pour placer les i premières lignes sur les j premiers passages.
        val dp = Array(n + 1) { DoubleArray(m + 1) { inf } }
        // from[i][j] : k (début du groupe de la ligne i-1), ou -1 si le passage j-1 est ignoré.
        val from = Array(n + 1) { IntArray(m + 1) { -2 } }
        dp[0][0] = 0.0
        for (j in 1..m) {
            dp[0][j] = dp[0][j - 1] + skipCost(rs[j - 1])
            from[0][j] = -1
        }
        for (i in 1..n) {
            val target = weights[i - 1] / totalWeight * totalVoiced
            for (j in 1..m) {
                // Passage j-1 ignoré (bruit, voix de fond…) après la ligne i-1.
                var best = dp[i][j - 1] + skipCost(rs[j - 1])
                var arg = -1
                for (k in 0 until j) {
                    if (dp[i - 1][k] >= inf) continue
                    val d = (prefix[j] - prefix[k]).toDouble()
                    val cost = dp[i - 1][k] + (d - target) * (d - target) / max(target, 1.0) -
                        1.5 * ln(1.0 + gapBefore(k))
                    if (cost < best) {
                        best = cost
                        arg = k
                    }
                }
                dp[i][j] = best
                from[i][j] = arg
            }
        }
        if (dp[n][m] >= inf) return proportional(weights, rs.first().start, rs.last().end)

        // Remontée du chemin optimal.
        val result = arrayOfNulls<Pair<Int, Int>>(n)
        var i = n
        var j = m
        while (i > 0 && j > 0) {
            val k = from[i][j]
            if (k == -1) {
                j--
            } else {
                result[i - 1] = rs[k].start to rs[j - 1].end
                i--
                j = k
            }
        }
        if (result.any { it == null }) return proportional(weights, rs.first().start, rs.last().end)
        return result.map { it!! }
    }

    private fun skipCost(r: Run) = 3.0 * r.length

    private fun splitRuns(runs: List<Run>, wanted: Int, score: FloatArray?): List<Run> {
        val rs = runs.toMutableList()
        while (rs.size < wanted) {
            val idx = rs.indices.maxByOrNull { rs[it].length } ?: break
            val r = rs[idx]
            if (r.length < 8) break
            // Point de coupe : trame la plus faible dans la partie centrale du passage.
            val from = r.start + r.length / 5
            val to = r.end - r.length / 5
            var cut = (r.start + r.end) / 2
            if (score != null) {
                var minV = Float.MAX_VALUE
                for (f in from until to) if (f in score.indices && score[f] < minV) {
                    minV = score[f]; cut = f
                }
            }
            rs[idx] = Run(r.start, cut)
            rs.add(idx + 1, Run(cut, r.end))
        }
        return rs
    }

    private fun proportional(weights: List<Int>, start: Int, end: Int): List<Pair<Int, Int>> {
        val total = weights.sum().toDouble()
        var pos = start.toDouble()
        return weights.map { w ->
            val len = (end - start) * w / total
            val s = pos.toInt()
            pos += len
            s to pos.toInt()
        }
    }

    // ------------------------------------------------------------------ Outils

    private fun movingAverage(x: FloatArray, radius: Int): FloatArray = FloatArray(x.size) { i ->
        var sum = 0f
        var n = 0
        for (k in max(0, i - radius)..min(x.size - 1, i + radius)) {
            sum += x[k]; n++
        }
        sum / n
    }

    private fun median(x: FloatArray): Float {
        if (x.isEmpty()) return 0f
        x.sort()
        return x[x.size / 2]
    }

    private fun normalize(x: FloatArray) {
        if (x.isEmpty()) return
        val mean = x.average().toFloat()
        var sd = 0.0
        for (v in x) sd += (v - mean) * (v - mean)
        val s = kotlin.math.sqrt(sd / x.size).toFloat().takeIf { it > 1e-6f } ?: 1f
        for (i in x.indices) x[i] = (x[i] - mean) / s
    }

    /** FFT radix-2 en place (taille puissance de 2). */
    fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit; bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until len / 2) {
                    val ar = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val ai = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k + len / 2] = re[i + k] - ar
                    im[i + k + len / 2] = im[i + k] - ai
                    re[i + k] += ar
                    im[i + k] += ai
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
