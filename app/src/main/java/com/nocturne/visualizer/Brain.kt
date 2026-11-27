package com.nocturne.visualizer

import android.content.Context
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Spectrum of the newest N samples, exactly like the browser's analyser (Blackman window, -100..-18 dB -> 0..255). */
class Spectrum(val n: Int) {
    var smoothing = 0f
    private val mag = DoubleArray(n / 2)
    private val fft = Fft(n)
    private val win = FloatArray(n) { (0.42 - 0.5 * cos(2 * PI * it / n) + 0.08 * cos(4 * PI * it / n)).toFloat() }
    private val re = FloatArray(n); private val im = FloatArray(n)
    val samples = FloatArray(n)
    val bytes = IntArray(n / 2)
    var subPower = 0.0          // power under 80 Hz, unclipped (loud EDM sub sits above the byte scale's ceiling)

    fun compute(sampleRate: Int) {
        for (i in 0 until n) { re[i] = samples[i] * win[i]; im[i] = 0f }
        fft.transform(re, im)
        val hz = sampleRate.toDouble() / n
        subPower = 0.0
        var i = 1
        while (i * hz < 80) { val m = sqrt((re[i] * re[i] + im[i] * im[i]).toDouble()) / n; subPower += m * m; i++ }
        for (k in 0 until n / 2) {
            val m0 = sqrt((re[k] * re[k] + im[k] * im[k]).toDouble()) / n
            mag[k] = smoothing * mag[k] + (1 - smoothing) * m0; val m = mag[k]
            val db = 20 * log10(m + 1e-12)
            bytes[k] = floor(min(1.0, max(0.0, (db + 100) / 82)) * 255).toInt()
        }
    }
}

/**
 * Everything the web version's analyser does, in the same order and with the same numbers:
 * the fine tone map (every note), the 10 + .1 channels, the three bands, the kick drum (.1 channel),
 * tempo, the learned energy, the Wave's strings and the Light speed ship.
 * Runs on the drawing thread once per frame; the 10 ms steps are worked through from the raw sound.
 */
class Brain(ctx: Context) {
    // settings (same defaults as the web page)
    val strings get() = Settings.strings
    private val sens get() = Settings.sens
    private val relR get() = when (Settings.response) { "smooth" -> 5f; "fast" -> 10f; else -> 20f }
    private val fall get() = when (Settings.peaks) { "off" -> 99f; "slow" -> 0.35f; else -> 1.3f }

    companion object {
        const val SPEC_N = 128; const val WAVE_N = 256; const val TONE_N = 256
        val BANDS = arrayOf(intArrayOf(25, 150), intArrayOf(150, 2500), intArrayOf(2500, 16000))
        val CH = arrayOf(intArrayOf(22, 44), intArrayOf(44, 88), intArrayOf(88, 177), intArrayOf(177, 355), intArrayOf(355, 710),
            intArrayOf(710, 1420), intArrayOf(1420, 2840), intArrayOf(2840, 5680), intArrayOf(5680, 11360), intArrayOf(11360, 20000), intArrayOf(20, 80))
    }

    private val ring = Engine.analyzer
    private val an = Spectrum(1024)          // "analyser" (render-frame view)
    private val tone4 = Spectrum(4096)        // fine tone analyser
    private val hopSpec = Spectrum(1024)      // the exact 10 ms steps (tempo + learned energy + kick)

    // outputs for the renderer
    val toneBytes = ByteArray(TONE_N); val toneSBytes = ByteArray(TONE_N); val specBytes = ByteArray(SPEC_N); val peakBytes = ByteArray(SPEC_N); val waveBytes = ByteArray(WAVE_N) { 128.toByte() }
    val chLev = FloatArray(11); val chHit = FloatArray(11)
    val lev = FloatArray(3); val hit = FloatArray(3); val clock = FloatArray(3)
    var E = 0f; var Eg = 0f; var int_ = 1f; var aggr = 0f; var red = 0f; var assemble = 0f; var level = 0f; var tempo = 1f
    var core = 0f; var kickT = 0.0; var bpm = 0f
    val shake = FloatArray(2)
    var live = false; private set
    // wave strings
    val strWK = FloatArray(7) { 8f }; val strGlow = FloatArray(7); private val strAct = FloatArray(7); private val strRest = FloatArray(7); private val strCAvg = FloatArray(7) { 0.5f }; var bgT = 0f; private val strPk = FloatArray(7) { -60f }; val strL = FloatArray(7); val strP = FloatArray(7); val strHit = FloatArray(7); val strAge = FloatArray(7) { 9f }
    private val strS = FloatArray(7); private val strSt = FloatArray(7); private val strLast = DoubleArray(7)
    private val strHist = Array(7) { ArrayDeque<Float>() }; private val strPrev = FloatArray(TONE_N); private var strN = -1
    var waveK = 6f
    // light speed ship
    var hsV = 0f; var hsX = 0f; var hsB = 0f; var hsE = 0f; var hsSpin = 0f

