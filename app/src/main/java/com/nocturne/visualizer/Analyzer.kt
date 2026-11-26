package com.nocturne.visualizer

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Everything the renderer needs for one frame. */
class Frame {
    val spec = FloatArray(Analyzer.SPEC_N)
    val ch = FloatArray(11)
    val chHit = FloatArray(11)
    var low = 0f; var mid = 0f; var high = 0f
    var lowHit = 0f; var midHit = 0f; var highHit = 0f
    var lowT = 0f; var midT = 0f; var highT = 0f
    var energy = 0f          // 0 quiet part .. 1 the biggest moments of the song
    var gate = 0f            // 0 in silence
    var intensity = 0f       // how hard everything hits: calm when quiet, very aggressive at full energy
    var level = 0f
    var bpm = 0f
    var beatPhase = 0f
    var beatIndex = 0L
    var travel = 0f; var speed = 0f; var spin = 0f
    var hyTravel = 0f; var hySpeed = 0f; var hyGlow = 0f
    var live = false
}

/**
 * Real-time music analysis. Sound samples are pushed in by the capture thread (or the demo player) and
 * analysed every 256 samples (about every 5 ms): spectrum, 10 equaliser bands + the .1 sub, beats per band,
 * tempo, and the song's energy. The renderer copies the latest results with [snapshot].
 */
class Analyzer {
    companion object {
        const val SPEC_N = 128
        private const val N = 1024
        private const val HOP = 256
        private const val RING = 4096
        // 10-band equaliser (octaves around 31 Hz .. 16 kHz) + the .1 subwoofer channel
        private val CH = arrayOf(
            floatArrayOf(22f, 44f), floatArrayOf(44f, 88f), floatArrayOf(88f, 177f), floatArrayOf(177f, 355f),
            floatArrayOf(355f, 710f), floatArrayOf(710f, 1420f), floatArrayOf(1420f, 2840f), floatArrayOf(2840f, 5680f),
            floatArrayOf(5680f, 11360f), floatArrayOf(11360f, 20000f), floatArrayOf(20f, 80f)
        )
        // naturally quieter high frequencies get a small lift so they register (like a pink-noise corrected analyser)
        private val TILT = floatArrayOf(0f, 0f, 0f, 0.02f, 0.05f, 0.08f, 0.12f, 0.16f, 0.22f, 0.28f, 0f)
        private val BANDS = arrayOf(floatArrayOf(25f, 150f), floatArrayOf(150f, 2500f), floatArrayOf(2500f, 16000f))
        private val BAND_TILT = floatArrayOf(0f, 0.05f, 0.2f)
    }

    private val lock = Any()
    private var sr = 48000f
    private val ring = FloatArray(RING)
    private var wpos = 0
    private var pending = 0
    private val fft = Fft(N)
    private val win = FloatArray(N) { i -> val x = i.toDouble() / N; (0.42 - 0.5 * cos(2 * PI * x) + 0.08 * cos(4 * PI * x)).toFloat() }
    private val re = FloatArray(N)
    private val im = FloatArray(N)
    private val b = FloatArray(N / 2)
    private val prevB = FloatArray(N / 2)
    private var time = 0.0            // seconds of sound analysed

    @Volatile var active = false
    var sensitivity = 1f

    // spectrum (for the equaliser-style shapes)
    private val spec = FloatArray(SPEC_N)
    private val binAvg = FloatArray(SPEC_N)
    private var gpk = 0.5f

    // channels and bands
    private val chE = FloatArray(11); private val chF = FloatArray(11)
    private val chLev = FloatArray(11); private val chHit = FloatArray(11)
    private val chLast = DoubleArray(11)
    private val chHist = Array(11) { FloatArray(140) }
    private var histPos = 0; private var histFill = 0
    private val bandE = FloatArray(3); private val bandF = FloatArray(3)
    private val bandLev = FloatArray(3); private val bandHit = FloatArray(3)
    private val bandLast = DoubleArray(3)
    private val bandHist = Array(3) { FloatArray(140) }

    // energy
    private var loudS = 0f
    private var rmsS = 0f
    private var playT = 0f
    private val eS = FloatArray(3)
    private class Range(var lo: Float, var hi: Float)
    private val rmsRange = Range(0.55f, 0.88f)
    private val bandRange = arrayOf(Range(0.6f, 0.92f), Range(0.48f, 0.7f), Range(0.12f, 0.5f))
    private var energy = 0f
    private var gate = 0f

