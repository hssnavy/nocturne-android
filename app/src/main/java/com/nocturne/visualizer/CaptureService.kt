package com.nocturne.visualizer

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.Visualizer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Captures sound while the visualizer is open.
 *  - mode "device": the sound this phone plays (Android 10+), directly where the app allows it, otherwise
 *    through the phone's output mix (Spotify and other apps that block capture). Never the microphone.
 *  - mode "mic": the microphone only (only when you choose Microphone).
 */
class CaptureService : Service() {

    @Volatile
    private var running = false
    private var worker: Thread? = null
    private var projection: MediaProjection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = intent?.getStringExtra("mode") ?: "mic"
        goForeground(mode)
        stopWorker()
        projection?.stop()
        projection = null
        if (mode == "device" && Build.VERSION.SDK_INT >= 29 && intent != null) {
            val data = projectionData(intent)
            val code = intent.getIntExtra("code", 0)
            if (data != null) {
                val mpm = getSystemService(MediaProjectionManager::class.java)
                projection = mpm.getMediaProjection(code, data)
                projection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() { running = false }
                }, null)
            }
        }
        Engine.analyzer.setSource(true, SAMPLE_RATE)
        startWorker(mode)
        return START_NOT_STICKY
    }

    private fun goForeground(mode: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Listening", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Nocturne is listening")
            .setContentText(if (mode == "device") "Visualising the sound this phone plays" else "Visualising sound from the microphone")
            .setSmallIcon(R.drawable.ic_stat)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 30) {
            val type = if (mode == "device")
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(NOTIFICATION_ID, notification, type)
        } else if (Build.VERSION.SDK_INT == 29 && mode == "device") {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun projectionData(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("data", Intent::class.java)
        else legacyParcelable(intent)

    @Suppress("DEPRECATION")
    private fun legacyParcelable(intent: Intent): Intent? = intent.getParcelableExtra("data")

    private fun format(): AudioFormat = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
        .setSampleRate(SAMPLE_RATE)
        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
        .build()

    private fun bufferBytes(): Int = max(
        AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT),
        SAMPLE_RATE / 10 * 4
    )

    @SuppressLint("MissingPermission")
    private fun playbackRecord(): AudioRecord? {
        if (Build.VERSION.SDK_INT < 29) return null
        val p = projection ?: return null
        return try {
            val config = AudioPlaybackCaptureConfiguration.Builder(p)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            AudioRecord.Builder()
                .setAudioFormat(format())
                .setBufferSizeInBytes(bufferBytes())
                .setAudioPlaybackCaptureConfig(config)
                .build()
                .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        } catch (e: Exception) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun micRecord(): AudioRecord? {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.MIC
        return try {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(format())
                .setBufferSizeInBytes(bufferBytes())
                .build()
                .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        } catch (e: Exception) {
            null
        }
    }

    private fun rms(buf: FloatArray, n: Int): Float {
        var s = 0f
        for (i in 0 until n) s += buf[i] * buf[i]
        return sqrt(s / max(1, n))
    }

    /**
     * The phone's output mix through Android's Visualizer effect (session 0). Unlike playback capture,
     * apps such as Spotify can't opt out of it, so it is what we use when capture hears nothing.
     */
    private inner class MixTap {
        val viz: Visualizer
        val rate: Int
        private val wave: ByteArray
        private var lastNs = 0L
        init {
            viz = Visualizer(0)
            viz.enabled = false
            viz.captureSize = Visualizer.getCaptureSizeRange()[1]
            viz.scalingMode = Visualizer.SCALING_MODE_NORMALIZED
            viz.enabled = true
            rate = viz.samplingRate / 1000
            wave = ByteArray(viz.captureSize)
        }
        /** Copies the samples played since the last call (newest last) into out; returns how many. */
        fun read(out: FloatArray): Int {
            if (viz.getWaveForm(wave) != Visualizer.SUCCESS) return 0
            val now = System.nanoTime()
            val fresh = if (lastNs == 0L) out.size else ((now - lastNs) * rate / 1_000_000_000L).toInt()
            lastNs = now
            val k = fresh.coerceIn(0, minOf(out.size, wave.size))
            val off = wave.size - k
            for (i in 0 until k) out[i] = ((wave[off + i].toInt() and 0xff) - 128) / 128f
            return k
        }
        fun release() { try { viz.enabled = false; viz.release() } catch (_: Exception) {} }
    }

    private fun startWorker(mode: String) {
        running = true
        worker = Thread({
            val buf = FloatArray(256)          // ~5 ms per read
            val mixBuf = FloatArray(1024)
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val device = if (mode == "device") playbackRecord() else null
            val tap: MixTap? = if (mode == "device") try { MixTap() } catch (e: Exception) { null } else null
            var mic: AudioRecord? = null
            device?.startRecording()
            // which source feeds the visuals: "device" (playback capture), "mix" (Visualizer), "mic"
            var using = ""
            fun use(src: String, rate: Int) {
                if (using == src) return
                using = src
                Engine.via = when (src) { "device" -> "phone"; "mix" -> "phone mix"; else -> "mic" }
                Engine.analyzer.setSource(true, rate)
                Engine.status = when (src) { "device", "mix" -> "device"; else -> "mic" }
            }
            var devQuietMs = 99999
            var mixQuietMs = 99999
            if (mode != "device") {
                mic = micRecord(); mic?.startRecording()
                if (mic == null) Engine.status = "no-permission" else use("mic", SAMPLE_RATE)
            }
            while (running) {
                if (mode != "device") {
                    val m = mic ?: break
                    val n = m.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                    if (n > 0) Engine.analyzer.process(buf, n)
                    continue
                }
                // 1) playback capture (best quality), when the playing app allows it
                var n = 0
                if (device != null) {
                    n = device.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                    if (n > 0) { if (rms(buf, n) > 1e-4f) devQuietMs = 0 else devQuietMs += n * 1000 / SAMPLE_RATE }
                } else Thread.sleep(5)
                // 2) the output mix: works for Spotify and other apps that block capture
                var k = 0
                if (tap != null) {
                    k = tap.read(mixBuf)
                    if (k > 0) { if (rms(mixBuf, k) > 2e-3f) mixQuietMs = 0 else mixQuietMs += k * 1000 / tap.rate }
                }
                when {
                    devQuietMs < 1500 && n > 0 -> { use("device", SAMPLE_RATE); Engine.analyzer.process(buf, n) }
                    tap != null && (mixQuietMs < 1500 || am.isMusicActive) -> { use("mix", tap.rate); if (k > 0) Engine.analyzer.process(mixBuf, k) }
                    // the phone is silent: stay on the phone's sound (the visuals go dark). The microphone is only
                    // ever used when you choose Microphone yourself.
                    n > 0 -> { use("device", SAMPLE_RATE); Engine.analyzer.process(buf, n) }
                    tap != null -> { use("mix", tap.rate); if (k > 0) Engine.analyzer.process(mixBuf, k) }
                    else -> { use("device", SAMPLE_RATE) }
                }
            }
            try { device?.stop() } catch (_: Exception) {}
            device?.release()
            tap?.release()
            try { mic?.stop() } catch (_: Exception) {}
            mic?.release()
        }, "nocturne-capture")
        worker?.priority = Thread.MAX_PRIORITY
        worker?.start()
    }

    private fun stopWorker() {
        running = false
        try { worker?.join(500) } catch (_: InterruptedException) {}
        worker = null
    }

    override fun onDestroy() {
        stopWorker()
        projection?.stop()
        projection = null
        if (Engine.status != "demo") {            // the demo track may already be playing into the analyser
            Engine.analyzer.setSource(false, SAMPLE_RATE)
            Engine.status = "idle"
        }
        super.onDestroy()
    }

    companion object {
        const val SAMPLE_RATE = 48000
        private const val CHANNEL = "capture"
        private const val NOTIFICATION_ID = 1
    }
}
