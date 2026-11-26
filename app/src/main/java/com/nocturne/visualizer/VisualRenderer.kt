package com.nocturne.visualizer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import java.nio.ByteBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

class Picture(val name: String, val file: String, val pal: Array<FloatArray>)

/**
 * Draws the visuals with the phone's graphics chip. Every frame it takes the newest analysis from the
 * [Analyzer] (updated every ~5 ms by the audio thread), draws the chosen style, adds trails, glow and colour.
 */
class VisualRenderer(
    private val ctx: Context,
    private val pictures: List<Picture>,
    private val onError: (String) -> Unit
) : GLSurfaceView.Renderer {

    @Volatile var style = 0          // 0 Wave, 1 Tunnel, 2 Light speed
    @Volatile var picture = 0
    @Volatile var lines = 5

    private val frame = Frame()
    private var progScene = 0; private var progCompose = 0; private var progDisplay = 0
    private val uS = HashMap<String, Int>(); private val uC = HashMap<String, Int>(); private val uD = HashMap<String, Int>()
    private var picTex = 0; private var picLoaded = -1; private var picAsp = 1.6f
    private var specTex = 0
    private val specBytes = ByteBuffer.allocateDirect(Analyzer.SPEC_N)
    private var W = 1; private var H = 1; private var w = 1; private var h = 1
    private var sceneT = IntArray(2); private val acc = Array(2) { IntArray(2) }; private var cur = 0
    private var lastNanos = 0L
    private var shownStyle = -1
    private var ready = false
    private var shakeX = 0f; private var shakeY = 0f; private var lastBeat = 0f
    private var t0 = System.nanoTime()

    // trails per style: zoom, rotation, how long they last
    private val fbZoom = floatArrayOf(1f, 1.006f, 1.010f)
    private val fbRot = floatArrayOf(0f, 0.001f, 0f)
    private val fbDecay = floatArrayOf(0f, 0.72f, 0.72f)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            uS.clear(); uC.clear(); uD.clear()
            val vert = asset("shaders/vert.glsl")
            val common = asset("shaders/common.glsl")
            progScene = program(vert, common + asset("shaders/scene.glsl"))
            progCompose = program(vert, common + asset("shaders/compose.glsl"))
            progDisplay = program(vert, common + asset("shaders/display.glsl"))
            val vao = IntArray(1); GLES30.glGenVertexArrays(1, vao, 0); GLES30.glBindVertexArray(vao[0])
            val t = IntArray(1); GLES30.glGenTextures(1, t, 0); specTex = t[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, specTex)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, Analyzer.SPEC_N, 1, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, specBytes)
            params(GLES30.GL_LINEAR, GLES30.GL_LINEAR, GLES30.GL_CLAMP_TO_EDGE)
            picLoaded = -1; picTex = 0; sceneT = IntArray(2); acc[0] = IntArray(2); acc[1] = IntArray(2)
            uS.clear(); uC.clear(); uD.clear(); shownStyle = -1
            ready = true
        } catch (e: Exception) {
            ready = false
            onError(e.message ?: e.toString())
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        W = max(1, width); H = max(1, height)
        // draw the effects at up to ~2.1 million pixels, then scale to the screen (keeps 60-120 fps)
        val k = min(1f, sqrt(2_100_000f / (W * H)))
        w = max(2, (W * k).roundToInt()); h = max(2, (H * k).roundToInt())
        if (ready) makeTargets()
    }

    private fun makeTargets() {
        if (sceneT[0] != 0) { GLES30.glDeleteTextures(1, intArrayOf(sceneT[0]), 0); GLES30.glDeleteFramebuffers(1, intArrayOf(sceneT[1]), 0) }
        for (a in acc) if (a[0] != 0) { GLES30.glDeleteTextures(1, intArrayOf(a[0]), 0); GLES30.glDeleteFramebuffers(1, intArrayOf(a[1]), 0) }
        sceneT = target(false); acc[0] = target(true); acc[1] = target(true)
        for (a in acc) { GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, a[1]); GLES30.glClearColor(0f, 0f, 0f, 1f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT) }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun target(mips: Boolean): IntArray {
        val t = IntArray(1); GLES30.glGenTextures(1, t, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        val levels = if (mips) 1 + (Math.log(max(w, h).toDouble()) / Math.log(2.0)).toInt() else 1
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, levels, GLES30.GL_RGBA8, w, h)
        params(if (mips) GLES30.GL_LINEAR_MIPMAP_LINEAR else GLES30.GL_LINEAR, GLES30.GL_LINEAR, GLES30.GL_CLAMP_TO_EDGE)
        val f = IntArray(1); GLES30.glGenFramebuffers(1, f, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, f[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, t[0], 0)
        return intArrayOf(t[0], f[0])
    }

    private fun params(min: Int, mag: Int, wrap: Int) {
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, min)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, mag)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, wrap)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, wrap)
    }

    private fun loadPicture(i: Int) {
        if (picTex != 0) GLES30.glDeleteTextures(1, intArrayOf(picTex), 0)
        val p = pictures[i]
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.assets.open("pics/" + p.file).use { BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        while (max(opts.outWidth, opts.outHeight) / sample > 2048) sample *= 2
        val bmp: Bitmap = ctx.assets.open("pics/" + p.file).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })!!
        }
        picAsp = bmp.width.toFloat() / bmp.height
        val t = IntArray(1); GLES30.glGenTextures(1, t, 0); picTex = t[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, picTex)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        params(GLES30.GL_LINEAR_MIPMAP_LINEAR, GLES30.GL_LINEAR, GLES30.GL_MIRRORED_REPEAT)
        picLoaded = i
    }

    override fun onDrawFrame(gl: GL10?) {
        if (!ready) { GLES30.glClearColor(0f, 0f, 0f, 1f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT); return }
        if (sceneT[0] == 0) makeTargets()
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 1 / 60f else min(0.1f, (now - lastNanos) / 1e9f)
        lastNanos = now
        val an = Engine.analyzer
        an.idle(dt)
        val st = style
        if (st != shownStyle) {
            shownStyle = st
            if (st == 2) an.resetLightSpeed()
            for (a in acc) { GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, a[1]); GLES30.glClearColor(0f, 0f, 0f, 1f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT) }
        }
        if (picLoaded != picture) loadPicture(picture)
        an.snapshot(frame)
        val f = frame
        val pic = pictures[picture]

        // screen shake on strong kicks, only when the song is intense
        if (f.lowHit > lastBeat + 0.25f && f.energy > 0.55f && st != 0) {
            val ang = Math.random() * Math.PI * 2; val m = 0.008f * f.intensity * f.lowHit
            shakeX = (Math.cos(ang) * m).toFloat(); shakeY = (Math.sin(ang) * m).toFloat()
        }
        lastBeat = f.lowHit
        shakeX *= exp(-dt * 14f); shakeY *= exp(-dt * 14f)

        specBytes.position(0)
        for (i in 0 until Analyzer.SPEC_N) specBytes.put((min(1f, f.spec[i]) * 255f).toInt().toByte())
        specBytes.position(0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, specTex)
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, Analyzer.SPEC_N, 1, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, specBytes)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, picTex)

        // 1. the style
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sceneT[1]); GLES30.glViewport(0, 0, w, h)
        GLES30.glUseProgram(progScene)
        GLES30.glUniform1i(u(uS, progScene, "uTex"), 0); GLES30.glUniform1i(u(uS, progScene, "uSpec"), 1)
        for (k in 0 until 4) GLES30.glUniform3fv(u(uS, progScene, "uPal$k"), 1, pic.pal[k], 0)
        GLES30.glUniform2f(u(uS, progScene, "uRes"), w.toFloat(), h.toFloat())
        GLES30.glUniform2f(u(uS, progScene, "uShake"), shakeX, shakeY)
        GLES30.glUniform1f(u(uS, progScene, "uImgAsp"), picAsp)
        set1(uS, progScene, "uLow", f.low); set1(uS, progScene, "uMid", f.mid); set1(uS, progScene, "uHigh", f.high)
        set1(uS, progScene, "uLowHit", f.lowHit); set1(uS, progScene, "uMidHit", f.midHit); set1(uS, progScene, "uHighHit", f.highHit)
        set1(uS, progScene, "uLowT", f.lowT); set1(uS, progScene, "uMidT", f.midT); set1(uS, progScene, "uHighT", f.highT)
        GLES30.glUniform1fv(u(uS, progScene, "uCh"), 11, f.ch, 0)
        GLES30.glUniform1fv(u(uS, progScene, "uChHit"), 11, f.chHit, 0)
        set1(uS, progScene, "uEnergy", f.energy); set1(uS, progScene, "uGate", f.gate); set1(uS, progScene, "uInt", f.intensity)
        set1(uS, progScene, "uTime", (now - t0) / 1e9f); set1(uS, progScene, "uBeat", f.beatPhase)
        set1(uS, progScene, "uTravel", f.travel); set1(uS, progScene, "uSpeed", f.speed); set1(uS, progScene, "uSpin", f.spin)
        set1(uS, progScene, "uHyT", f.hyTravel); set1(uS, progScene, "uHyS", f.hySpeed); set1(uS, progScene, "uHyE", f.hyGlow)
        set1(uS, progScene, "uLines", lines.toFloat())
        GLES30.glUniform1i(u(uS, progScene, "uMode"), st)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)

        // 2. trails (move only with the music), colour that deepens with intensity, flashes that only brighten
        val src = acc[cur]; val dst = acc[1 - cur]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, dst[1])
        GLES30.glUseProgram(progCompose)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sceneT[0])
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, src[0])
        GLES30.glUniform1i(u(uC, progCompose, "uScene"), 0); GLES30.glUniform1i(u(uC, progCompose, "uPrev"), 1)
        val f60 = dt * 60f
        val calmFade = 2.2f - 1.2f * f.energy
        val decay = if (fbDecay[st] <= 0f) 0f else fbDecay[st].pow(f60 * calmFade)
        GLES30.glUniform2f(u(uC, progCompose, "uRes"), w.toFloat(), h.toFloat())
        GLES30.glUniform2f(u(uC, progCompose, "uShift"), 0f, 0f)
        set1(uC, progCompose, "uZoom", 1f + ((fbZoom[st] - 1f) * f.low * 1.5f * f.energy + f.lowHit * 0.005f * f.energy) * f60)
        set1(uC, progCompose, "uRot", fbRot[st] * f.mid * 1.6f * f.energy * f60)
        set1(uC, progCompose, "uDecay", decay)
        set1(uC, progCompose, "uAberr", (f.lowHit * 0.004f + f.highHit * 0.002f) * f.energy * f.energy)
        set1(uC, progCompose, "uFlash", if (f.energy > 0.5f) f.lowHit * f.lowHit * 0.06f * f.energy else 0f)
        set1(uC, progCompose, "uAggr", f.energy.pow(1.5f))
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, dst[0])
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)

        // 3. glow and overall light (follows the song's energy) to the screen
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0); GLES30.glViewport(0, 0, W, H)
        GLES30.glUseProgram(progDisplay)
        GLES30.glUniform1i(u(uD, progDisplay, "uSrc"), 0)
        GLES30.glUniform2f(u(uD, progDisplay, "uRes"), W.toFloat(), H.toFloat())
        set1(uD, progDisplay, "uBloom", (0.12f + f.low * 0.25f) * (0.3f + f.energy))
        val glow = if (st == 2) max(f.hyGlow, f.gate * 0.5f) else f.gate
        set1(uD, progDisplay, "uExpo", 0.15f + 0.85f * glow.pow(1.1f))
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        cur = 1 - cur
    }

    private fun set1(m: HashMap<String, Int>, p: Int, n: String, v: Float) = GLES30.glUniform1f(u(m, p, n), v)
    private fun u(m: HashMap<String, Int>, p: Int, n: String): Int = m.getOrPut(n) { GLES30.glGetUniformLocation(p, n) }

    private fun asset(path: String) = ctx.assets.open(path).bufferedReader().use { it.readText() }

    private fun shader(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
        val ok = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("Shader error: " + GLES30.glGetShaderInfoLog(s))
        return s
    }

    private fun program(vs: String, fs: String): Int {
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, shader(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(p)
        val ok = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("Link error: " + GLES30.glGetProgramInfoLog(p))
        return p
    }
}