    // internal state
    private val toneDb = FloatArray(TONE_N); private val toneFl = FloatArray(TONE_N) { -100f }; private val toneV = FloatArray(TONE_N); private val toneVs = FloatArray(TONE_N); private val pkB = FloatArray(TONE_N) { -90f }; private val flS = FloatArray(TONE_N) { -100f }
    private val pkR = floatArrayOf(-60f, -60f, -60f)
    private val spec = FloatArray(SPEC_N); private val peak = FloatArray(SPEC_N); private val binAvg = FloatArray(SPEC_N); private val tmp = FloatArray(SPEC_N)
    private var gpk = 0.5f
    private var prev: IntArray? = null
    private val chE = FloatArray(11); private val chF = FloatArray(11); private val chT = FloatArray(11); private val chLast = DoubleArray(11)
    private val chHist = Array(11) { ArrayDeque<Float>() }
    private val avg = floatArrayOf(0.3f, 0.3f, 0.3f); private val bmax = floatArrayOf(0.2f, 0.2f, 0.2f)
    private val fluxH = Array(3) { ArrayDeque<Float>() }; private val lastHit = DoubleArray(3)
    private var loudS = 0f; private val histL = ArrayDeque<Float>(); private val histS = ArrayDeque<Float>(); private var sampleT = 0f; private var pT = 0f
    private var pl97 = 1f; private var plmax = 1f; private var ps10 = 0f; private var ps50 = 1f; private var ps95 = 1f; private var psmax = 1f
    private var waveF = FloatArray(WAVE_N); private var wgain = 2f
    private val tdata = IntArray(1024)

    // learned energy (see the web page for how it was fitted to your teaching)
    private val Lw = floatArrayOf(11.219f, 7.601f, 3.773f, -9.891f, 2.944f, 74.595f)
    private val Lmu = floatArrayOf(-0.60193f, -13.11202f, -1.56391f, 0.00146f, 0.02994f)
    private val Lsd = floatArrayOf(7.46035f, 5.27088f, 2.51173f, 0.00361f, 0.00503f)
    private var Lx: FloatArray? = null; private var Lpk = 0f; private var LkickL = 0f; private var LE = -1f
    private val Lprev = IntArray(512); private var primed = false; private var pos = -1L
    private val subH = ArrayDeque<Double>(); private var subPk = Double.NaN; private var subAvg = Double.NaN; private var subT = 0
    // tempo
    private val tGrid = FloatArray(800); private val tLow = FloatArray(800); private var tPos = 0; private var tFilled = 0; private var tHops = 0; private var tDisagree = 0
    private val tPrev = IntArray(512)
    // the Demo track's taught curve
    private var demoV: FloatArray? = null; private var demoStep = 0.5
    private var gen = -1

    init {
        try {
            val o = JSONObject(ctx.assets.open("demo_energy.json").bufferedReader().use { it.readText() })
            val a = o.getJSONArray("v"); demoStep = o.getDouble("step")
            demoV = FloatArray(a.length()) { a.getDouble(it).toFloat() }
        } catch (_: Exception) {}
    }

    private fun resetSource() {
        Lx = null; pos = -1; LE = -1f; primed = false; subH.clear(); subPk = Double.NaN; subAvg = Double.NaN
        tFilled = 0; tPos = 0; bpm = 0f; tDisagree = 0; tHops = 0
        hsV = 0f; hsB = 0f; hsE = 0f
    }

    fun resetShip() { hsV = 0f; hsB = 0f; hsE = 0f }

    private fun taughtEnergy(): Float {
        val v = demoV ?: return -1f
        val p = Engine.demoPos; if (p < 0) return -1f
        val c = p / demoStep; var sw = 0.0; var sv = 0.0
        for (d in -8..8) { val i = c.roundToInt() + d; if (i in v.indices && v[i] >= 0) { val g = exp(-0.5 * (d / 4.0) * (d / 4.0)); sw += g; sv += g * v[i] } }
        return if (sw > 0.5) (sv / sw / 10).toFloat() else -1f
    }