    // tempo: low-frequency onsets on a 10 ms grid, 8 s long
    private val grid = FloatArray(800)
    private var gridPos = 0; private var gridFill = 0; private var gridAcc = 0.0
    private var lastTempoCalc = 0.0
    private var bpm = 0f; private var disagree = 0
    private var beatPh = 0.0; private var beatIdx = 0L
    private var lowM = 0f; private var lowV = 1f; private var lastKick = 0.0

    // motion clocks with inertia
    private val clock = FloatArray(3); private val clockV = FloatArray(3)
    private var shipV = 0f; private var shipX = 0f; private var boost = 0f
    private var spinV = 0f; private var spin = 0f; private var spinDir = 1f; private var eSlow = 0f
    private var hyV = 0f; private var hyX = 0f; private var hyB = 0f; private var hyE = 0f

    fun setSource(on: Boolean, sampleRate: Int) {
        synchronized(lock) {
            active = on
            sr = sampleRate.toFloat()
            pending = 0
            gridFill = 0; gridPos = 0; bpm = 0f; disagree = 0
            playT = 0f
        }
    }

    /** Light speed launches from rest every time it is opened. */
    fun resetLightSpeed() {
        synchronized(lock) { hyV = 0f; hyB = 0f; hyE = 0f }
    }

    fun process(src: FloatArray, n: Int) {
        synchronized(lock) {
            for (i in 0 until n) {
                ring[wpos] = src[i]
                wpos = (wpos + 1) and (RING - 1)
                if (++pending >= HOP) { pending = 0; hop() }
            }
        }
    }

    /** Called ~200 times a second while there is no sound source, so everything settles to black. */
    fun idle(dt: Float) {
        synchronized(lock) { if (!active) decay(dt) }
    }

    private fun decay(dt: Float) {
        for (i in 0 until SPEC_N) spec[i] *= exp(-dt * 4f)
        for (c in 0 until 11) { chLev[c] *= exp(-dt * 8f); chHit[c] *= exp(-dt * 12f) }
        for (k in 0 until 3) { bandLev[k] *= exp(-dt * 8f); bandHit[k] *= exp(-dt * 12f) }
        energy *= exp(-dt * 1.5f); gate *= exp(-dt * 3f)
        motion(dt, false)
    }

    private fun clamp(x: Float, a: Float, b: Float) = if (x < a) a else if (x > b) b else x

    private fun track(r: Range, v: Float, dt: Float) {
        if (loudS < 0.12f || playT < 1.5f) return          // learn the song's range only while music is really playing
        r.hi = if (v > r.hi) r.hi + (v - r.hi) * min(1f, dt * 1.5f) else r.hi - (r.hi - v) * dt / 90f
        r.lo = if (v < r.lo) r.lo + (v - r.lo) * min(1f, dt * 0.35f) else r.lo + (v - r.lo) * dt / 60f
        if (r.hi - r.lo < 0.12f) { val m = (r.hi + r.lo) / 2; r.hi = m + 0.06f; r.lo = m - 0.06f }
    }
    private fun norm(r: Range, v: Float) = clamp((v - r.lo) / (r.hi - r.lo), 0f, 1.15f)

