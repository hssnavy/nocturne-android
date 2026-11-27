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
import kotlin.math.max
import kotlin.math.min

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
    private val styleButtons = ArrayList<TextView>()
    private val sourceButtons = HashMap<String, TextView>()
    private val ui = Handler(Looper.getMainLooper())
    private var source = ""                 // "device", "mic", "demo" or ""
    private var pendingMode: String? = null
    private var lastStatus = ""
    private var seeking = false
    private var minimised = false
    private var picIndex = 0
    private var screenShare = false
    private lateinit var shareBtn: TextView
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
        Settings.load(this)
        demo = DemoPlayer(this)
        brain = Brain(this)
        brain.log = LearnLog(this).also { it.startUploader() }
        val prefs = getPreferences(MODE_PRIVATE)
        minimised = prefs.getBoolean("min", false)
        screenShare = prefs.getBoolean("share", false)

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
        // the credit, faint and small, on the main screen (bottom-left)
        root.addView(TextView(this).apply { text = "by Hassan Al-Yunis"; textSize = 10f; letterSpacing = 0.05f; setTextColor(Color.argb(80, 233, 237, 242)); setPadding(dp(18), dp(8), dp(18), dp(12)) },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
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
        picName = pill("") { showPicker() }.apply { setTextColor(Color.WHITE) }
        val prev = pill("‹") { setPicture(picIndex - 1) }
        val next = pill("›") { setPicture(picIndex + 1) }
        styleButtons.clear()
        val styleRow = row(label("Style"))
        STYLES.forEachIndexed { i, s -> val b = pill(s.name) { setStyle(i) }; styleButtons.add(b); styleRow.addView(b, lp()) }
        val r1 = row(prev, picName, next, pill("Settings") { showSettings() })

        sourceButtons["device"] = pill("This phone") { toggle("device") }
        sourceButtons["mic"] = pill("Microphone") { toggle("mic") }
        sourceButtons["demo"] = pill("Demo track") { toggle("demo") }
        // optional: share the screen for the most detailed sound from apps that allow it (YouTube, games...).
        // Off by default: the phone's sound (Spotify included) is heard through its output without sharing anything.
        shareBtn = pill("Screen share: off") {
            screenShare = !screenShare
            getPreferences(MODE_PRIVATE).edit().putBoolean("share", screenShare).apply()
            refreshButtons()
            if (source == "device") { stopService(Intent(this, CaptureService::class.java)); startPhone() }
        }
        status = TextView(this).apply { setTextColor(muted); textSize = 11f; typeface = Typeface.MONOSPACE; setPadding(dp(8), 0, dp(6), 0) }
        meter = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progressTintList = ColorStateList.valueOf(accent) }
        val r3 = row(label("Sound"), sourceButtons["device"]!!, sourceButtons["mic"]!!, sourceButtons["demo"]!!, shareBtn, label("Energy"))
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
            setPadding(dp(12), dp(10), dp(40), dp(10))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.argb(190, 10, 12, 16)); setStroke(dp(1), Color.argb(33, 233, 237, 242)) }
            addView(scroll(r1))
            addView(scroll(styleRow), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            addView(scroll(r3), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            addView(scroll(seekRow), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        val frame = FrameLayout(this)
        val panelWrap = FrameLayout(this)
        panelWrap.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        // inside the panel: minimise chevron (top-right)
        val minBtn = Chevron(false).apply { setOnClickListener { minimised = true; applyMin() } }

        val panelBox = FrameLayout(this)
        panelBox.addView(panelWrap)
        panel.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            (minBtn.layoutParams as FrameLayout.LayoutParams).apply { leftMargin = r - dp(36); topMargin = t + dp(4) }; minBtn.requestLayout()
        }
        panelBox.addView(minBtn, FrameLayout.LayoutParams(dp(32), dp(28)))
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

    private var firstMin = true
    private fun applyMin() {
        val box = bar.findViewWithTag<View>("panelBox")
        getPreferences(MODE_PRIVATE).edit().putBoolean("min", minimised).apply()
        if (firstMin) {           // on start: no animation, just the saved state
            firstMin = false
            box.visibility = if (minimised) View.GONE else View.VISIBLE
            maxBtn.visibility = if (minimised) View.VISIBLE else View.GONE
            showBar(); return
        }
        box.animate().cancel(); maxBtn.animate().cancel()
        if (minimised) {
            // the panel glides down and fades into the corner; the chevron fades in where it went
            box.pivotX = box.width.toFloat(); box.pivotY = box.height.toFloat()
            box.animate().translationY(box.height * 0.6f).translationX(box.width * 0.25f).scaleX(0.6f).scaleY(0.6f).alpha(0f)
                .setDuration(320).setInterpolator(android.view.animation.DecelerateInterpolator())
                .withEndAction { box.visibility = View.GONE }.start()
            maxBtn.alpha = 0f; maxBtn.scaleX = 0.6f; maxBtn.scaleY = 0.6f; maxBtn.visibility = View.VISIBLE
            maxBtn.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(180).setDuration(240).start()
        } else {
            maxBtn.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setStartDelay(0).setDuration(160).withEndAction { maxBtn.visibility = View.GONE }.start()
            box.visibility = View.VISIBLE
            box.pivotX = box.width.toFloat(); box.pivotY = box.height.toFloat()
            box.alpha = 0f; box.translationY = box.height * 0.6f; box.translationX = box.width * 0.25f; box.scaleX = 0.6f; box.scaleY = 0.6f
            box.animate().translationY(0f).translationX(0f).scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(340).setInterpolator(android.view.animation.DecelerateInterpolator()).withEndAction(null).start()
        }
        showBar()
    }

    // ---------------- settings ----------------

    private var settingsView: View? = null
    private fun showSettings() {
        if (settingsView != null) return
        val root = window.decorView.findViewById<FrameLayout>(android.R.id.content)
        val shade = FrameLayout(this).apply { setBackgroundColor(Color.argb(215, 4, 5, 8)); alpha = 0f; isClickable = true }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(60), dp(20), dp(30)) }
        fun hint(t: String) = TextView(this).apply { text = t; textSize = 11f; setTextColor(muted); setPadding(dp(2), dp(2), 0, 0) }
        fun section(title: String, h: String? = null): LinearLayout {
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, dp(10)) }
            box.addView(TextView(this).apply { text = title; textSize = 13f; setTextColor(fg) })
            if (h != null) box.addView(hint(h))
            list.addView(box); return box
        }
        fun <T> choice(title: String, opts: List<Pair<T, String>>, get: () -> T, set: (T) -> Unit, h: String? = null) {
            val box = section(title, h); val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val btns = ArrayList<Pair<T, TextView>>()
            for ((v, name) in opts) { val b = pill(name) { set(v); Settings.save(this); btns.forEach { (vv, bb) -> setOn(bb, vv == get()) } }; btns.add(v to b); r.addView(b, lp()) }
            btns.forEach { (vv, bb) -> setOn(bb, vv == get()) }
            box.addView(scroll(r), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }
        val S = Settings
        // sensitivity slider
        run {
            val box = section("Sensitivity", "Raise it for quiet music or the microphone.")
            val v = TextView(this).apply { setTextColor(fg); textSize = 12f; typeface = Typeface.MONOSPACE; text = "%.2f".format(S.sens) }
            val sb = SeekBar(this).apply {
                max = 270; progress = ((S.sens - 0.3f) * 100).toInt()
                progressTintList = ColorStateList.valueOf(accent); thumbTintList = ColorStateList.valueOf(accent)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) { S.sens = 0.3f + p / 100f; v.text = "%.2f".format(S.sens) }
                    override fun onStartTrackingTouch(sb: SeekBar) {}
                    override fun onStopTrackingTouch(sb: SeekBar) { Settings.save(this@MainActivity) }
                })
            }
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            r.addView(sb, LinearLayout.LayoutParams(dp(240), ViewGroup.LayoutParams.WRAP_CONTENT)); r.addView(v)
            box.addView(r)
        }
        choice("Wave strings", listOf(1 to "1", 3 to "3", 5 to "5", 7 to "7"), { S.strings }, { S.strings = it }, "How many strings share the spectrum, 20 Hz to 20 kHz.")
        choice("Response", listOf("smooth" to "Smooth", "fast" to "Fast", "instant" to "Instant"), { S.response }, { S.response = it })
        choice("Bars", listOf(24 to "24", 48 to "48", 72 to "72", 96 to "96"), { S.bars }, { S.bars = it })
        choice("Peak markers", listOf("off" to "Off", "slow" to "Slow fall", "fast" to "Fast fall"), { S.peaks }, { S.peaks = it })
        choice("Direction", listOf("bottom" to "Bottom up", "center" to "Center out", "top" to "Top down"), { S.layout }, { S.layout = it })
        choice("Bass position", listOf("left" to "Left", "middle" to "Middle"), { S.bass }, { S.bass = it })
        choice("LED segments", listOf(false to "Off", true to "On"), { S.seg }, { S.seg = it })
        choice("Trippy", listOf(0f to "Off", 0.5f to "On", 1f to "Max"), { S.trip }, { S.trip = it }, "Bends, spins and shifts the colours of the trails.")
        choice("Colour follows intensity", listOf(false to "Off", true to "On"), { S.aggcolor }, { S.aggcolor = it })
        choice("Red at peaks", listOf(false to "Off", true to "On"), { S.peakred }, { S.peakred = it })
        choice("Colour", listOf("picture" to "Picture", "palette" to "Palette", "heat" to "Heat", "rainbow" to "Rainbow"), { S.color }, { S.color = it })
        choice("Trails", listOf("off" to "Off", "short" to "Short", "long" to "Long"), { S.trails }, { S.trails = it })
        choice("Beat flash", listOf(false to "Off", true to "On"), { S.flash }, { S.flash = it })
        choice("Screen shake", listOf(false to "Off", true to "On"), { S.shake }, { S.shake = it })
        choice("Quality", listOf("auto" to "Auto (smoothest)", "balanced" to "Balanced", "high" to "High (up to 4K)"), { S.quality }, { S.quality = it },
            "Auto lowers the detail slightly when needed to keep the full frame rate.")
        list.addView(pill("Reset to defaults") { Settings.reset(); Settings.save(this); closeSettings(); showSettings() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        val sv = android.widget.ScrollView(this).apply { addView(list) }
        shade.addView(sv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        shade.addView(TextView(this).apply { text = "Settings"; textSize = 16f; setTextColor(fg); setPadding(dp(20), dp(18), dp(20), dp(10)) },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        shade.addView(pill("Close") { closeSettings() }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { topMargin = dp(12); rightMargin = dp(14) })
        root.addView(shade, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        shade.animate().alpha(1f).setDuration(220).start()
        settingsView = shade
    }
    private fun closeSettings() {
        val v = settingsView ?: return; settingsView = null
        v.animate().alpha(0f).setDuration(200).withEndAction { (v.parent as? ViewGroup)?.removeView(v) }.start()
        showBar()
    }

    // ---------------- picture chooser (tap the picture's name) ----------------

    private var picker: View? = null
    private fun showPicker() {
        if (picker != null) return
        val root = window.decorView.findViewById<FrameLayout>(android.R.id.content)
        val shade = FrameLayout(this).apply { setBackgroundColor(Color.argb(215, 4, 5, 8)); alpha = 0f; isClickable = true }
        val grid = android.widget.GridLayout(this).apply {
            val wDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
            columnCount = max(2, min(6, (wDp / 170).toInt()))
            setPadding(dp(14), dp(56), dp(14), dp(24))
        }
        val cellW = (resources.displayMetrics.widthPixels - dp(28)) / grid.columnCount - dp(10)
        pics.forEachIndexed { i, p ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(5), dp(5), dp(5), dp(5))
                background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setStroke(dp(if (i == picIndex) 2 else 1), if (i == picIndex) accent else Color.argb(30, 233, 237, 242)) }
                setOnClickListener { setPicture(i); closePicker() }
            }
            val img = android.widget.ImageView(this).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; setImageBitmap(thumb(p, cellW)) }
            cell.addView(img, LinearLayout.LayoutParams(cellW, (cellW * 0.62f).toInt()))
            cell.addView(TextView(this).apply { text = p.name; textSize = 12f; setTextColor(fg); setPadding(dp(2), dp(6), 0, dp(2)) })
            grid.addView(cell, android.widget.GridLayout.LayoutParams().apply { width = cellW + dp(10); setMargins(dp(5), dp(5), dp(5), dp(5)) })
        }
        val sv = android.widget.ScrollView(this).apply { addView(grid) }
        shade.addView(sv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        shade.addView(TextView(this).apply { text = "Pictures"; textSize = 16f; setTextColor(fg); setPadding(dp(20), dp(18), dp(20), dp(10)) },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        shade.addView(pill("Close") { closePicker() }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { topMargin = dp(12); rightMargin = dp(14) })
        root.addView(shade, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        shade.animate().alpha(1f).setDuration(220).start()
        picker = shade
    }
    private fun closePicker() {
        val v = picker ?: return; picker = null
        v.animate().alpha(0f).setDuration(200).withEndAction { (v.parent as? ViewGroup)?.removeView(v) }.start()
        showBar()
    }
    private val thumbs = HashMap<String, android.graphics.Bitmap>()
    private fun thumb(p: Pic, w: Int): android.graphics.Bitmap = thumbs.getOrPut(p.id) {
        if (p.gen > 0 || p.file.isEmpty()) {
            // generated pictures: a soft blend of their colours
            val h = (w * 0.62f).toInt().coerceAtLeast(1)
            val b = android.graphics.Bitmap.createBitmap(w.coerceAtLeast(1), h, android.graphics.Bitmap.Config.ARGB_8888)
            val c = Canvas(b); c.drawColor(Color.BLACK)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            p.pal.forEachIndexed { k, col ->
                paint.shader = android.graphics.RadialGradient(w * (0.2f + 0.2f * k), h * (0.35f + 0.1f * (k % 2)), w * 0.35f,
                    Color.argb(150, (col[0] * 255).toInt(), (col[1] * 255).toInt(), (col[2] * 255).toInt()), Color.TRANSPARENT, android.graphics.Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
            }
            b
        } else {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            assets.open("pics/" + p.file).use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
            var sample = 1; while (o.outWidth / (sample * 2) >= w) sample *= 2
            assets.open("pics/" + p.file).use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) }!!
        }
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


    private fun refreshButtons() {
        picName.text = pics[picIndex].name
        styleButtons.forEachIndexed { i, b -> setOn(b, i == renderer.style) }
        for ((k, b) in sourceButtons) setOn(b, k == source)
        seekRow.visibility = if (source == "demo") View.VISIBLE else View.GONE
        (seekRow.parent as? View)?.visibility = seekRow.visibility
        playBtn.text = if (demo.paused) "▶" else "❚❚"
        shareBtn.text = if (screenShare) "Screen share: on" else "Screen share: off"; setOn(shareBtn, screenShare)
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
        if (mode == "device" && screenShare && Build.VERSION.SDK_INT >= 29) {
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
            else { Toast.makeText(this, "No screen share: listening to the phone's sound without it", Toast.LENGTH_SHORT).show(); startService("device", 0, null) }
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

    @Deprecated("Back closes the picture chooser first")
    override fun onBackPressed() { if (settingsView != null) closeSettings() else if (picker != null) closePicker() else @Suppress("DEPRECATION") super.onBackPressed() }

    override fun onResume() { super.onResume(); gl.onResume() }
    override fun onPause() { gl.onPause(); super.onPause() }

    override fun onDestroy() {
        brain.log?.flush()
        ui.removeCallbacksAndMessages(null)
        stopAll()
        super.onDestroy()
    }

    companion object {
        private const val REQ_PROJECTION = 1
        private const val REQ_AUDIO = 3
    }
}
