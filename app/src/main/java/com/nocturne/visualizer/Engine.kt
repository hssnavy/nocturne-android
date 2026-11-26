package com.nocturne.visualizer

/** Shared between the sound sources (capture service, demo player) and the renderer. */
object Engine {
    val analyzer = Analyzer()

    /** "idle", "device", "mic", "mic-fallback", "no-permission" or "demo" — shown to the user. */
    @Volatile
    var status = "idle"
}