    private fun hop() {
        val dt = HOP / sr
        time += dt
        // ---- spectrum of the newest 1024 samples
        for (i in 0 until N) { re[i] = ring[(wpos - N + i) and (RING - 1)] * win[i]; im[i] = 0f }
        fft.transform(re, im)
        val hz = sr / N
        for (k in 0 until N / 2) {
            val m = hypot(re[k], im[k]) / N
            val db = 20f * log10(m + 1e-12f)
            b[k] = clamp((db + 100f) / 82f, 0f, 1f)
        }
        var s2 = 0f
        for (i in 0 until HOP) { val x = ring[(wpos - HOP + i) and (RING - 1)]; s2 += x * x }
        val rmsN = clamp((10f * log10(s2 / HOP + 1e-9f) + 50f) / 50f, 0f, 1f)

        // ---- equaliser spectrum (log spaced)
        var gmax = 0f
        val tmp = FloatArray(SPEC_N)
        for (i in 0 until SPEC_N) {
            val f0 = 30f * (16000f / 30f).pow(i.toFloat() / SPEC_N)
            val f1 = 30f * (16000f / 30f).pow((i + 1).toFloat() / SPEC_N)
            val a = min(N / 2 - 1, floor(f0 / hz).toInt()); val e = min(N / 2 - 1, max(a, floor(f1 / hz).toInt()))
            var s = 0f; var mx = 0f
            for (j in a..e) { s += b[j]; if (b[j] > mx) mx = b[j] }
            tmp[i] = (s / (e - a + 1) * 0.5f + mx * 0.5f) * (0.8f + 1.1f * (i.toFloat() / SPEC_N).pow(1.3f))
            if (tmp[i] > gmax) gmax = tmp[i]
        }
        gpk = max(max(gpk * 0.93f.pow(dt), gmax), 0.25f)
        for (i in 0 until SPEC_N) {
            binAvg[i] += (tmp[i] - binAvg[i]) * min(1f, dt / 3f)
            val nov = clamp((tmp[i] - binAvg[i]) / (binAvg[i] * 0.6f + 0.03f), 0f, 1.2f)
            val v = min(1.25f, min(1.2f, tmp[i] / gpk * sensitivity).pow(1.8f) * 0.65f + nov * 0.55f * sensitivity)
            spec[i] = if (v > spec[i]) v else spec[i] + (v - spec[i]) * min(1f, dt * 16f)
        }

        // ---- channels and bands: level and attack (flux)
        for (c in 0 until 11) {
            val lo = max(1, floor(CH[c][0] / hz).toInt()); val hi = min(N / 2 - 1, max(lo, kotlin.math.ceil(CH[c][1] / hz).toInt()))
            var s = 0f; var f = 0f
            for (k in lo..hi) { s += b[k]; val d = b[k] - prevB[k]; if (d > 0) f += d }
            val n = hi - lo + 1
            chE[c] = min(1f, s / n * sensitivity); chF[c] = f / n * sensitivity
        }
        var onsetLow = 0f
        for (k in 1 until N / 2) { if (k * hz >= 150f) break; val d = b[k] - prevB[k]; if (d > 0) onsetLow += d }
        for (q in 0 until 3) {
            val lo = max(1, floor(BANDS[q][0] / hz).toInt()); val hi = min(N / 2 - 1, kotlin.math.ceil(BANDS[q][1] / hz).toInt())
            var s = 0f; var f = 0f
            for (k in lo..hi) { s += b[k]; val d = b[k] - prevB[k]; if (d > 0) f += d }
            val n = hi - lo + 1
            bandE[q] = min(1f, s / n * sensitivity); bandF[q] = f / n * sensitivity
        }
        System.arraycopy(b, 0, prevB, 0, N / 2)

        // ---- energy: how full and loud the music is, compared with this song's own quiet and loud parts
        val loud = bandE[0] * 0.5f + bandE[1] * 0.3f + bandE[2] * 0.2f
        loudS += (loud - loudS) * min(1f, dt * 2.5f)
        playT = if (active && loud > 0.12f) playT + dt else 0f
        rmsS += (rmsN - rmsS) * min(1f, dt * 8f)
        track(rmsRange, rmsS, dt)
        for (q in 0 until 3) { eS[q] += (bandE[q] - eS[q]) * min(1f, dt * 2f); track(bandRange[q], eS[q], dt) }
        val fullness = norm(bandRange[0], eS[0]) * 0.25f + norm(bandRange[1], eS[1]) * 0.35f + norm(bandRange[2], eS[2]) * 0.4f
        val target = if (active) min(1f, (fullness * 0.6f + norm(rmsRange, rmsS) * 0.4f) * 1.12f).pow(1.5f) else 0f
        energy += (target - energy) * min(1f, dt * (if (target > energy) 2.5f else 1.2f))
        gate = clamp((loudS - 0.02f) / 0.08f, 0f, 1f)

        // ---- levels: instant rise, gentle fall; quieter overall when the song is calm
        val calm = 0.35f + 0.65f * energy
        for (c in 0 until 11) {
            val t = if (active) clamp((chE[c] + TILT[c] - 0.06f) / 0.5f, 0f, 1.4f).pow(1.2f) * calm else 0f
            chLev[c] = if (t > chLev[c]) t else chLev[c] + (t - chLev[c]) * min(1f, dt * 9f)
        }
        for (q in 0 until 3) {
            val t = if (active) clamp((bandE[q] + BAND_TILT[q] - 0.06f) / 0.5f, 0f, 1.4f).pow(1.2f) * calm else 0f
            bandLev[q] = if (t > bandLev[q]) t else bandLev[q] + (t - bandLev[q]) * min(1f, dt * 9f)
        }

        // ---- beats: a sudden jump in a band above its recent average
        histPos = (histPos + 1) % 140; histFill = min(140, histFill + 1)
        for (c in 0 until 11) {
            if (detect(chHist[c], chF[c])) {
                val gap = if (c == 10 || c < 2) 0.13 else 0.09
                if (time - chLast[c] > gap) {
                    chHit[c] = max(chHit[c], strength * (0.3f + 0.7f * energy)); chLast[c] = time
                }
            }
            chHit[c] *= exp(-dt * (if (c == 10 || c < 3) 9f else if (c < 6) 12f else 16f))
        }
        val gaps = doubleArrayOf(0.15, 0.09, 0.06); val decays = floatArrayOf(9f, 11f, 18f)
        for (q in 0 until 3) {
            if (detect(bandHist[q], bandF[q]) && time - bandLast[q] > gaps[q]) {
                bandHit[q] = max(bandHit[q], strength * (0.3f + 0.7f * energy)); bandLast[q] = time
            }
            bandHit[q] *= exp(-dt * decays[q])
        }

        // ---- tempo
        tempo(onsetLow, dt)
        motion(dt, true)
    }

