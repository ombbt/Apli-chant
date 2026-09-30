package com.aplichant.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.abs
import kotlin.math.max

/**
 * Enregistre le micro en mono 16 bits à [ENGINE_RATE] Hz dans un thread dédié.
 */
class VoiceRecorder {
    private val buffer = ShortBuffer(ENGINE_RATE * 60)
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    /** Nombre de frames enregistrées jusqu'ici. */
    @Volatile
    var framesRecorded: Long = 0
        private set

    /** Niveau crête récent (0..1) pour l'affichage d'un vu-mètre. */
    @Volatile
    var level: Float = 0f
        private set

    @SuppressLint("MissingPermission")
    fun start() {
        val minBuf = AudioRecord.getMinBufferSize(
            ENGINE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        // VOICE_RECOGNITION : pas de contrôle automatique de gain ni de réduction de bruit,
        // ce qui convient mieux au chant que la source MIC.
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            ENGINE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            max(minBuf * 2, 8192),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("Impossible d'initialiser le micro")
        }
        record = rec
        running = true
        rec.startRecording()
        thread = Thread({
            val chunk = ShortArray(ENGINE_RATE / 100) // 10 ms
            while (running) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n <= 0) continue
                synchronized(buffer) { buffer.addAll(chunk, n) }
                var peak = 0
                for (i in 0 until n) peak = max(peak, abs(chunk[i].toInt()))
                level = peak / 32768f
                framesRecorded += n
            }
        }, "VoiceRecorder").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /** Arrête l'enregistrement et renvoie les échantillons mono. */
    fun stop(): ShortArray {
        running = false
        thread?.join(1000)
        thread = null
        record?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
            }
            it.release()
        }
        record = null
        return synchronized(buffer) { buffer.toArray() }
    }
}
