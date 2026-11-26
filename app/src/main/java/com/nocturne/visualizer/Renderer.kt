package com.nocturne.visualizer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

class Pic(val id: String, val name: String, val file: String, val styles: List<String>, val pal: Array<FloatArray>, val gen: Int)

class Style(val id: String, val name: String, val zoom: Float, val rot: Float, val shiftY: Float, val decay: Float,
            val keep: Boolean = false, val warp: Float = 0f, val hue: Float = 0f)

val STYLES = listOf(
    Style("bars", "Spectrum bars", 1.000f, 0f, 0.0015f, 0.80f),
    Style("radial", "Radial", 1.005f, 0.0008f, 0f, 0.86f),
    Style("wave", "Wave", 1f, 0f, 0f, 0f, keep = true),
    Style("tunnel", "Tunnel", 1.006f, 0.001f, 0f, 0.72f),
    Style("kaleido", "Kaleidoscope", 1.004f, -0.0012f, 0f, 0.80f),
    Style("shatter", "Shatter", 1.006f, 0f, 0f, 0.62f),
    Style("rise", "Rising sparks", 1.000f, 0f, 0.0015f, 0.86f),
    Style("warp", "Warp", 1.008f, 0.002f, 0f, 0.965f, keep = true, warp = 1f, hue = 0.002f),
    Style("fractal", "Fractal", 1.003f, 0.0008f, 0f, 0.75f, hue = 0.0012f),
    Style("hyper", "Light speed", 1.010f, 0f, 0f, 0.72f),
)

/**
 * Draws on the graphics chip at the screen's full refresh rate, the same three passes as the web page:
 * the style (scene), MilkDrop-style trails, then bloom and exposure to the screen.
 * Effects render at full resolution (up to 4K) and only step down if the chip can't keep up.
 */
class Renderer(private val ctx: Context, private val pics: List<Pic>, private val brain: Brain, private val onError: (String) -> Unit) : GLSurfaceView.Renderer {
    @Volatile var picture = 0
    @Volatile var style = 2