    /** One frame: work through the 10 ms steps, then analyse the newest sound. */
    fun update(dt: Float, now: Double) {
        if (ring.generation != gen) { gen = ring.generation; resetSource() }
        live = ring.active && ring.total > 0
        val sr = ring.sampleRate
        if (live) hops(sr, now)
        analyse(dt, now, sr)
        strings(dt, now)
    }

    // ---------------- exact 10 ms steps: kick drum (.1), learned energy, tempo ----------------
    private fun pw(T: IntArray, hz: Double, a: Double, b: Double): Float {
        var p = 0.0; var i = max(1, ceil(a / hz).toInt())
        while (i < 512 && i * hz < b) { p += 10.0.pow((-100 + T[i] / 255.0 * 82) / 10); i++ }
        return (10 * log10(p + 1e-12)).toFloat()
    }

    private fun hops(sr: Int, now: Double) {
        val hop = (sr * 0.01).roundToInt(); val hz = sr / 1024.0
        val end = ring.total
        if (pos < 0 || end - pos > 30000) { pos = end - hop * 3; primed = false }
        var steps = 0
        val T = hopSpec.bytes
        while (pos + hop <= end && steps < 60) {
            pos += hop; steps++
            ring.read(pos, 1024, hopSpec.samples); hopSpec.compute(sr)
            tempoHop(T, hz)
            if (!primed) { System.arraycopy(T, 0, Lprev, 0, 512); primed = true; continue }
            var k = 0.0; var kn = 0; var i = max(1, ceil(60 / hz).toInt())
            while (i * hz < 200) { k += max(0, T[i] - Lprev[i]) / 255.0; kn++; i++ }
            System.arraycopy(T, 0, Lprev, 0, 512)
            // .1 channel kick drum: the sub jumps >= 8 dB above where it was 20-50 ms earlier, lands >= 3 dB over the
            // last second's average and near the kick's usual level; at most one per beat
            run {
                val sdb = 10 * log10(hopSpec.subPower + 1e-14)
                subH.addLast(sdb); if (subH.size > 6) subH.removeFirst()
                subPk = if (subPk.isNaN()) sdb else max(sdb, subPk - 0.1 * 0.01)
                subT++
                subAvg = if (subAvg.isNaN()) sdb else subAvg + (sdb - subAvg) * 0.01
                var lo = Double.MAX_VALUE; for (j in 0 until subH.size - 1) lo = min(lo, subH[j])
                val rise = sdb - lo
                if (subH.size >= 4 && rise > 8 && sdb > subAvg + 3 && sdb > subPk - 6 && subT > max(25.0, if (bpm > 0) 0.7 * 6000 / bpm else 0.0)) {
                    val ks = min(1.0, 0.65 + (rise - 8) / 14).toFloat(); subT = 0
                    core = max(core, ks)
                    hit[0] = max(hit[0], ks * min(1f, 0.4f + Eg))
                    lastHit[0] = now; kickT = now
                    if (Settings.shake && E > 0.55f) { val ang = Math.random() * PI * 2; val m = 0.006f * int_ * ks; shake[0] = (cos(ang) * m).toFloat(); shake[1] = (sin(ang) * m).toFloat() }
                }
            }
            val raw = floatArrayOf(pw(T, hz, 60.0, 200.0), pw(T, hz, 200.0, 800.0), pw(T, hz, 3000.0, 10000.0), pw(T, hz, 0.0, sr / 2.0), (k / max(1, kn)).toFloat())
            if (raw[3] < -90) continue
            val X = Lx ?: raw.copyOf().also { Lx = it; Lpk = raw[3]; LkickL = raw[4] }
            for (j in 0 until 5) X[j] += (raw[j] - X[j]) * (0.01f / 3)
            Lpk = max(X[3], Lpk - 0.1f * 0.01f)
            LkickL += (raw[4] - LkickL) * (0.01f / 30)
        }
        val X = Lx ?: return
        val f = floatArrayOf(X[0] - X[1], X[2] - X[1], X[3] - Lpk, X[4] - LkickL, X[4])
        var e = Lw[5]; for (j in 0 until 5) e += Lw[j] * (f[j] - Lmu[j]) / Lsd[j]
        LE = min(1f, max(0f, e / 100))
    }

