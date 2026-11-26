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
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Captures sound while the visualizer is open.
 *  - mode "device": the sound this phone plays (Android 10+). When nothing has played for 2.5 s it listens
 *    through the microphone instead, and switches back as soon as the phone plays again.
 *  - mode "mic": the microphone only.
 * Some apps block capture of their sound; for those the microphone fallback takes over automatically.
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

    private fun startWorker(mode: String) {
        running = true
        worker = Thread({
            val buf = FloatArray(256)          // ~5 ms per read
            val probe = FloatArray(960)
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val device = if (mode == "device") playbackRecord() else null
            var mic: AudioRecord? = null
            var useMic = device == null
            if (useMic) {
                mic = micRecord()
                Engine.status = if (mic == null) "no-permission"
                    else if (mode == "device") (if (projection == null) "capture-denied" else "capture-failed") else "mic"
            } else {
                Engine.status = "device"
            }
            device?.startRecording()
            mic?.startRecording()
            var quietMs = 0
            var loudMs = 0
            while (running) {
                if (!useMic && device != null) {
                    val n = device.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                    if (n <= 0) { Thread.sleep(10); continue }
                    if (rms(buf, n) < 1e-4f) quietMs += n * 1000 / SAMPLE_RATE else { quietMs = 0; if (Engine.status != "device") Engine.status = "device" }
                    if (quietMs > 2500) {
                        if (am.isMusicActive) {
                            // the phone IS playing but we hear nothing: that app blocks capture. Never switch to
                            // the microphone here (it can't hear headphones); tell the user instead.
                            Engine.status = "blocked"
                        } else {
                            // nothing is playing on this phone: listen through the microphone until it plays again
                            if (mic == null) { mic = micRecord(); mic?.startRecording() }
                            if (mic != null) { useMic = true; loudMs = 0; Engine.status = "mic-fallback" }
                        }
                    }
                    if (!useMic) Engine.analyzer.process(buf, n)
                } else {
                    val m = mic ?: break
                    val n = m.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                    if (n > 0) Engine.analyzer.process(buf, n)
                    if (device != null) {
                        val k = device.read(probe, 0, probe.size, AudioRecord.READ_NON_BLOCKING)
                        if (k > 0) {
                            if (rms(probe, k) > 1e-4f) loudMs += k * 1000 / SAMPLE_RATE else loudMs = 0
                            if (loudMs > 100) { useMic = false; quietMs = 0; Engine.status = "device" }
                        }
                        // the phone started playing (even an app that blocks capture): stop using the microphone
                        if (useMic && am.isMusicActive) { useMic = false; quietMs = 0; Engine.status = "device" }
                    }
                }
            }
            try { device?.stop() } catch (_: Exception) {}
            device?.release()
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
