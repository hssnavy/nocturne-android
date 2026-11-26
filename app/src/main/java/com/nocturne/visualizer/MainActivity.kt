package com.nocturne.visualizer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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
 * Nocturne: native visualizer. Sound comes straight from Android's audio system into the analyser and
 * the visuals are drawn with OpenGL; there is no browser in between.
 */
class MainActivity : Activity() {

    private lateinit var gl: GLSurfaceView
    private lateinit var renderer: VisualRenderer
    private lateinit var pictures: List<Picture>
    private lateinit var demo: DemoPlayer
    private lateinit var bar: LinearLayout
    private lateinit var hud: TextView
    private lateinit var picName: TextView
    private lateinit var status: TextView
    private lateinit var meter: ProgressBar
    private lateinit var seekRow: LinearLayout
    private lateinit var seek: SeekBar
    private lateinit var timeText: TextView
    private val styleButtons = ArrayList<TextView>()
    private val sourceButtons = HashMap<String, TextView>()
    private val ui = Handler(Looper.getMainLooper())
    private var source = ""                 // "device", "mic", "demo" or ""
    private var pendingMode: String? = null
    private var lastStatus = ""
    private var seeking = false
    private val styles = arrayOf("Wave", "Tunnel", "Light speed")
    private val accent = Color.rgb(255, 106, 43)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        pictures = loadPictures()
        demo = DemoPlayer(this)
        val prefs = getPreferences(MODE_PRIVATE)