    private fun tempoHop(T: IntArray, hz: Double) {
        val top = min(512, ceil(5000 / hz).toInt())
        var o = 0.0; var ol = 0.0
        for (i in 1 until top) { val d = T[i] - tPrev[i]; if (d > 0) { o += d * (if (i * hz < 200) 1.5 else 1.0); if (i * hz < 150) ol += d } }
        System.arraycopy(T, 0, tPrev, 0, 512)
        tGrid[tPos] = o.toFloat(); tLow[tPos] = ol.toFloat(); tPos = (tPos + 1) % 800; tFilled++
        tHops++
        if (tFilled < 400 || tHops < 50) return
        tHops = 0
        val N = min(800, tFilled)
        val x = FloatArray(N); var m = 0.0
        for (i in 0 until N) { x[i] = tLow[(tPos - N + i + 1600) % 800]; m += x[i] }
        m = m / N + 1e-9
        for (i in 0 until N) x[i] = ln1p(x[i] / m).toFloat()
        val y = FloatArray(N); var run = 0.0
        for (i in 0 until N) { run += x[i] - (if (i >= 31) x[i - 31] else 0f); val c = min(i + 1, 31); val j = max(0, i - 15); y[j] = max(0.0, x[j] - run / c).toFloat() }
        var ym = 0.0; for (i in 0 until N) ym += y[i]; ym /= N
        val r = DoubleArray(203)
        for (lag in 27..202) { var sum = 0.0; for (i in lag until N) sum += (y[i] - ym) * (y[i - lag] - ym); r[lag] = sum / (N - lag) }
        var bestLag = 0; var best = -1e9
        for (lag in 30..100) { val sc = r[lag] * exp(-0.5 * (log2(6000.0 / lag / 120) / 0.8).pow(2)); if (sc > best) { best = sc; bestLag = lag } }
        if (bestLag == 0 || r[bestLag] <= 0) return
        fun pk(c: Double): Double { val i = round(c).toInt(); return max(r[i - 1], max(r[i], r[i + 1])) }
        val cands = listOf(bestLag / 2.0, bestLag.toDouble(), bestLag * 2.0).filter { it >= 30 && it <= 100 }.sorted()
        val topS = cands.maxOf { pk(it) }
        var chosen = bestLag.toDouble(); for (c in cands) if (pk(c) >= 0.5 * topS) { chosen = c; break }
        val i0 = max(28, floor(chosen - 1.5).toInt()); val i1 = min(201, ceil(chosen + 1.5).toInt()); var bi = i0
        for (i in i0..i1) if (r[i] > r[bi]) bi = i
        val den = r[bi - 1] - 2 * r[bi] + r[bi + 1]
        val lag = bi + if (den < 0) max(-0.5, min(0.5, 0.5 * (r[bi - 1] - r[bi + 1]) / den)) else 0.0
        val nb = (6000 / lag).toFloat()
        if (bpm == 0f) bpm = nb
        else if (abs(nb - bpm) / bpm < 0.05f) { bpm += (nb - bpm) * 0.4f; tDisagree = 0 }
        else if (++tDisagree >= 3) { bpm = nb; tDisagree = 0 }
    }

    // ---------------- per frame: same as the web page's analyse() ----------------
    private fun meanSd(h: ArrayDeque<Float>): Pair<Float, Float> {
        var m = 0f; for (v in h) m += v; m /= h.size
        var s = 0f; for (v in h) s += (v - m) * (v - m)
        return Pair(m, sqrt(s / h.size))
    }

