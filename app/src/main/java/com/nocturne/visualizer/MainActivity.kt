package com.nocturne.visualizer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray

/**
 * Nocturne: native visualizer. Sound goes straight from Android's audio system into the analyser and the
 * visuals are drawn with OpenGL at the screen's full refresh rate; there is no browser in between.
 */
class MainActivity : Activity() {

    private lateinit var gl: GLSurfaceView
    private lateinit var renderer: Renderer
    private lateinit var brain: Brain
    private lateinit var pics: List<Pic>
    private lateinit var demo: DemoPlayer
    private lateinit var bar: FrameLayout
    private lateinit var panel: LinearLayout
    private lateinit var maxBtn: View
    private lateinit var hud: TextView
    private lateinit var picName: TextView
    private lateinit var status: TextView
    private lateinit var meter: ProgressBar
    private lateinit var seekRow: LinearLayout
    private lateinit var seek: SeekBar
    private lateinit var timeText: TextView
    private lateinit var playBtn: TextView
    private lateinit var strRow: LinearLayout
    private val styleButtons = ArrayList<TextView>()
    private val strButtons = HashMap<Int, TextView>()
    private val sourceButtons = HashMap<String, TextView>()
    private val ui = Handler(Looper.getMainLooper())
    private var source = ""                 // "device", "mic", "demo" or ""
    private var pendingMode: String? = null
    private var lastStatus = ""
    private var seeking = false
    private var minimised = false
    private var picIndex = 0
    private val accent = Color.rgb(255, 106, 43)
    private val fg = Color.rgb(233, 237, 242)
    private val muted = Color.rgb(138, 147, 160)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        useHighestRefreshRate()
        pics = loadPictures()
        demo = DemoPlayer(this)
        brain = Brain(this)
        val prefs = getPreferences(MODE_PRIVATE)
        brain.strings = prefs.getInt("strings", 3)
        minimised = prefs.getBoolean("min", false)

