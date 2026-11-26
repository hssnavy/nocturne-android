package com.nocturne.visualizer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * Plays the bundled track (assets/track.mp3). The decoded sound goes to the speaker and, at the exact moment
 * the speaker plays it, to the analyser, so the visuals are in step with what you hear.
 */
class DemoPlayer(private val ctx: Context) {
    @Volatile private var running = false
    private var worker: Thread? = null
    @Volatile var durationUs = 0L; private set
    @Volatile var positionUs = 0L; private set
    @Volatile private var seekTo = -1L
    val isPlaying get() = running
    @Volatile var paused = false

    fun start() {
        stop()
        paused = false
        running = true
        worker = Thread({ loop() }, "nocturne-demo").also { it.priority = Thread.MAX_PRIORITY; it.start() }
    }

    fun stop() {
        running = false
        try { worker?.join(1000) } catch (_: InterruptedException) {}
        worker = null
    }

    fun seek(us: Long) { seekTo = max(0L, us) }

    private fun makeTrack(rate: Int, channels: Int): AudioTrack {
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(mask).build())
            .setBufferSizeInBytes(min * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
            .also { it.play() }
    }

    private fun loop() {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        var track: AudioTrack? = null
        try {
            ctx.assets.openFd("track.mp3").use { ex.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            var ti = -1
            for (i in 0 until ex.trackCount) if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { ti = i; break }
            if (ti < 0) return
            ex.selectTrack(ti)
            val fmt = ex.getTrackFormat(ti)
            durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var chs = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(fmt, null, null, 0); c.start()
            var t = makeTrack(rate, chs); track = t
            Engine.analyzer.setSource(true, rate)
            Engine.status = "demo"

            // decoded sound waiting to be heard: fed to the analyser when the speaker reaches it
            var pend = FloatArray(rate * 2)
            var written = 0L; var fed = 0L; var baseUs = 0L
            val chunk = FloatArray(256)
            val info = MediaCodec.BufferInfo()
            var inputDone = false

            fun feed() {
                val head = t.playbackHeadPosition.toLong() and 0xffffffffL
                var n = 0
                while (fed < min(head, written)) {
                    chunk[n++] = pend[(fed % pend.size).toInt()]; fed++
                    if (n == chunk.size) { Engine.analyzer.process(chunk, n); n = 0 }
                }
                if (n > 0) Engine.analyzer.process(chunk, n)
                positionUs = baseUs + head * 1_000_000L / rate
                Engine.demoPos = positionUs / 1e6
            }

            var wasPaused = false
            while (running) {
                if (paused) {                                    // pause: the speaker stops, the visuals go quiet
                    if (!wasPaused) { t.pause(); wasPaused = true; Engine.demoPos = -1.0 }
                    Thread.sleep(15); continue
                } else if (wasPaused) { t.play(); wasPaused = false }
                val sk = seekTo
                if (sk >= 0) {
                    seekTo = -1
                    ex.seekTo(sk, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    c.flush(); inputDone = false
                    t.pause(); t.flush(); t.play()
                    baseUs = max(0L, ex.sampleTime); written = 0; fed = 0
                }
                if (!inputDone) {
                    val ii = c.dequeueInputBuffer(2000)
                    if (ii >= 0) {
                        val buf = c.getInputBuffer(ii)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) { c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { c.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val oi = c.dequeueOutputBuffer(info, 2000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = c.outputFormat
                    val nr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); val nc = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (nr != rate || nc != chs) {
                        rate = nr; chs = nc
                        t.release(); t = makeTrack(rate, chs); track = t
                        pend = FloatArray(rate * 2); written = 0; fed = 0
                        Engine.analyzer.setSource(true, rate)
                    }
                } else if (oi >= 0) {
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        c.releaseOutputBuffer(oi, false)
                        seekTo = 0                                   // loop back to the start
                        continue
                    }
                    val ob = c.getOutputBuffer(oi)!!
                    ob.position(info.offset); ob.limit(info.offset + info.size)
                    val sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer()
                    val total = sb.remaining()
                    val shorts = ShortArray(total); sb.get(shorts)
                    c.releaseOutputBuffer(oi, false)
                    val frames = total / chs
                    for (fr in 0 until frames) {
                        var s = 0f
                        for (k in 0 until chs) s += shorts[fr * chs + k]
                        pend[(written % pend.size).toInt()] = s / (chs * 32768f)
                        written++
                    }
                    var off = 0
                    while (off < total && running && seekTo < 0) {
                        val w = t.write(shorts, off, min(total - off, 2048))
                        if (w <= 0) break
                        off += w
                        feed()
                    }
                }
                feed()
            }
        } catch (e: Exception) {
            Engine.status = "demo-error"
        } finally {
            Engine.demoPos = -1.0
            try { track?.stop() } catch (_: Exception) {}
            track?.release()
            try { codec?.stop() } catch (_: Exception) {}
            codec?.release()
            ex.release()
            if (Engine.status == "demo" || Engine.status == "demo-error") {
                Engine.analyzer.setSource(false, 48000)
                if (Engine.status == "demo") Engine.status = "idle"
            }
        }
    }
}