    private fun analyse(dt: Float, now: Double, sr: Int) {
        val e = FloatArray(3); val flux = FloatArray(3)
        val freq = an.bytes
        if (live) {
            val end = ring.total
            an.smoothing = when (Settings.response) { "smooth" -> 0.5f; "fast" -> 0.15f; else -> 0f }
            ring.read(end, 1024, an.samples); an.compute(sr)
            ring.read(end, 4096, tone4.samples); tone4.compute(sr)
            for (i in 0 until 1024) tdata[i] = max(0, min(255, (128 * (1 + an.samples[i])).toInt()))
            val hz = sr / 1024.0; val hzT = sr / 4096.0
            var tmax = -200f; val rmax = floatArrayOf(-200f, -200f, -200f)
            for (i in 0 until TONE_N) {
                val f0 = 20 * 1000.0.pow(i.toDouble() / TONE_N); val f1 = 20 * 1000.0.pow((i + 1.0) / TONE_N)
                var m = 0.0
                val useT = f1 < 1500
                val F = if (useT) tone4.bytes else freq; val w = if (useT) hzT else hz
                if (f1 - f0 < w) {
                    val c = sqrt(f0 * f1) / w - 0.5; val j = max(0, floor(c).toInt()); val u = c - j
                    m = F[j] * (1 - u) + F[min(F.size - 1, j + 1)] * u
                } else { val a = floor(f0 / w).toInt(); val b = floor(f1 / w).toInt(); var j = a; while (j <= b && j < F.size) { if (F[j] > m) m = F[j].toDouble(); j++ } }
                val d = if (m < 8) -200f else (-100 + m / 255 * 82 + 2.5 * log2(max(f0, 200.0) / 1000)).toFloat()
                toneDb[i] = d; if (d > tmax) tmax = d
                val rg = if (i < 86) 0 else if (i < 171) 1 else 2; if (d > rmax[rg]) rmax[rg] = d
            }
            for (r in 0 until 3) pkR[r] = max(rmax[r], max(pkR[r] - dt * 1.5f, -75f))
            for (i in 0 until TONE_N) {
                val d = toneDb[i]
                if (d > -150) toneFl[i] += (d - toneFl[i]) * min(1f, dt * (if (d < toneFl[i]) 5f else 0.025f))
                val pk = pkR[if (i < 86) 0 else if (i < 171) 1 else 2]
                val ref = max(toneFl[i] + 3, pk - 45)
                var t = min(1.2f, max(0f, (d - ref) / (pk + 2 - ref + 1e-3f)) * sens)
                if (d < -150) t = 0f
                toneV[i] = if (t > toneV[i]) t else toneV[i] + (t - toneV[i]) * min(1f, dt * 22)
                toneBytes[i] = min(255f, toneV[i] * 196).toInt().toByte()
            }
            // strings' tone map: every bin against its own recent peak (falls 2 dB/s)
            for (i in 0 until TONE_N) {
                val d = toneDb[i]
                pkB[i] = max(d, max(pkB[i] - dt * 2, -90f))
                flS[i] += (d - flS[i]) * min(1f, dt * (if (d < flS[i]) 5f else 0.006f))
                val ref = max(flS[i] + 3, pkB[i] - 36)
                var t = min(1.2f, max(0f, (d - ref) / (pkB[i] + 1 - ref + 1e-3f)) * sens)
                if (d < -150) t = 0f
                toneVs[i] = if (t > toneVs[i]) t else toneVs[i] + (t - toneVs[i]) * min(1f, dt * 22)
                toneSBytes[i] = min(255f, toneVs[i] * 196).toInt().toByte()
            }
            var gmax = 0f
            for (i in 0 until SPEC_N) {
                val f0 = 30 * (16000.0 / 30).pow(i.toDouble() / SPEC_N); val f1 = 30 * (16000.0 / 30).pow((i + 1.0) / SPEC_N)
                val a = floor(f0 / hz).toInt(); val b = max(a, floor(f1 / hz).toInt()); var s2 = 0.0; var m = 0
                for (j in a..b) { val v = freq[min(j, freq.size - 1)]; s2 += v; if (v > m) m = v }
                tmp[i] = ((s2 / (b - a + 1) * 0.5 + m * 0.5) / 255 * (0.8 + 1.1 * (i.toDouble() / SPEC_N).pow(1.3))).toFloat()
                if (tmp[i] > gmax) gmax = tmp[i]
            }
            gpk = max(gpk * 0.93f.pow(dt), max(gmax, 0.25f))
            for (i in 0 until SPEC_N) {
                binAvg[i] += (tmp[i] - binAvg[i]) * min(1f, dt / 3)
                val nov = min(1.2f, max(0f, (tmp[i] - binAvg[i]) / (binAvg[i] * 0.6f + 0.03f)))
                val ti = max(0, min(TONE_N - 2, (TONE_N * ln(30 * (16000.0 / 30).pow((i + 0.5) / SPEC_N) / 20) / ln(1000.0)).roundToInt()))
                val tv = max(toneV[ti], toneV[ti + 1])
                val v = min(1.25f, tv * 0.8f + nov * 0.35f * sens)
                spec[i] = if (v > spec[i]) v else spec[i] + (v - spec[i]) * min(1f, dt * relR * 1.6f)
            }
            val pv = prev ?: IntArray(freq.size).also { prev = it }
            for (b in 0 until 3) {
                val lo = max(1, floor(BANDS[b][0] / hz).toInt()); val hi = min(freq.size - 1, ceil(BANDS[b][1] / hz).toInt())
                var s2 = 0.0; var f = 0.0
                for (i in lo..hi) { s2 += freq[i]; val d = freq[i] - pv[i]; if (d > 0) f += d }
                val n = hi - lo + 1
                e[b] = min(1.0, s2 / (n * 255) * sens).toFloat(); flux[b] = (f / (n * 255) * sens).toFloat()
            }
            for (c in 0 until 11) {
                val lo = max(1, floor(CH[c][0] / hz).toInt()); val hi = min(freq.size - 1, max(lo, ceil(CH[c][1] / hz).toInt()))
                var s2 = 0.0; var f = 0.0
                for (i in lo..hi) { s2 += freq[i]; val d = freq[i] - pv[i]; if (d > 0) f += d }
                val n = hi - lo + 1
                chE[c] = min(1.0, s2 / (n * 255) * sens).toFloat(); chF[c] = (f / (n * 255) * sens).toFloat()
                val ti0 = max(0, floor(TONE_N * ln(max(20.0, CH[c][0].toDouble()) / 20) / ln(1000.0)).toInt())
                val ti1 = min(TONE_N - 1, max(ti0, ceil(TONE_N * ln(min(20000.0, CH[c][1].toDouble()) / 20) / ln(1000.0)).toInt()))
                var tm = 0f; var ts = 0f; for (i in ti0..ti1) { tm = max(tm, toneV[i]); ts += toneV[i] }
                chT[c] = tm * 0.6f + ts / (ti1 - ti0 + 1) * 0.4f
            }
            System.arraycopy(freq, 0, pv, 0, freq.size)
            // oscilloscope wave
            var start = 0; for (i in 1 until 1024) if (tdata[i - 1] < 128 && tdata[i] >= 128) { start = i; break }
            start = min(start, 1024 - WAVE_N * 3 - 4)
            var dev = 0; for (i in 0 until WAVE_N) { val d = abs(tdata[start + i * 3] - 128); if (d > dev) dev = d }
            wgain += (min(5f, 60f / max(dev, 4)) - wgain) * min(1f, dt * 3)
            val k = min(1f, dt * 6)
            for (i in 0 until WAVE_N) {
                var sum = 0; for (j in -3..3) sum += tdata[max(0, min(1023, start + i * 3 + j))] - 128
                waveF[i] += (sum / 7f * wgain - waveF[i]) * k
                waveBytes[i] = max(0f, min(255f, 128 + waveF[i])).toInt().toByte()
            }
        } else {
            for (i in 0 until SPEC_N) spec[i] *= exp(-dt * 4)
            for (i in 0 until WAVE_N) waveBytes[i] = 128.toByte()
            for (i in 0 until TONE_N) { toneV[i] *= exp(-dt * 6); toneBytes[i] = min(255f, toneV[i] * 196).toInt().toByte(); toneVs[i] *= exp(-dt * 6); toneSBytes[i] = min(255f, toneVs[i] * 196).toInt().toByte(); toneDb[i] = -200f }
        }
        for (i in 0 until SPEC_N) {
            peak[i] = if (fall > 50) spec[i] else max(peak[i] - fall * dt, spec[i])
            specBytes[i] = min(255f, spec[i] * 255).toInt().toByte(); peakBytes[i] = min(255f, peak[i] * 255).toInt().toByte()
        }

        val loud = e[0] * 0.5f + e[1] * 0.3f + e[2] * 0.2f
        loudS += (loud - loudS) * min(1f, dt * 2.5f)
        if (live) {
            sampleT += dt
            if (sampleT > 0.1f) { sampleT = 0f; histL.addLast(loud); histS.addLast(loudS); if (histL.size > 1200) { histL.removeFirst(); histS.removeFirst() } }
            pT += dt
            if (pT > 1 && histL.size > 60) {
                pT = 0f
                val l = histL.sorted(); val sS = histS.sorted()
                fun qq(a: List<Float>, f: Float) = a[min(a.size - 1, floor(a.size * f).toInt())]
                pl97 = qq(l, 0.97f); plmax = l.last(); ps10 = qq(sS, 0.1f); ps50 = qq(sS, 0.5f); ps95 = qq(sS, 0.95f); psmax = sS.last()
            }
        }
        val warm = live && histL.size > 50
        var Et = if (!live) 0f else if (LE >= 0) LE
            else if (warm) min(1f, max(0f, (loudS - ps10) / max(0.02f, ps95 - ps10)))
            else min(0.8f, max(0f, (loudS - 0.04f) / 0.45f))
        val taught = if (live) taughtEnergy() else -1f
        if (taught >= 0) Et = taught else Et = min(1f, max(0f, 0.5f + (Et - 0.5f) * 1.3f))
        E += (Et - E) * min(1f, dt * (if (taught >= 0) 4 else 3))
        val gate = min(1f, max(0f, (loudS - 0.02f) / 0.08f))
        Eg = gate * (0.3f + 0.7f * E)
        int_ = 0.45f + 1.7f * E.pow(1.3f)

        val fallR = floatArrayOf(9f * relR / 10, 8f * relR / 10, 13f * relR / 10)
        for (b in 0 until 3) {
            avg[b] += (e[b] - avg[b]) * min(1f, dt / 4)
            bmax[b] = max(bmax[b] * 0.99f.pow(dt), max(e[b], 0.08f))
            val rel = min(1.6f, (e[b] / max(avg[b], 0.03f)).pow(3) * 0.6f)
            val ab = (e[b] / bmax[b]).pow(2.5f) * 1.3f
            val t = if (live) min(1.6f, rel * 0.5f + ab * 0.6f) * Eg else 0f
            lev[b] = if (t > lev[b]) t else lev[b] + (t - lev[b]) * min(1f, dt * fallR[b])
        }
        for (c in 0 until 11) {
            val tc = if (live) min(1.4f, chT[c] * 1.15f) * (0.7f + 0.3f * E) else 0f
            chLev[c] = if (tc > chLev[c]) tc else chLev[c] + (tc - chLev[c]) * min(1f, dt * 8 * relR / 10)
            val hst = chHist[c]; val fc = if (live) chF[c] else 0f; hst.addLast(fc); if (hst.size > 45) hst.removeFirst()
            val (mean, sd) = meanSd(hst)
            if (live && Eg > 0.05f && fc > mean + 1.3f * sd + 0.002f && now - chLast[c] > (if (c == 10 || c < 2) 130 else 90)) {
                val str = min(1f, 0.45f + (fc - mean) / (4 * sd + 1e-4f))
                chHit[c] = max(chHit[c], str * min(1f, 0.4f + Eg)); chLast[c] = now
            }
            chHit[c] *= exp(-dt * (if (c == 10 || c < 3) 9f else if (c < 6) 12f else 16f))
        }
        val gap = floatArrayOf(150f, 90f, 60f); val dec = floatArrayOf(9f, 11f, 18f)
        core *= exp(-dt * 2.2f)
        for (b in 0 until 3) {
            val hst = fluxH[b]; hst.addLast(flux[b]); if (hst.size > 45) hst.removeFirst()
            if (b == 0) { hit[0] *= exp(-dt * dec[0]); continue }       // the low band's beat is the kick drum (.1)
            val (mean, sd) = meanSd(hst)
            if (live && Eg > 0.05f && flux[b] > mean + 1.3f * sd + 0.0015f && now - lastHit[b] > gap[b]) {
                val str = min(1f, 0.45f + (flux[b] - mean) / (4 * sd + 1e-4f))
                hit[b] = max(hit[b], str * min(1f, 0.4f + Eg)); lastHit[b] = now
            }
            hit[b] *= exp(-dt * dec[b])
        }
        tempo = if (bpm > 0) min(1.7f, max(0.5f, bpm / 124)) else 1f
        // Light speed ship
        val hT = if (live) tempo * (0.9f + 1.5f * E + 0.4f * level) else 0f
        hsV += (hT - hsV) * min(1f, dt * (if (hT > hsV) 0.9f else 0.25f))
        hsB = max(hsB * exp(-dt * 2.5f), core * 0.7f * E * min(1f, hsV))
        hsX += (hsV + hsB) * dt
        hsSpin += dt * (if (live) 0.05f + 0.3f * lev[1] * E else 0f)
        val hE = if (live) E else 0f; hsE += (hE - hsE) * min(1f, dt * (if (hE > hsE) 1.0f else 0.25f))
        val mv = dt * tempo
        clock[0] += mv * (lev[0] * 1.1f + hit[0] * 0.5f)
        clock[1] += mv * (lev[1] * 1.0f)
        clock[2] += mv * (lev[2] * 1.3f + hit[2] * 0.4f)
        level = lev[0] * 0.5f + lev[1] * 0.3f + lev[2] * 0.2f
        val ag = if (live) min(1.3f, level * 0.6f + E * 0.25f + hit[0] * 0.35f + hit[2] * 0.15f) else 0f
        aggr += (ag - aggr) * min(1f, dt * (if (ag > aggr) 12 else 3))
        val redT = if (warm) min(1f, max(0f, (loud - pl97) / max(0.01f, plmax - pl97))) else 0f
        red += (redT - red) * min(1f, dt * (if (redT > red) 12 else 3))
        val asm = if (live) min(1f, max(0f, (loudS - ps50) / max(0.01f, psmax - ps50))).pow(2.2f) else 0f
        assemble += (asm - assemble) * min(1f, dt * 3)
        shake[0] *= exp(-dt * 14); shake[1] *= exp(-dt * 14)
        waveK += ((6 + 30 * min(1.5f, lev[2])) - waveK) * min(1f, dt * 5)
    }

