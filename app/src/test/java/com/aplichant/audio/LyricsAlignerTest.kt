package com.aplichant.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class LyricsAlignerTest {

    private val rate = LyricsAligner.ANALYSIS_RATE

    /** Accompagnement synthétique : accords graves + bruit léger. */
    private fun backing(seconds: Double, seed: Int = 1): ShortArray {
        val rnd = Random(seed)
        val n = (seconds * rate).toInt()
        return ShortArray(n) { i ->
            val t = i.toDouble() / rate
            val v = 0.25 * sin(2 * PI * 110 * t) + 0.15 * sin(2 * PI * 165 * t) +
                0.1 * sin(2 * PI * 220 * t) + 0.03 * (rnd.nextDouble() * 2 - 1)
            (v * 32767).toInt().toShort()
        }
    }

    /** « Musique » = accompagnement + voix (sons à 600-900 Hz) sur les intervalles donnés (s). */
    private fun song(acc: ShortArray, phrases: List<Pair<Double, Double>>, shiftSeconds: Double = 0.0): ShortArray {
        val shift = (shiftSeconds * rate).toInt()
        return ShortArray(acc.size) { i ->
            val t = i.toDouble() / rate
            val a = acc.getOrElse(i + shift) { 0 }.toInt()
            val voice = if (phrases.any { t >= it.first && t < it.second }) {
                0.2 * sin(2 * PI * 700 * t) + 0.1 * sin(2 * PI * 1400 * t)
            } else 0.0
            (a + voice * 32767).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    private fun frameToSec(f: Int) = f * LyricsAligner.FRAME_MS / 1000

    @Test
    fun syllables() {
        assertEquals(3, LyricsAligner.syllables("la la la"))
        assertEquals(1, LyricsAligner.syllables("hmm"))
        assertEquals(3, LyricsAligner.syllables("éa oui ou"))
    }

    @Test
    fun detectsPhrasesAndAssignsLines() {
        val phrases = listOf(2.0 to 4.0, 5.0 to 6.5, 8.0 to 11.0)
        val acc = backing(14.0)
        val score = LyricsAligner.vocalScore(song(acc, phrases), acc)
        val runs = LyricsAligner.voicedRuns(score)
        assertEquals("passages détectés : $runs", 3, runs.size)

        val lines = listOf("la la la la", "la la la", "la la la la la la")
        val result = LyricsAligner.assignLines(lines.map { LyricsAligner.syllables(it) }, runs, score)
        assertNotNull(result)
        result!!.forEachIndexed { i, (s, e) ->
            assertTrue("début ligne $i : ${frameToSec(s)}", abs(frameToSec(s) - phrases[i].first) < 0.25)
            assertTrue("fin ligne $i : ${frameToSec(e)}", abs(frameToSec(e) - phrases[i].second) < 0.25)
        }
    }

    @Test
    fun compensatesOffsetBetweenSongAndBacking() {
        val phrases = listOf(3.0 to 5.0, 7.0 to 9.0)
        val acc = backing(16.0, seed = 3)
        // Le backing est en avance de 0,7 s sur la musique.
        val score = LyricsAligner.vocalScore(song(acc, phrases, shiftSeconds = 0.7), acc)
        val runs = LyricsAligner.voicedRuns(score)
        assertEquals("passages détectés : $runs", 2, runs.size)
        assertTrue(abs(frameToSec(runs[0].start) - 3.0) < 0.25)
        assertTrue(abs(frameToSec(runs[1].end) - 9.0) < 0.25)
    }

    @Test
    fun groupsRunsWhenMorePassagesThanLines() {
        // 4 passages, 2 lignes : les deux premiers vont ensemble (petit silence), puis les deux derniers.
        val runs = listOf(
            LyricsAligner.Run(0, 40), LyricsAligner.Run(45, 90),
            LyricsAligner.Run(200, 240), LyricsAligner.Run(246, 290),
        )
        val result = LyricsAligner.assignLines(listOf(6, 6), runs)!!
        assertEquals(0 to 90, result[0])
        assertEquals(200 to 290, result[1])
    }

    @Test
    fun splitsWhenFewerPassagesThanLines() {
        val runs = listOf(LyricsAligner.Run(0, 200))
        val result = LyricsAligner.assignLines(listOf(3, 3), runs)!!
        assertEquals(2, result.size)
        assertTrue(result[0].second <= result[1].first)
    }

    @Test
    fun fftOfPureTone() {
        val n = 512
        val re = FloatArray(n) { sin(2 * PI * 32 * it / n).toFloat() }
        val im = FloatArray(n)
        LyricsAligner.fft(re, im)
        val mags = FloatArray(n / 2) { re[it] * re[it] + im[it] * im[it] }
        assertEquals(32, mags.indices.maxByOrNull { mags[it] })
    }
}