    private var PS = 0; private var PC = 0; private var PD = 0; private var PG = 0
    private val loc = HashMap<String, Int>()
    private var specTex = 0; private var peakTex = 0; private var waveTex = 0; private var toneTex = 0
    private val bSpec = ByteBuffer.allocateDirect(Brain.SPEC_N); private val bPeak = ByteBuffer.allocateDirect(Brain.SPEC_N)
    private val bWave = ByteBuffer.allocateDirect(Brain.WAVE_N); private val bTone = ByteBuffer.allocateDirect(Brain.TONE_N)
    private var floatOK = false
    private var W = 1; private var H = 1
    private var tw = 0; private var th = 0
    private var scene = IntArray(2); private val acc = arrayOf(IntArray(2), IntArray(2)); private var ai = 0
    private var gen = IntArray(2); private val GEN_W = 1920; private val GEN_H = 1080
    private var picTex = 0; private var picLoaded = -1; private var picW = 16f; private var picH = 9f
    private var key = ""
    private var last = 0L; private val t0 = System.nanoTime()
    private val scales = floatArrayOf(1f, 0.85f, 0.72f, 0.6f, 0.5f)
    private val dts = ArrayDeque<Float>(); private var step = 0; private var lastChange = 0.0; private var goodSince = 0.0
    private var ready = false

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            loc.clear()
            val ext = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: ""
            floatOK = ext.contains("GL_EXT_color_buffer_float") || ext.contains("GL_EXT_color_buffer_half_float")
            val vs = asset("shaders/vert.glsl"); val common = asset("shaders/common.glsl")
            PS = program(vs, common + asset("shaders/scene.glsl"))
            PC = program(vs, common + asset("shaders/compose.glsl"))
            PD = program(vs, common + asset("shaders/display.glsl"))
            PG = program(vs, common + asset("shaders/source.glsl"))
            GLES30.glUseProgram(PG); GLES30.glUniform1i(u(PG, "uSpec"), 1)
            GLES30.glUseProgram(PS); GLES30.glUniform1i(u(PS, "uTex"), 0); GLES30.glUniform1i(u(PS, "uSpec"), 1); GLES30.glUniform1i(u(PS, "uPeak"), 2); GLES30.glUniform1i(u(PS, "uWave"), 3); GLES30.glUniform1i(u(PS, "uTone"), 4)
            GLES30.glUseProgram(PC); GLES30.glUniform1i(u(PC, "uScene"), 0); GLES30.glUniform1i(u(PC, "uPrev"), 1)
            GLES30.glUseProgram(PD); GLES30.glUniform1i(u(PD, "uSrc"), 0)
            val vao = IntArray(1); GLES30.glGenVertexArrays(1, vao, 0); GLES30.glBindVertexArray(vao[0])
            val vb = IntArray(1); GLES30.glGenBuffers(1, vb, 0); GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vb[0])
            val tri = ByteBuffer.allocateDirect(6 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f)); tri.position(0)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 24, tri, GLES30.GL_STATIC_DRAW)
            GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            specTex = dataTex(Brain.SPEC_N); peakTex = dataTex(Brain.SPEC_N); waveTex = dataTex(Brain.WAVE_N); toneTex = dataTex(Brain.TONE_N)
            tw = 0; th = 0; gen = IntArray(2); picLoaded = -1; picTex = 0; key = ""
            ready = true
        } catch (e: Exception) { ready = false; onError(e.message ?: e.toString()) }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        var w = max(1, width); var h = max(1, height)
        val maxPx = 3840 * 2160
        if (w * h > maxPx) { val k = sqrt(maxPx.toFloat() / (w * h)); w = (w * k).toInt(); h = (h * k).toInt() }
        W = w; H = h; viewW = max(1, width); viewH = max(1, height)
    }

    private fun dataTex(n: Int): Int {
        val t = IntArray(1); GLES30.glGenTextures(1, t, 0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, n, 1, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, ByteBuffer.allocateDirect(n))
        params(GLES30.GL_LINEAR, GLES30.GL_CLAMP_TO_EDGE); return t[0]
    }
    private fun params(minF: Int, wrap: Int) {
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, minF)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, wrap)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, wrap)
    }
    private fun target(w: Int, h: Int, mips: Boolean, wrap: Int = GLES30.GL_CLAMP_TO_EDGE): IntArray {
        val t = IntArray(1); GLES30.glGenTextures(1, t, 0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        val levels = if (mips) 1 + (Math.log(max(w, h).toDouble()) / Math.log(2.0)).toInt() else 1
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, levels, if (floatOK) GLES30.GL_RGBA16F else GLES30.GL_RGBA8, w, h)
        params(if (mips) GLES30.GL_LINEAR_MIPMAP_LINEAR else GLES30.GL_LINEAR, wrap)
        val f = IntArray(1); GLES30.glGenFramebuffers(1, f, 0); GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, f[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, t[0], 0)
        GLES30.glClearColor(0f, 0f, 0f, 1f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return intArrayOf(t[0], f[0])
    }
    private fun free(x: IntArray) { if (x[0] != 0) { GLES30.glDeleteTextures(1, intArrayOf(x[0]), 0); GLES30.glDeleteFramebuffers(1, intArrayOf(x[1]), 0) } }
    private fun ensureTargets(w: Int, h: Int) {
        if (w == tw && h == th) return
        if (tw != 0) { free(scene); free(acc[0]); free(acc[1]) }
        scene = target(w, h, false); acc[0] = target(w, h, true); acc[1] = target(w, h, true); tw = w; th = h; ai = 0
    }
    private fun clearTrails() {
        for (a in acc) { GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, a[1]); GLES30.glClearColor(0f, 0f, 0f, 1f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT) }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun loadPicture(i: Int) {
        if (picTex != 0) { GLES30.glDeleteTextures(1, intArrayOf(picTex), 0); picTex = 0 }
        val p = pics[i]; picLoaded = i
        if (p.gen > 0) { if (gen[0] == 0) gen = target(GEN_W, GEN_H, true, GLES30.GL_MIRRORED_REPEAT); picW = GEN_W.toFloat(); picH = GEN_H.toFloat(); return }
        val maxT = IntArray(1); GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, maxT, 0)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.assets.open("pics/" + p.file).use { BitmapFactory.decodeStream(it, null, o) }
        var sample = 1
        while (max(o.outWidth, o.outHeight) / sample > min(4096, maxT[0])) sample *= 2     // full resolution, up to 4K
        val bmp: Bitmap = ctx.assets.open("pics/" + p.file).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })!!
        }
        picW = bmp.width.toFloat(); picH = bmp.height.toFloat()
        val t = IntArray(1); GLES30.glGenTextures(1, t, 0); picTex = t[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, picTex)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0); bmp.recycle()
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        params(GLES30.GL_LINEAR_MIPMAP_LINEAR, GLES30.GL_MIRRORED_REPEAT)
    }

    private fun upload(tex: Int, unit: Int, buf: ByteBuffer, data: ByteArray) {
        buf.position(0); buf.put(data); buf.position(0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, data.size, 1, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, buf)
    }

    // keep the frame rate at the screen's refresh rate: render the effects a little smaller only when needed
    private fun adapt(dt: Float, now: Double) {
        dts.addLast(dt); if (dts.size > 120) dts.removeFirst()
        if (dts.size < 60 || now - lastChange < 1500) return
        val sorted = dts.sorted(); val refresh = min(sorted[sorted.size / 10], 1f / 55)
        var recent = 0f; for (i in dts.size - 30 until dts.size) recent += dts[i]; recent /= 30
        if (recent > refresh * 1.3f && step < scales.size - 1) { step++; lastChange = now; goodSince = now }
        else if (recent < refresh * 1.08f) { if (now - goodSince > 4000 && step > 0) { step--; lastChange = now; goodSince = now } }
        else goodSince = now
    }

    override fun onDrawFrame(gl: GL10?) {
        if (!ready) return
        val nowN = System.nanoTime(); val now = nowN / 1e6
        val dt = if (last == 0L) 1f / 60 else min(0.1f, (nowN - last) / 1e9f); last = nowN
        brain.update(dt, now)
        adapt(dt, now)
        val pi = picture.coerceIn(0, pics.size - 1); val si = style.coerceIn(0, STYLES.size - 1)
        if (pi != picLoaded) loadPicture(pi)
        val pic = pics[pi]; val st = STYLES[si]
        val w = max(2, (W * scales[step]).roundToInt()); val h = max(2, (H * scales[step]).roundToInt())
        ensureTargets(w, h)
        val k = pic.id + "|" + st.id
        if (k != key) { if (st.id == "hyper") brain.resetShip(); key = k; clearTrails() }
        val b = brain; val L = b.lev; val Hh = b.hit; val C = b.clock

        upload(specTex, 1, bSpec, b.specBytes); upload(peakTex, 2, bPeak, b.peakBytes); upload(waveTex, 3, bWave, b.waveBytes); upload(toneTex, 4, bTone, b.toneBytes)
        if (pic.gen > 0) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, gen[1]); GLES30.glViewport(0, 0, GEN_W, GEN_H)
            GLES30.glUseProgram(PG)
            f2(PG, "uRes", GEN_W.toFloat(), GEN_H.toFloat()); f1(PG, "uTime", C[2]); f1(PG, "uPhase", C[1] * 3)
            f1(PG, "uBass", L[0]); f1(PG, "uHigh", L[2]); f1(PG, "uLevel", b.level); f1(PG, "uKick", Hh[0]); f1(PG, "uGen", pic.gen.toFloat())
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, gen[0]); GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (pic.gen > 0) gen[0] else picTex)

        // 1. scene
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, scene[1]); GLES30.glViewport(0, 0, w, h)
        GLES30.glUseProgram(PS)
        f3(PS, "uPal0", pic.pal[0]); f3(PS, "uPal1", pic.pal[1]); f3(PS, "uPal2", pic.pal[2]); f3(PS, "uPal3", pic.pal[3])
        f2(PS, "uRes", w.toFloat(), h.toFloat()); f2(PS, "uShake", b.shake[0], b.shake[1])
        f1(PS, "uTime", ((nowN - t0) / 1e9).toFloat())
        f1(PS, "uLow", L[0]); f1(PS, "uMid", L[1]); f1(PS, "uHigh", L[2])
        f1(PS, "uLowHit", Hh[0]); f1(PS, "uMidHit", Hh[1]); f1(PS, "uHighHit", Hh[2])
        f1(PS, "uLowT", C[0]); f1(PS, "uMidT", C[1]); f1(PS, "uHighT", C[2])
        f1(PS, "uCore", b.core); f1(PS, "uKAge", if (b.kickT > 0) min(9.0, (now - b.kickT) / 1000).toFloat() else 9f)
        f1(PS, "uTravel", b.hsX); f1(PS, "uSpeed", b.hsV + b.hsB); f1(PS, "uSpin", b.hsSpin); f1(PS, "uHyE", b.hsE)
        f1(PS, "uNStr", b.strings.toFloat()); fv(PS, "uStr", b.strL); fv(PS, "uFlow", b.strP); fv(PS, "uSHit", b.strHit); fv(PS, "uSAge", b.strAge)
        f1(PS, "uWaveK", b.waveK)
        f1(PS, "uLowA", min(1.8f, L[0] + Hh[0] * 0.45f + b.chLev[10] * 0.35f))
        f1(PS, "uEnergy", b.Eg); f1(PS, "uInt", b.int_); f1(PS, "uAggr", b.aggr)
        fv(PS, "uCh", b.chLev); fv(PS, "uChHit", b.chHit)
        f1(PS, "uMode", si.toFloat()); f1(PS, "uBars", 48f); f1(PS, "uLayout", 0f); f1(PS, "uBassMid", 0f); f1(PS, "uSeg", 0f); f1(PS, "uColor", 0f); f1(PS, "uPeakOn", 1f)
        f1(PS, "uImgAsp", picW / picH); f1(PS, "uAssemble", b.assemble)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)

        // 2. trails
        val src = acc[ai]; val dst = acc[1 - ai]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, dst[1])
        GLES30.glUseProgram(PC)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, scene[0])
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, src[0])
        val f60 = dt * 60 * b.tempo; val calmFade = 2.2f - 1.2f * b.E; val trip = 0.5f
        val decay = if (st.decay <= 0.02f) 0f else if (st.keep) (st.decay - 0.02f).pow(dt * 60 * calmFade) else min(0.93f, st.decay - 0.2f).pow(dt * 60 * calmFade)
        f1(PC, "uWarp", (st.warp * L[2] * 1.3f + trip * L[2]) * b.E)
        f1(PC, "uHue", (st.hue + trip * 0.0015f) * L[1] * 1.4f * b.E * f60)
        f1(PC, "uTime", C[2] * 1.5f)
        f2(PC, "uRes", w.toFloat(), h.toFloat())
        f2(PC, "uShift", 0f, st.shiftY * (L[0] * 0.7f + L[2] * 0.9f) * f60)
        f1(PC, "uZoom", 1 + ((st.zoom - 1) * L[0] * 1.5f * b.E + Hh[0] * 0.005f * b.E) * f60)
        f1(PC, "uRot", st.rot * L[1] * 1.6f * b.E * f60)
        f1(PC, "uDecay", decay)
        f1(PC, "uAberr", (Hh[0] * 0.004f + Hh[2] * 0.002f) * b.E)
        f1(PC, "uFlash", Hh[0] * Hh[0] * 0.05f * b.E)
        f3(PC, "uFlashCol", pic.pal[1])
        f1(PC, "uRed", b.red); f1(PC, "uAggr", b.aggr)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, dst[0]); GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)

        // 3. bloom to the screen
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0); GLES30.glViewport(0, 0, viewW, viewH)
        GLES30.glUseProgram(PD)
        f2(PD, "uRes", viewW.toFloat(), viewH.toFloat()); f1(PD, "uBloom", (0.15f + L[0] * 0.22f) * b.E)
        f1(PD, "uExpo", 0.1f + 0.9f * b.Eg.pow(1.3f))
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        ai = 1 - ai
    }

    // the real surface size (W/H may be capped at 4K for the effects)
    @Volatile var viewW = 1; @Volatile var viewH = 1
    fun surfaceSize(w: Int, h: Int) { viewW = w; viewH = h }

    private fun u(p: Int, n: String): Int = loc.getOrPut("$p/$n") { GLES30.glGetUniformLocation(p, n) }
    private fun f1(p: Int, n: String, v: Float) = GLES30.glUniform1f(u(p, n), v)
    private fun f2(p: Int, n: String, a: Float, b: Float) = GLES30.glUniform2f(u(p, n), a, b)
    private fun f3(p: Int, n: String, v: FloatArray) = GLES30.glUniform3f(u(p, n), v[0], v[1], v[2])
    private fun fv(p: Int, n: String, v: FloatArray) = GLES30.glUniform1fv(u(p, n), v.size, v, 0)
    private fun asset(path: String) = ctx.assets.open(path).bufferedReader().use { it.readText() }
    private fun shader(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type); GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
        val ok = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("Shader error: " + GLES30.glGetShaderInfoLog(s))
        return s
    }
    private fun program(vs: String, fs: String): Int {
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, vs)); GLES30.glAttachShader(p, shader(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glBindAttribLocation(p, 0, "p"); GLES30.glLinkProgram(p)
        val ok = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("Link error: " + GLES30.glGetProgramInfoLog(p))
        return p
    }
}