    // ---------------- the Wave's strings ----------------
    private fun strings(dt: Float, now: Double) {
        val NS = strings
        if (NS != strN) { strN = NS; strL.fill(0f); strS.fill(0f); strSt.fill(0f); strP.fill(0f); strHit.fill(0f); strAge.fill(9f); strLast.fill(0.0); strHist.forEach { it.clear() }; strPrev.fill(0f); strPk.fill(-60f); strWK.fill(8f); strAct.fill(0f); strGlow.fill(0f); strCAvg.fill(0.5f); strRest.fill(0f) }
        for (b in 0 until NS) {
            // its part of 20 Hz-20 kHz: an equal share, overlapping its neighbours a little (7.5% each side)
            val i0 = max(0, floor(TONE_N * (b - 0.075) / NS).toInt()); val i1 = min(TONE_N, ceil(TONE_N * (b + 1.075) / NS).toInt())
            var fl = 0f; var dmax = -200f; var cw = 0f; var cs = 0f
            for (i in i0 until i1) { val v = toneVs[i]; fl += max(0f, v - strPrev[i]); if (toneDb[i] > dmax) dmax = toneDb[i]; cw += v * (i - i0); cs += v }
            val fr = if (NS > 1) b.toFloat() / (NS - 1) else 0.5f
            val g = 1 + 0.2f * min(1f, fr * 2) + 0.3f * max(0f, fr * 2 - 1)
            // 1. amplitude: how loud its part is now against its own loudest lately
            strPk[b] = max(dmax, max(strPk[b] - dt * 0.5f, -80f))
            val lvl = if (live && dmax > -150) max(0f, min(1.2f, (dmax - (strPk[b] - 30)) / 30)) else 0f
            // bounce: every rise above its part's resting level throws the string up, then it drops back fast
            strRest[b] += (lvl - strRest[b]) * min(1f, dt * (if (lvl < strRest[b]) 12f else 1.2f))
            val bounce = max(0f, lvl - strRest[b]) * 2.4f
            val lt = min(1.8f, (lvl * 0.45f + bounce) * g)
            strL[b] = if (lt > strL[b]) lt else strL[b] + (lt - strL[b]) * min(1f, dt * (9 + 6 * fr))
            // 2. wavelength: where the sound sits inside its part, compared with a moment ago
            val cen = if (cs > 0.05f) cw / cs / max(1, i1 - i0 - 1) else 0.5f
            strCAvg[b] += (cen - strCAvg[b]) * min(1f, dt / 2)
            val cRel = max(-0.85f, min(1f, (cen - strCAvg[b]) * (16 - 7 * fr)))
            val wkT = 7 * 4f.pow(fr) * (1 + 0.8f * cRel) * (0.8f + 0.4f * cen)
            strWK[b] += (wkT - strWK[b]) * min(1f, dt * 8)
            // 3. right-to-left movement: how busy its part is
            val f = if (live) fl / (i1 - i0) else 0f
            strAct[b] += (min(1.5f, f * g * 14) - strAct[b]) * min(1f, dt * 4)
            strP[b] += dt * (0.6f + 0.6f * fr) * (0.08f + strAct[b] * 2.2f + strL[b] * 0.25f) * 1.8f
            // beat in its part
            val hs = strHist[b]; hs.addLast(f); if (hs.size > 45) hs.removeFirst()
            val (mean, sd) = meanSd(hs)
            if (live && hs.size > 10 && f > mean + 1.4f * sd / g + 0.004f / g && now - strLast[b] > 110) {
                strHit[b] = max(strHit[b], min(1f, 0.55f + (f - mean) / (4 * sd + 1e-4f))); strAge[b] = 0f; strLast[b] = now
            }
            strHit[b] *= exp(-dt * (5 + 4 * fr)); strAge[b] += dt
            // 4. glow: its loudness and hits, brighter with the song's energy (the low string also on the kick)
            strGlow[b] = (0.3f + 0.8f * strL[b] + 0.6f * strHit[b] + (if (b == 0) 0.6f * core else 0f)) * (0.55f + 0.6f * E)
        }
        bgT += dt * (if (live) 0.06f + 0.45f * E * E else 0.03f)
        System.arraycopy(toneVs, 0, strPrev, 0, TONE_N)
    }
}
