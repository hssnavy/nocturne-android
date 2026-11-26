package com.nocturne.visualizer

import android.util.Base64
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Hands captured sound (mono float PCM) from the capture thread to the page. */
object AudioHub {
    const val SAMPLE_RATE = 48000
    private const val MAX_BACKLOG = SAMPLE_RATE / 4         // never hand the page more than 250 ms (keeps it in sync)

    /** "idle", "device", "mic", "mic-fallback" or "no-permission" — shown to the user by the page. */
    @Volatile
    var status = "idle"

    private val ring = FloatArray(SAMPLE_RATE)
    private var writePos = 0
    private var readPos = 0

    @Synchronized
    fun reset() {
        writePos = 0
        readPos = 0
        status = "idle"
    }

    @Synchronized
    fun write(src: FloatArray, n: Int) {
        for (i in 0 until n) {
            ring[writePos] = src[i]
            writePos = (writePos + 1) % ring.size
            if (writePos == readPos) readPos = (readPos + 1) % ring.size
        }
    }

    @Synchronized
    fun drainBase64(): String {
        var count = (writePos - readPos + ring.size) % ring.size
        if (count == 0) return ""
        if (count > MAX_BACKLOG) {
            readPos = (writePos - MAX_BACKLOG + ring.size) % ring.size
            count = MAX_BACKLOG
        }
        val bytes = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            bytes.putFloat(ring[readPos])
            readPos = (readPos + 1) % ring.size
        }
        return Base64.encodeToString(bytes.array(), Base64.NO_WRAP)
    }
}