    private var strength = 0f
    private fun detect(hist: FloatArray, f: Float): Boolean {
        hist[histPos] = f
        if (!active || gate < 0.05f || histFill < 20) return false
        var m = 0f
        for (i in 0 until histFill) m += hist[i]
        m /= histFill
        var v = 0f
        for (i in 0 until histFill) { val d = hist[i] - m; v += d * d }
        val sd = sqrt(v / histFill)
        if (f > m + 1.3f * sd + 0.0015f) { strength = min(1f, 0.45f + (f - m) / (4f * sd + 1e-4f)); return true }
        return false
    }

    private fun tempo(onset: Float, dt: Float) {
        // onset grid at 100 Hz
        gridAcc += dt
        val cur = (gridPos + 799) % 800
        grid[cur] = max(grid[cur], onset * 100f)
        while (gridAcc >= 0.01) { gridAcc -= 0.01; grid[gridPos] = 0f; gridPos = (gridPos + 1) % 800; gridFill++ }
        // beat clock, nudged onto strong kicks (phase-locked)
        if (bpm > 0) { beatPh += dt * bpm / 60.0; while (beatPh >= 1) { beatPh -= 1; beatIdx++ } }
        val ol = onset * 100f
        lowM += (ol - lowM) * 0.01f; lowV += ((ol - lowM) * (ol - lowM) - lowV) * 0.01f
        if (bpm > 0 && ol > lowM + 2.2f * sqrt(lowV) && ol > 5f && time - lastKick > 30.0 / bpm) {
            lastKick = time
            val err = if (beatPh > 0.5) beatPh - 1 else beatPh
            if (abs(err) < 0.3) { beatPh -= err * 0.3; if (beatPh < 0) { beatPh += 1; beatIdx-- } }
        }
        if (gridFill < 400 || time - lastTempoCalc < 0.5 || !active) return
        lastTempoCalc = time
        val n = min(800, gridFill)
        val x = FloatArray(n)
        var m = 0f
        for (i in 0 until n) { x[i] = grid[(gridPos - n + i + 1600) % 800]; m += x[i] }
        m = m / n + 1e-9f
        for (i in 0 until n) x[i] = ln(1f + x[i] / m)
        val y = FloatArray(n)
        var run = 0f
        for (i in 0 until n) {
            run += x[i] - (if (i >= 31) x[i - 31] else 0f)
            val c = min(i + 1, 31)
            val j = max(0, i - 15)
            y[j] = max(0f, x[j] - run / c)
        }
        var ym = 0f
        for (v in y) ym += v
        ym /= n
        val r = FloatArray(203)
        for (lag in 27..202) {
            var s = 0f
            for (i in lag until n) s += (y[i] - ym) * (y[i - lag] - ym)
            r[lag] = s / (n - lag)
        }
        var bestLag = 0; var best = -1e9f
        for (lag in 30..100) {
            val p = ln(6000f / lag / 120f) / ln(2f) / 0.8f
            val sc = r[lag] * exp(-0.5f * p * p)
            if (sc > best) { best = sc; bestLag = lag }
        }
        if (bestLag == 0 || r[bestLag] <= 0) return
        fun pk(c: Float): Float { val i = c.roundToInt(); return max(r[i - 1], max(r[i], r[min(202, i + 1)])) }
        val cands = listOf(bestLag / 2f, bestLag.toFloat(), bestLag * 2f).filter { it in 30f..100f }.sorted()
        val top = cands.maxOf { pk(it) }
        var chosen = bestLag.toFloat()
        for (c in cands) if (pk(c) >= 0.5f * top) { chosen = c; break }
        val i0 = max(28, floor(chosen - 1.5f).toInt()); val i1 = min(201, kotlin.math.ceil(chosen + 1.5f).toInt())
        var bi = i0
        for (i in i0..i1) if (r[i] > r[bi]) bi = i
        val den = r[bi - 1] - 2 * r[bi] + r[bi + 1]
        val lag = bi + if (den < 0) clamp(0.5f * (r[bi - 1] - r[bi + 1]) / den, -0.5f, 0.5f) else 0f
        val nb = 6000f / lag
        if (bpm == 0f) bpm = nb
        else if (abs(nb - bpm) / bpm < 0.05f) { bpm += (nb - bpm) * 0.4f; disagree = 0 }
        else if (++disagree >= 3) { bpm = nb; disagree = 0 }
    }

