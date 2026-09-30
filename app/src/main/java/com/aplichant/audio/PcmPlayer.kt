package com.aplichant.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * Lit un tampon PCM 16 bits stéréo à [ENGINE_RATE] Hz avec AudioTrack.
 * Suspend jusqu'à la fin de la lecture, l'annulation de la coroutine ou [shouldStop].
 */
suspend fun playPcm(
    pcm: ShortArray,
    shouldStop: () -> Boolean = { false },
    onStart: () -> Unit = {},
    onPosition: (frames: Long) -> Unit = {},
) = withContext(Dispatchers.IO) {
    val channels = 2
    val minBuf = AudioTrack.getMinBufferSize(
        ENGINE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
    )
    val track = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setSampleRate(ENGINE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build()
        )
        .setBufferSizeInBytes(max(minBuf * 2, 8192))
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

    val totalFrames = pcm.size / channels
    try {
        onStart()
        track.play()
        val chunk = ENGINE_RATE / 50 * channels // 20 ms
        var offset = 0
        while (offset < pcm.size && isActive && !shouldStop()) {
            val n = track.write(pcm, offset, min(chunk, pcm.size - offset))
            if (n < 0) break
            offset += n
            onPosition(track.playbackHeadPosition.toLong())
        }
        // Attendre que la fin du tampon soit réellement jouée.
        var guard = 0
        while (isActive && !shouldStop() && track.playbackHeadPosition < totalFrames && guard < 200) {
            val before = track.playbackHeadPosition
            delay(20)
            onPosition(track.playbackHeadPosition.toLong())
            if (track.playbackHeadPosition == before) guard++ else guard = 0
        }
    } finally {
        try {
            track.pause()
            track.flush()
            track.stop()
        } catch (_: IllegalStateException) {
        }
        track.release()
    }
}
