package com.nocturne.visualizer

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * LEARNING RECORDS. While music plays, the app notes once a second what it hears and what it decided:
 * the sound features behind the energy (bass, low-mids, highs, loudness, kick), the energy it showed, the 11 channel
 * levels, the BPM and how many kicks it caught. Each song becomes one record (a song ends at a silence, a source
 * change, or after 15 minutes). No audio is ever kept or sent.
 *
 * Records wait on the phone and are uploaded when the phone is online, as issues in the GitHub project
 * hssnavy/nocturne-android (label-free, title "learning: ..."), where they can be read later to improve the energy
 * model and the BPM. Uploading needs the project's upload key (built in from the NOCTURNE_UPLOAD_TOKEN secret);
 * without it the records simply stay on the phone (up to 300) until a build with the key is installed.
 */
class LearnLog(private val ctx: Context) {
    private val dir = File(ctx.filesDir, "learn").apply { mkdirs() }
    private var rows = JSONArray()
    private var t = 0f; private var acc = 0f; private var started = 0L
    private var kicksSeen = 0.0; private var lastKick = 0.0
    private var source = ""; private var gen = -1
    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    /** Called every frame by the brain. */
    fun tick(dt: Float, b: Brain) {
        val g = Engine.analyzer.generation
        if (g != gen || b.songStarted) { finish(); gen = g; b.songStarted = false; begin(b) }
        if (!b.live) return
        if (b.kickT != lastKick) { lastKick = b.kickT; kicksSeen++ }
        acc += dt; t += dt
        if (acc < 1f) return
        acc = 0f
        val f = b.learnFeatures() ?: return
        val row = JSONArray()
        row.put(t.roundToInt())
        row.put((b.E * 100).roundToInt())
        for (v in f) row.put((v * 1000).roundToInt() / 1000.0)
        for (c in 0 until 11) row.put((b.chLev[c] * 100).roundToInt())
        row.put((b.bpm * 10).roundToInt() / 10.0)
        row.put(kicksSeen.toInt()); kicksSeen = 0.0
        rows.put(row)
        if (t > 900) { finish(); begin(b) }
    }

    private fun begin(b: Brain) { rows = JSONArray(); t = 0f; acc = 0f; started = System.currentTimeMillis(); source = if (Engine.demoPos >= 0) "demo" else Engine.via }

    fun flush() = finish()

    private fun finish() {
        if (rows.length() < 20) { rows = JSONArray(); return }          // under 20 s: not a song
        val rec = JSONObject()
        rec.put("app", "nocturne-android"); rec.put("format", 1)
        rec.put("started", iso.format(Date(started))); rec.put("seconds", rows.length())
        rec.put("source", source); rec.put("sampleRate", Engine.analyzer.sampleRate)
        rec.put("device", Build.MANUFACTURER + " " + Build.MODEL); rec.put("android", Build.VERSION.RELEASE)
        rec.put("sensitivity", Settings.sens)
        rec.put("columns", JSONArray(listOf("t", "energy", "bass-lowmid", "highs-lowmid", "loudness-vs-peak", "kick-rise", "kick",
            "bass_dB", "lowmid_dB", "highs_dB", "total_dB", "ch31", "ch62", "ch125", "ch250", "ch500", "ch1k", "ch2k", "ch4k", "ch8k", "ch16k", "ch.1", "bpm", "kicks")))
        rec.put("rows", rows)
        try {
            val files = dir.listFiles()?.sortedBy { it.name } ?: emptyList()
            if (files.size >= 300) files.take(files.size - 299).forEach { it.delete() }
            File(dir, "rec-${started}.json").writeText(rec.toString())
        } catch (_: Exception) {}
        rows = JSONArray()
    }

    /** Uploads waiting records in the background (every minute while the app is open). */
    fun startUploader() {
        val token = BuildConfig.UPLOAD_TOKEN
        if (token.isEmpty()) return
        Thread({
            while (true) {
                try {
                    for (f in dir.listFiles()?.sortedBy { it.name } ?: emptyList()) {
                        if (!upload(f, token)) break
                        f.delete()
                    }
                } catch (_: Exception) {}
                try { Thread.sleep(60_000) } catch (_: InterruptedException) { return@Thread }
            }
        }, "nocturne-learn-upload").apply { isDaemon = true; start() }
    }

    private fun upload(f: File, token: String): Boolean {
        val rec = JSONObject(f.readText())
        val title = "learning: ${rec.optString("source")} ${rec.optString("started")} (${rec.optInt("seconds")} s)"
        val body = "Nocturne learning record (no audio). Read by the developer to improve energy and BPM.\n\n```json\n" + rec.toString() + "\n```"
        if (body.length > 64000) return true.also { f.delete() }
        val c = URL("https://api.github.com/repos/hssnavy/nocturne-android/issues").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15000; c.readTimeout = 20000
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(JSONObject().put("title", title).put("body", body).toString().toByteArray()) }
        val code = c.responseCode
        c.disconnect()
        return code in 200..299
    }
}
