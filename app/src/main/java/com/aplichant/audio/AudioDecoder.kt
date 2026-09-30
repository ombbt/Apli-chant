package com.aplichant.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Fréquence d'échantillonnage interne de l'application. */
const val ENGINE_RATE = 44100

/**
 * Décode un fichier audio (MP3, WAV, ...) en PCM 16 bits via MediaExtractor/MediaCodec.
 */
object AudioDecoder {

    /** Durée du fichier en millisecondes (lecture des métadonnées uniquement). */
    fun durationMs(context: Context, uri: Uri): Long {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = findAudioTrack(extractor)
            val format = extractor.getTrackFormat(track)
            return if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION) / 1000
            } else 0L
        } finally {
            extractor.release()
        }
    }

    /**
     * Décode l'intervalle [startMs, endMs) et le renvoie en stéréo entrelacée à [ENGINE_RATE] Hz.
     */
    fun decodeStereo(
        context: Context,
        uri: Uri,
        startMs: Long,
        endMs: Long,
        shouldStop: () -> Boolean = { false },
    ): ShortArray {
        val out = ShortBuffer()
        var nativeRate = ENGINE_RATE
        decode(context, uri, startMs * 1000, endMs * 1000, shouldStop) { pcm, frames, channels, rate, _ ->
            nativeRate = rate
            for (i in 0 until frames) {
                val l: Short
                val r: Short
                if (channels == 1) {
                    l = pcm[i]; r = pcm[i]
                } else {
                    l = pcm[i * channels]; r = pcm[i * channels + 1]
                }
                out.add(l); out.add(r)
            }
        }
        val stereo = out.toArray()
        return if (nativeRate == ENGINE_RATE) stereo else resampleStereo(stereo, nativeRate, ENGINE_RATE)
    }

    /**
     * Décode le fichier entier et calcule une forme d'onde de [buckets] valeurs (0..1).
     * Renvoie la forme d'onde et la durée en ms.
     */
    fun waveform(context: Context, uri: Uri, buckets: Int): Pair<FloatArray, Long> {
        val durationUs = durationMs(context, uri) * 1000
        val peaks = FloatArray(buckets)
        var lastUs = 0L
        decode(context, uri, 0, Long.MAX_VALUE, { false }) { pcm, frames, channels, rate, firstUs ->
            val total = frames * channels
            // Un pic tous les ~5 ms suffit largement pour l'affichage.
            val step = max(1, rate / 200) * channels
            var i = 0
            while (i < total) {
                val frame = i / channels
                val tUs = firstUs + frame * 1_000_000L / rate
                if (durationUs > 0) {
                    val b = (tUs * buckets / durationUs).toInt().coerceIn(0, buckets - 1)
                    val v = abs(pcm[i].toInt()) / 32768f
                    if (v > peaks[b]) peaks[b] = v
                }
                i += step
            }
            lastUs = firstUs + frames * 1_000_000L / rate
        }
        val duration = if (durationUs > 0) durationUs / 1000 else lastUs / 1000
        val maxPeak = peaks.maxOrNull() ?: 0f
        if (maxPeak > 0f) for (i in peaks.indices) peaks[i] = peaks[i] / maxPeak
        return peaks to duration
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        throw IllegalArgumentException("Aucune piste audio trouvée dans ce fichier")
    }

    /**
     * Boucle de décodage générique. [onPcm] reçoit des blocs entrelacés déjà découpés sur l'intervalle
     * demandé : (échantillons, nbFrames, nbCanaux, fréquence, instant du 1er frame en µs).
     */
    private fun decode(
        context: Context,
        uri: Uri,
        startUs: Long,
        endUs: Long,
        shouldStop: () -> Boolean,
        onPcm: (ShortArray, Int, Int, Int, Long) -> Unit,
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = findAudioTrack(extractor)
            extractor.selectTrack(track)
            val inputFormat = extractor.getTrackFormat(track)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
            if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone && !shouldStop()) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0 || extractor.sampleTime > endUs) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                    }
                    outIndex >= 0 -> {
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        if (info.size > 0) {
                            val buf = codec.getOutputBuffer(outIndex)!!
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            buf.order(ByteOrder.nativeOrder())
                            val pcm = toShorts(buf, encoding)
                            val frames = pcm.size / channels
                            val pts = info.presentationTimeUs
                            // Découpage précis sur [startUs, endUs)
                            val first = if (pts >= startUs) 0 else
                                min(frames.toLong(), ceil((startUs - pts) * sampleRate / 1e6).toLong()).toInt()
                            val endFrame = if (endUs == Long.MAX_VALUE) frames.toLong()
                            else ((endUs - pts) * sampleRate / 1_000_000L)
                            val last = endFrame.coerceIn(0L, frames.toLong()).toInt()
                            if (last > first) {
                                val slice = if (first == 0 && last == frames) pcm
                                else pcm.copyOfRange(first * channels, last * channels)
                                onPcm(slice, last - first, channels, sampleRate, pts + first * 1_000_000L / sampleRate)
                            }
                            if (endUs != Long.MAX_VALUE && endFrame <= frames) outputDone = true
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                    }
                }
            }
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            codec?.release()
            extractor.release()
        }
    }

    private fun toShorts(buf: java.nio.ByteBuffer, encoding: Int): ShortArray = when (encoding) {
        AudioFormat.ENCODING_PCM_FLOAT -> {
            val fb = buf.asFloatBuffer()
            ShortArray(fb.remaining()) { (fb.get(it).coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
        }
        AudioFormat.ENCODING_PCM_8BIT -> {
            ShortArray(buf.remaining()) { (((buf.get(buf.position() + it).toInt() and 0xFF) - 128) shl 8).toShort() }
        }
        AudioFormat.ENCODING_PCM_32BIT -> {
            val ib = buf.asIntBuffer()
            ShortArray(ib.remaining()) { (ib.get(it) shr 16).toShort() }
        }
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
            val n = buf.remaining() / 3
            val base = buf.position()
            ShortArray(n) {
                // Petit-boutiste : on garde les 2 octets de poids fort.
                val lo = buf.get(base + it * 3 + 1).toInt() and 0xFF
                val hi = buf.get(base + it * 3 + 2).toInt()
                ((hi shl 8) or lo).toShort()
            }
        }
        else -> {
            val sb = buf.asShortBuffer()
            ShortArray(sb.remaining()).also { sb.get(it) }
        }
    }

    /** Ré-échantillonnage linéaire d'un signal stéréo entrelacé. */
    fun resampleStereo(input: ShortArray, inRate: Int, outRate: Int): ShortArray {
        val inFrames = input.size / 2
        if (inFrames == 0) return input
        val outFrames = (inFrames.toLong() * outRate / inRate).toInt()
        val out = ShortArray(outFrames * 2)
        val ratio = inRate.toDouble() / outRate
        for (i in 0 until outFrames) {
            val pos = i * ratio
            val i0 = pos.toInt().coerceAtMost(inFrames - 1)
            val i1 = (i0 + 1).coerceAtMost(inFrames - 1)
            val frac = pos - i0
            for (c in 0..1) {
                val a = input[i0 * 2 + c]
                val b = input[i1 * 2 + c]
                out[i * 2 + c] = (a + (b - a) * frac).toInt().toShort()
            }
        }
        return out
    }
}

/** Tableau de shorts extensible. */
class ShortBuffer(initial: Int = 1 shl 16) {
    private var data = ShortArray(initial)
    var size = 0
        private set

    fun add(v: Short) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    fun addAll(src: ShortArray, count: Int) {
        if (size + count > data.size) {
            var n = data.size
            while (size + count > n) n *= 2
            data = data.copyOf(n)
        }
        System.arraycopy(src, 0, data, size, count)
        size += count
    }

    fun toArray(): ShortArray = data.copyOf(size)
}