    private fun motion(dt: Float, live: Boolean) {
        val tf = if (bpm > 0) clamp(bpm / 124f, 0.5f, 1.7f) else 1f
        val level = bandLev[0] * 0.5f + bandLev[1] * 0.3f + bandLev[2] * 0.2f
        // each band's clock runs as fast as that band is loud
        val tgt = if (live && active) floatArrayOf(tf * (bandLev[0] * 1.1f + bandHit[0] * 0.5f), tf * bandLev[1], tf * (bandLev[2] * 1.3f + bandHit[2] * 0.4f)) else floatArrayOf(0f, 0f, 0f)
        for (q in 0 until 3) {
            clockV[q] += (tgt[q] - clockV[q]) * min(1f, dt * (if (tgt[q] > clockV[q]) 4f else 0.8f))
            clock[q] += clockV[q] * dt
        }
        // travel with inertia like a ship: quick to speed up, coasts down; much faster when the song is at full energy
        val vT = if (live && active) tf * (0.1f + 2.2f * energy.pow(1.5f) + 0.3f * level) else 0f
        shipV += (vT - shipV) * min(1f, dt * (if (vT > shipV) 1.3f else 0.3f))
        boost = max(boost * exp(-dt * 3f), (bandHit[0] + chHit[10]) * 0.5f * energy * energy)
        shipX += (shipV + boost) * dt
        eSlow += (energy - eSlow) * min(1f, dt * 0.25f)
        if (energy > 0.72f && eSlow < 0.45f) { spinDir = -spinDir; eSlow = energy }      // each new drop turns the other way
        val sT = if (live && active) (0.08f + 0.6f * bandLev[1] * (0.3f + energy * 1.2f)) * spinDir else 0f
        spinV += (sT - spinV) * min(1f, dt * (if (abs(sT) > abs(spinV)) 1f else 0.35f))
        spin += spinV * dt
        // Light speed's own ship: launches gradually, cruises at the song's pace, eases off slowly
        val hT = if (live && active) tf * (0.6f + 2.4f * energy.pow(1.4f) + 0.3f * level) else 0f
        hyV += (hT - hyV) * min(1f, dt * (if (hT > hyV) 0.9f else 0.25f))
        val beat = beatPulse()
        hyB = max(hyB * exp(-dt * 2f), (beat * 0.6f + chHit[10] * 0.2f) * energy * min(1f, hyV))
        hyX += (hyV + hyB) * dt
        val hE = if (live && active) energy else 0f
        hyE += (hE - hyE) * min(1f, dt * (if (hE > hyE) 1f else 0.25f))
    }

    private fun beatPulse(): Float {
        if (bpm <= 0 || !active) return 0f
        val ph = beatPh.toFloat()
        val accent = if (beatIdx % 4 == 0L) 1f else 0.7f
        return min(1f, ph / 0.04f) * (1 - ph).pow(3) * accent * (0.3f + 0.7f * energy)
    }

    fun snapshot(f: Frame) {
        synchronized(lock) {
            System.arraycopy(spec, 0, f.spec, 0, SPEC_N)
            System.arraycopy(chLev, 0, f.ch, 0, 11)
            System.arraycopy(chHit, 0, f.chHit, 0, 11)
            f.low = bandLev[0]; f.mid = bandLev[1]; f.high = bandLev[2]
            f.lowHit = bandHit[0]; f.midHit = bandHit[1]; f.highHit = bandHit[2]
            f.lowT = clock[0]; f.midT = clock[1]; f.highT = clock[2]
            f.energy = energy
            f.gate = gate * (0.12f + 0.88f * energy)
            f.intensity = 0.2f + 2.3f * energy.pow(1.8f)          // calm when quiet, very aggressive at full energy
            f.level = bandLev[0] * 0.5f + bandLev[1] * 0.3f + bandLev[2] * 0.2f
            f.bpm = bpm
            f.beatPhase = beatPulse()
            f.beatIndex = beatIdx
            f.travel = shipX; f.speed = shipV + boost; f.spin = spin
            f.hyTravel = hyX; f.hySpeed = hyV + hyB; f.hyGlow = hyE
            f.live = active
        }
    }
}