        renderer = Renderer(this, pics, brain) { msg -> runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() } }
        gl = GLSurfaceView(this)
        gl.setEGLContextClientVersion(3)
        gl.preserveEGLContextOnPause = true
        gl.setRenderer(renderer)
        gl.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        gl.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_DOWN) showBar(); false }
        gl.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {
                // ask the display for its fastest refresh rate for this surface (90 / 120 Hz where the screen has it)
                if (Build.VERSION.SDK_INT >= 30) try { h.surface.setFrameRate(maxRefresh(), android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) } catch (_: Exception) {}
            }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) {}
        })
        setPicture(prefs.getInt("picture", 0))

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        root.addView(gl, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        hud = TextView(this).apply { setTextColor(Color.argb(170, 233, 237, 242)); textSize = 13f; setPadding(dp(18), dp(16), dp(18), dp(8)) }
        root.addView(hud, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        bar = buildBar()
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = dp(12); leftMargin = dp(10); rightMargin = dp(10) })
        setContentView(root)
        hideSystemBars()
        applyMin()
        refreshButtons()
        ui.post(tick)
        ui.postDelayed({ if (source.isEmpty()) startPhone() }, 600)
    }

    // ---------------- display ----------------

    private fun maxRefresh(): Float {
        val d = if (Build.VERSION.SDK_INT >= 30) display else @Suppress("DEPRECATION") windowManager.defaultDisplay
        return d?.supportedModes?.maxOfOrNull { it.refreshRate } ?: 60f
    }

    private fun useHighestRefreshRate() {
        try {
            val d = if (Build.VERSION.SDK_INT >= 30) display else @Suppress("DEPRECATION") windowManager.defaultDisplay
            val cur = d?.mode ?: return
            val best = d.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }.maxByOrNull { it.refreshRate } ?: return
            val lp = window.attributes; lp.preferredDisplayModeId = best.modeId; window.attributes = lp
        } catch (_: Exception) {}
    }

    // ---------------- controls ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun pill(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(fg)
        setPadding(dp(13), dp(7), dp(13), dp(7))
        background = GradientDrawable().apply { cornerRadius = dp(999).toFloat(); setStroke(dp(1), Color.argb(40, 233, 237, 242)) }
        setOnClickListener { onClick(); showBar() }
    }

    private fun setOn(v: TextView, on: Boolean) {
        (v.background as GradientDrawable).apply {
            setStroke(dp(1), if (on) accent else Color.argb(40, 233, 237, 242))
            setColor(if (on) Color.argb(40, 255, 106, 43) else Color.TRANSPARENT)
        }
    }

    private fun lp(right: Int = 6) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(right) }

    private fun row(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        for (v in views) addView(v, lp())
    }

    private fun label(t: String) = TextView(this).apply {
        text = t.uppercase(); textSize = 10f; letterSpacing = 0.16f
        setTextColor(muted); setPadding(dp(4), 0, dp(4), 0)
    }

    private fun scroll(v: View) = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(v) }

    /** A chevron: just the two legs, pointing down (minimise) or up (bring the controls back). */
    private inner class Chevron(val up: Boolean) : View(this@MainActivity) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat(); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = if (up) fg else muted }
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val s = dp(6).toFloat(); val cx = w / 2; val cy = h / 2
            val path = Path()
            if (up) { path.moveTo(cx - s, cy + s / 2); path.lineTo(cx, cy - s / 2); path.lineTo(cx + s, cy + s / 2) }
            else { path.moveTo(cx - s, cy - s / 2); path.lineTo(cx, cy + s / 2); path.lineTo(cx + s, cy - s / 2) }
            c.drawPath(path, p)
        }
    }

    private fun buildBar(): FrameLayout {
        picName = TextView(this).apply { setTextColor(Color.WHITE); textSize = 13f; setPadding(dp(8), 0, dp(8), 0) }
        val prev = pill("‹") { setPicture(picIndex - 1) }
        val next = pill("›") { setPicture(picIndex + 1) }
        styleButtons.clear()
        val styleRow = row(label("Style"))
        STYLES.forEachIndexed { i, s -> val b = pill(s.name) { setStyle(i) }; styleButtons.add(b); styleRow.addView(b, lp()) }
        val r1 = row(prev, picName, next)
        strRow = row(label("Strings"))
        for (n in intArrayOf(1, 3, 5, 7)) { val b = pill("$n") { setStrings(n) }; strButtons[n] = b; strRow.addView(b, lp()) }

        sourceButtons["device"] = pill("This phone") { toggle("device") }
        sourceButtons["mic"] = pill("Microphone") { toggle("mic") }
        sourceButtons["demo"] = pill("Demo track") { toggle("demo") }
        status = TextView(this).apply { setTextColor(muted); textSize = 11f; typeface = Typeface.MONOSPACE; setPadding(dp(8), 0, dp(6), 0) }
        meter = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progressTintList = ColorStateList.valueOf(accent) }
        val r3 = row(label("Sound"), sourceButtons["device"]!!, sourceButtons["mic"]!!, sourceButtons["demo"]!!, label("Energy"))
        r3.addView(meter, LinearLayout.LayoutParams(dp(70), dp(6)))
        r3.addView(status)

        seek = SeekBar(this).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(accent); thumbTintList = ColorStateList.valueOf(accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) timeText.text = fmt(p / 1000.0 * demo.durationUs / 1e6) + " / " + fmt(demo.durationUs / 1e6)
                }
                override fun onStartTrackingTouch(s: SeekBar) { seeking = true }
                override fun onStopTrackingTouch(s: SeekBar) { demo.seek((s.progress / 1000.0 * demo.durationUs).toLong()); seeking = false; showBar() }
            })
        }
        timeText = TextView(this).apply { setTextColor(fg); textSize = 11f; typeface = Typeface.MONOSPACE }
        playBtn = pill("❚❚") { demo.paused = !demo.paused; playBtn.text = if (demo.paused) "▶" else "❚❚" }
        val back = pill("−10") { demo.seek(demo.positionUs - 10_000_000L) }
        val fwd = pill("+10") { demo.seek(demo.positionUs + 10_000_000L) }
        seekRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
        seekRow.addView(playBtn, lp()); seekRow.addView(back, lp())
        seekRow.addView(seek, LinearLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.WRAP_CONTENT))
        seekRow.addView(timeText, lp()); seekRow.addView(fwd, lp())

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(10), dp(40), dp(18))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.argb(190, 10, 12, 16)); setStroke(dp(1), Color.argb(33, 233, 237, 242)) }
            addView(scroll(r1))
            addView(scroll(styleRow), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            addView(scroll(strRow), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            addView(scroll(r3), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            addView(scroll(seekRow), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        val frame = FrameLayout(this)
        val panelWrap = FrameLayout(this)
        panelWrap.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        // inside the panel: minimise chevron (top-right) and the credit (bottom-right, faint)
        val minBtn = Chevron(false).apply { setOnClickListener { minimised = true; applyMin() } }
        val credit = TextView(this).apply { text = "by Hassan Al-Yunis"; textSize = 9f; letterSpacing = 0.04f; setTextColor(Color.argb(90, 233, 237, 242)) }
        val panelBox = FrameLayout(this)
        panelBox.addView(panelWrap)
        panel.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            (minBtn.layoutParams as FrameLayout.LayoutParams).apply { leftMargin = r - dp(36); topMargin = t + dp(4) }; minBtn.requestLayout()
            (credit.layoutParams as FrameLayout.LayoutParams).apply { leftMargin = r - dp(12) - credit.width; topMargin = b - dp(16) }; credit.requestLayout()
        }
        panelBox.addView(minBtn, FrameLayout.LayoutParams(dp(32), dp(28)))
        panelBox.addView(credit, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        frame.addView(panelBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // when minimised: a chevron pointing up stays at the right
        maxBtn = Chevron(true).apply {
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.argb(190, 10, 12, 16)); setStroke(dp(1), Color.argb(33, 233, 237, 242)) }
            setOnClickListener { minimised = false; applyMin() }
        }
        frame.addView(maxBtn, FrameLayout.LayoutParams(dp(44), dp(34), Gravity.END or Gravity.BOTTOM))
        panelBox.tag = "panelBox"
        return frame
    }

    private fun applyMin() {
        bar.findViewWithTag<View>("panelBox").visibility = if (minimised) View.GONE else View.VISIBLE
        maxBtn.visibility = if (minimised) View.VISIBLE else View.GONE
        getPreferences(MODE_PRIVATE).edit().putBoolean("min", minimised).apply()
        showBar()
    }

    private fun fmt(sec: Double): String { val s = sec.toInt().coerceAtLeast(0); return "${s / 60}:${(s % 60).toString().padStart(2, '0')}" }

    private fun styleOf(i: Int): Int {
        val p = pics[i]
        val saved = getPreferences(MODE_PRIVATE).getString("style_" + p.id, null) ?: p.styles.firstOrNull()
        return STYLES.indexOfFirst { it.id == saved }.let { if (it < 0) 2 else it }
    }

    private fun setPicture(i: Int) {
        val n = pics.size
        picIndex = ((i % n) + n) % n
        renderer.picture = picIndex
        renderer.style = styleOf(picIndex)
        getPreferences(MODE_PRIVATE).edit().putInt("picture", picIndex).apply()
        if (::picName.isInitialized) refreshButtons()
    }

    private fun setStyle(i: Int) {
        renderer.style = i
        getPreferences(MODE_PRIVATE).edit().putString("style_" + pics[picIndex].id, STYLES[i].id).apply()
        refreshButtons()
    }

    private fun setStrings(n: Int) {
        brain.strings = n
        getPreferences(MODE_PRIVATE).edit().putInt("strings", n).apply()
        refreshButtons()
    }

    private fun refreshButtons() {
        picName.text = pics[picIndex].name
        styleButtons.forEachIndexed { i, b -> setOn(b, i == renderer.style) }
        strRow.visibility = if (STYLES[renderer.style].id == "wave") View.VISIBLE else View.GONE
        (strRow.parent as? View)?.visibility = strRow.visibility
        for ((k, b) in strButtons) setOn(b, k == brain.strings)
        for ((k, b) in sourceButtons) setOn(b, k == source)
        seekRow.visibility = if (source == "demo") View.VISIBLE else View.GONE
        (seekRow.parent as? View)?.visibility = seekRow.visibility
        playBtn.text = if (demo.paused) "▶" else "❚❚"
        hud.text = "${pics[picIndex].name}  ·  ${STYLES[renderer.style].name}"
    }

    // controls fade away while music plays; tap the screen to bring them back
    private val hideBar = Runnable { if (source.isNotEmpty() && !seeking) { bar.animate().alpha(0f).setDuration(500).withEndAction { bar.visibility = View.INVISIBLE }.start(); hud.animate().alpha(0f).setDuration(500).start() } }
    private fun showBar() {
        if (!::bar.isInitialized) return
        bar.animate().cancel(); bar.visibility = View.VISIBLE; bar.alpha = 1f; hud.alpha = 1f
        ui.removeCallbacks(hideBar); ui.postDelayed(hideBar, 3500)
    }

    private val tick = object : Runnable {
        override fun run() {
            meter.progress = (brain.E * 100).toInt()
            status.text = (if (source == "demo") "demo" else Engine.via) + (if (brain.bpm > 0 && brain.live) "  ${brain.bpm.toInt()} BPM" else "")
            if (source == "demo" && !seeking && demo.durationUs > 0) {
                seek.progress = (demo.positionUs * 1000 / demo.durationUs).toInt()
                timeText.text = fmt(demo.positionUs / 1e6) + " / " + fmt(demo.durationUs / 1e6)
            }
            val st = Engine.status
            if (st != lastStatus) {
                lastStatus = st
                val msg = when (st) {
                    "mic-fallback" -> "Nothing is playing on this phone, so I'm listening through the microphone"
                    "capture-denied" -> "Phone sound needs the screen-share permission (tap Start now). Using the microphone for now"
                    "capture-failed" -> "This phone wouldn't share its sound, so I'm using the microphone"
                    "no-permission" -> "Nocturne needs the microphone permission to hear sound"
                    "demo-error" -> "The demo track couldn't play"
                    else -> null
                }
                if (msg != null) Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
            }
            ui.postDelayed(this, 200)
        }
    }

    // ---------------- sound sources ----------------

    private fun toggle(mode: String) {
        if (source == mode) { stopAll(); source = ""; refreshButtons(); return }
        when (mode) {
            "device" -> startPhone()
            "mic" -> startCapture("mic")
            "demo" -> startDemo()
        }
    }

    private fun stopAll() {
        stopService(Intent(this, CaptureService::class.java))
        if (demo.isPlaying) demo.stop()
    }

    private fun startPhone() = startCapture("device")

    private fun startDemo() {
        Engine.status = "demo"
        stopService(Intent(this, CaptureService::class.java))
        demo.start(); demo.paused = false
        source = "demo"; refreshButtons(); showBar()
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun neededPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        else arrayOf(Manifest.permission.RECORD_AUDIO)

    private fun startCapture(mode: String) {
        if (!hasMic()) { pendingMode = mode; requestPermissions(neededPermissions(), REQ_AUDIO); return }
        if (demo.isPlaying) demo.stop()
        if (mode == "device" && Build.VERSION.SDK_INT >= 29) {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            // Android 14+: ask for the whole screen. Sharing "one app" would only capture Nocturne's own (silent) sound.
            val consent = if (Build.VERSION.SDK_INT >= 34)
                mpm.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
            else mpm.createScreenCaptureIntent()
            startActivityForResult(consent, REQ_PROJECTION)
        } else startService(mode, 0, null)
    }

    private fun startService(mode: String, code: Int, data: Intent?) {
        val i = Intent(this, CaptureService::class.java).putExtra("mode", mode).putExtra("code", code)
        if (data != null) i.putExtra("data", data)
        Engine.status = "starting"
        startForegroundService(i)
        source = mode; refreshButtons(); showBar()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) startService("device", resultCode, data)
            else { Toast.makeText(this, "Phone sound needs you to tap Start now. Using the microphone for now", Toast.LENGTH_LONG).show(); startService("mic", 0, null) }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_AUDIO) return
        val mode = pendingMode; pendingMode = null
        if (mode != null) {
            if (hasMic()) startCapture(mode)
            else Toast.makeText(this, "Without the microphone permission Nocturne can only play the demo track", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------- pictures & window ----------------

    private fun loadPictures(): List<Pic> {
        val arr = JSONArray(assets.open("pictures.json").bufferedReader().use { it.readText() })
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val p = o.getJSONArray("pal"); val st = o.getJSONArray("styles")
            Pic(o.getString("id"), o.getString("name"), o.getString("file"), (0 until st.length()).map { st.getString(it) },
                Array(4) { k -> val c = p.getJSONArray(k); floatArrayOf(c.getDouble(0).toFloat(), c.getDouble(1).toFloat(), c.getDouble(2).toFloat()) }, o.optInt("gen", 0))
        }
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else hideSystemBarsLegacy()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBarsLegacy() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onResume() { super.onResume(); gl.onResume() }
    override fun onPause() { gl.onPause(); super.onPause() }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        stopAll()
        super.onDestroy()
    }

    companion object {
        private const val REQ_PROJECTION = 1
        private const val REQ_AUDIO = 3
    }
}