        renderer = VisualRenderer(this, pictures) { msg -> runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() } }
        renderer.picture = prefs.getInt("picture", 0).coerceIn(0, pictures.size - 1)
        renderer.style = prefs.getInt("style", 0).coerceIn(0, styles.size - 1)

        gl = GLSurfaceView(this)
        gl.setEGLContextClientVersion(3)
        gl.preserveEGLContextOnPause = true
        gl.setRenderer(renderer)
        gl.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        gl.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_DOWN) showBar(); false }

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        root.addView(gl, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        hud = TextView(this).apply { setTextColor(Color.argb(170, 233, 237, 242)); textSize = 13f; setPadding(dp(18), dp(16), dp(18), dp(8)) }
        root.addView(hud, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        bar = buildBar()
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(14); leftMargin = dp(10); rightMargin = dp(10) })
        setContentView(root)
        hideSystemBars()
        refreshButtons()
        ui.post(tick)
        // the app's main job: react to whatever this phone plays (falls back to the microphone)
        ui.postDelayed({ if (source.isEmpty()) startPhone() }, 600)
    }

    // ---------------- controls ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun pill(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.rgb(233, 237, 242))
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

    private fun row(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        for (v in views) addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) })
    }

    private fun label(t: String) = TextView(this).apply {
        text = t.uppercase(); textSize = 10f; letterSpacing = 0.16f
        setTextColor(Color.rgb(138, 147, 160)); setPadding(dp(4), 0, dp(4), 0)
    }

    private fun scroll(v: View) = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(v) }

    private fun buildBar(): LinearLayout {
        picName = TextView(this).apply { setTextColor(Color.WHITE); textSize = 13f; setPadding(dp(8), 0, dp(8), 0) }
        val prev = pill("‹") { setPicture(renderer.picture - 1) }
        val next = pill("›") { setPicture(renderer.picture + 1) }
        styleButtons.clear()
        val styleRow = row(label("Style"))
        styles.forEachIndexed { i, s -> val b = pill(s) { setStyle(i) }; styleButtons.add(b); styleRow.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) }) }
        val r1 = row(prev, picName, next, styleRow)

        sourceButtons["device"] = pill("This phone") { toggle("device") }
        sourceButtons["mic"] = pill("Microphone") { toggle("mic") }
        sourceButtons["demo"] = pill("Demo track") { toggle("demo") }
        status = TextView(this).apply { setTextColor(Color.rgb(138, 147, 160)); textSize = 11f; typeface = Typeface.MONOSPACE; setPadding(dp(8), 0, dp(6), 0) }
        meter = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progressTintList = android.content.res.ColorStateList.valueOf(accent)
        }
        val r2 = row(label("Sound"), sourceButtons["device"]!!, sourceButtons["mic"]!!, sourceButtons["demo"]!!, label("Energy"))
        r2.addView(meter, LinearLayout.LayoutParams(dp(70), dp(6)))
        r2.addView(status)

        seek = SeekBar(this).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) timeText.text = fmt(p / 1000.0 * demo.durationUs / 1e6) + " / " + fmt(demo.durationUs / 1e6)
                }
                override fun onStartTrackingTouch(s: SeekBar) { seeking = true }
                override fun onStopTrackingTouch(s: SeekBar) { demo.seek((s.progress / 1000.0 * demo.durationUs).toLong()); seeking = false; showBar() }
            })
        }
        timeText = TextView(this).apply { setTextColor(Color.rgb(233, 237, 242)); textSize = 11f; typeface = Typeface.MONOSPACE }
        val back = pill("−10") { demo.seek(demo.positionUs - 10_000_000L) }
        val fwd = pill("+30") { demo.seek(demo.positionUs + 30_000_000L) }
        seekRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
        seekRow.addView(back)
        seekRow.addView(seek, LinearLayout.LayoutParams(dp(240), ViewGroup.LayoutParams.WRAP_CONTENT))
        seekRow.addView(timeText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) })
        seekRow.addView(fwd)

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.argb(190, 10, 12, 16)); setStroke(dp(1), Color.argb(33, 233, 237, 242)) }
            addView(scroll(r1))
            addView(scroll(r2), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            addView(scroll(seekRow), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
    }

    private fun fmt(sec: Double): String { val s = sec.toInt().coerceAtLeast(0); return "${s / 60}:${(s % 60).toString().padStart(2, '0')}" }

    private fun setPicture(i: Int) {
        val n = pictures.size
        renderer.picture = ((i % n) + n) % n
        getPreferences(MODE_PRIVATE).edit().putInt("picture", renderer.picture).apply()
        refreshButtons()
    }

    private fun setStyle(i: Int) {
        renderer.style = i
        getPreferences(MODE_PRIVATE).edit().putInt("style", i).apply()
        refreshButtons()
    }

    private fun refreshButtons() {
        picName.text = pictures[renderer.picture].name
        styleButtons.forEachIndexed { i, b -> setOn(b, i == renderer.style) }
        for ((k, b) in sourceButtons) setOn(b, k == source)
        seekRow.visibility = if (source == "demo") View.VISIBLE else View.GONE
        hud.text = "${pictures[renderer.picture].name}  ·  ${styles[renderer.style]}"
    }

    // controls fade away while music plays; tap the screen to bring them back
    private val hideBar = Runnable { if (source.isNotEmpty() && !seeking) { bar.animate().alpha(0f).setDuration(500).withEndAction { bar.visibility = View.INVISIBLE }.start(); hud.animate().alpha(0f).setDuration(500).start() } }
    private fun showBar() {
        bar.animate().cancel(); bar.visibility = View.VISIBLE; bar.alpha = 1f; hud.alpha = 1f
        ui.removeCallbacks(hideBar); ui.postDelayed(hideBar, 3500)
    }

    private val tick = object : Runnable {
        override fun run() {
            val f = Frame(); Engine.analyzer.snapshot(f)
            meter.progress = (f.energy * 100).toInt()
            status.text = (if (source == "demo") "demo" else Engine.via) + (if (f.bpm > 0 && f.live) "  ${f.bpm.toInt()} BPM" else "")
            if (source == "demo" && !seeking && demo.durationUs > 0) {
                seek.progress = (demo.positionUs * 1000 / demo.durationUs).toInt()
                timeText.text = fmt(demo.positionUs / 1e6) + " / " + fmt(demo.durationUs / 1e6)
            }
            val st = Engine.status
            if (st != lastStatus) {
                lastStatus = st
                val msg = when (st) {
                    "device" -> "Listening to the sound this phone plays"
                    "mic-fallback" -> "Nothing is playing on this phone, so I'm listening through the microphone"
                    "blocked" -> "The app that's playing won't let other apps hear its sound. Try another music app, or the Demo track"
                    "capture-denied" -> "Phone sound needs the screen-share permission (tap Start now). Using the microphone for now"
                    "capture-failed" -> "This phone wouldn't share its sound, so I'm using the microphone"
                    "mic" -> "Listening through the microphone"
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
        demo.start()
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

    private fun loadPictures(): List<Picture> {
        val arr = JSONArray(assets.open("pictures.json").bufferedReader().use { it.readText() })
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val p = o.getJSONArray("pal")
            Picture(o.getString("name"), o.getString("file"), Array(4) { k -> val c = p.getJSONArray(k); floatArrayOf(c.getDouble(0).toFloat(), c.getDouble(1).toFloat(), c.getDouble(2).toFloat()) })
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
