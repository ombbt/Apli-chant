package com.aplichant.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Lecture/écriture de fichiers WAV PCM 16 bits. */
object Wav {

    fun write(file: File, samples: ShortArray, channels: Int, sampleRate: Int) {
        val dataBytes = samples.size * 2
        val buf = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray())
        buf.putInt(36 + dataBytes)
        buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray())
        buf.putInt(16)
        buf.putShort(1) // PCM
        buf.putShort(channels.toShort())
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * channels * 2)
        buf.putShort((channels * 2).toShort())
        buf.putShort(16)
        buf.put("data".toByteArray())
        buf.putInt(dataBytes)
        for (s in samples) buf.putShort(s)
        file.parentFile?.mkdirs()
        file.writeBytes(buf.array())
    }

    /** Lit un WAV écrit par [write] et renvoie ses échantillons (entrelacés). */
    fun read(file: File): ShortArray {
        RandomAccessFile(file, "r").use { raf ->
            val bytes = ByteArray(raf.length().toInt())
            raf.readFully(bytes)
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var pos = 12
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4)
                val size = bb.getInt(pos + 4)
                if (id == "data") {
                    val n = minOf(size, bytes.size - pos - 8) / 2
                    val out = ShortArray(n)
                    bb.position(pos + 8)
                    bb.asShortBuffer().get(out)
                    return out
                }
                pos += 8 + size + (size and 1)
            }
            return ShortArray(0)
        }
    }
}
