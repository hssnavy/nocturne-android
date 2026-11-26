package com.nocturne.visualizer

/**
 * The raw sound the visuals react to. Sound sources (phone capture, microphone, demo track) write samples
 * here the moment they are heard; the renderer reads the newest ones every frame and analyses them itself,
 * in exact 10 ms steps, so nothing waits in a queue.
 */
class SoundRing {
    val size = 1 shl 17                      // ~2.7 s at 48 kHz
    private val buf = FloatArray(size)
    @Volatile var total = 0L; private set    // samples written since the source started
    @Volatile var sampleRate = 48000; private set
    @Volatile var active = false; private set
    @Volatile var generation = 0; private set  // changes whenever the source changes

    @Synchronized fun setSource(on: Boolean, rate: Int) {
        active = on; sampleRate = rate; generation++
    }

    /** Called from the audio threads. */
    fun process(src: FloatArray, n: Int) {
        var t = total
        for (i in 0 until n) { buf[(t and (size - 1).toLong()).toInt()] = src[i]; t++ }
        total = t
    }

    /** Copies the n samples that end at absolute index [end] into out (zeros where nothing was written). */
    fun read(end: Long, n: Int, out: FloatArray) {
        val start = end - n
        for (i in 0 until n) {
            val j = start + i
            out[i] = if (j < 0 || j < total - size) 0f else buf[(j and (size - 1).toLong()).toInt()]
        }
    }
}

/** Shared between the sound sources (capture service, demo player) and the renderer. */
object Engine {
    val analyzer = SoundRing()

    /** "idle", "device", "mic", "mic-fallback", "no-permission", "demo" or "demo-error": shown to the user. */
    @Volatile var status = "idle"

    /** Where the sound feeding the visuals comes from right now, shown next to the BPM. */
    @Volatile var via = ""

    /** Demo track position (seconds) while it plays; -1 otherwise. The Demo track follows the energy you taught. */
    @Volatile var demoPos = -1.0
}
