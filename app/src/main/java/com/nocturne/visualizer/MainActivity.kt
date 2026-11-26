package com.nocturne.visualizer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader

/**
 * Full-screen visualizer. The visuals are the same page as the web preview (bundled in assets/web);
 * sound from this phone or the microphone is captured natively by [CaptureService] and handed to the
 * page through the NocturneNative bridge.
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingMode: String? = null
    private var pendingWebPermission: PermissionRequest? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }

        // serve the bundled page from a real https origin so fetch(), audio and storage behave like on the web
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this)
        web.setBackgroundColor(0xFF000000.toInt())
        setContentView(web)
        hideSystemBars()

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.settings.allowFileAccess = false

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)
        }
        web.webChromeClient = object : WebChromeClient() {
            // "Music file": open the phone's file picker
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(emptyArray())
                fileCallback = callback
                return try {
                    startActivityForResult(params.createIntent(), REQ_FILE)
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    if (hasMic()) request.grant(request.resources)
                    else {
                        pendingWebPermission = request
                        requestPermissions(neededPermissions(), REQ_AUDIO)
                    }
                }
            }
        }
        web.addJavascriptInterface(Bridge(), "NocturneNative")
        web.loadUrl("https://appassets.androidplatform.net/assets/web/index.html")
    }

    /** Called from the page. */
    inner class Bridge {
        @JavascriptInterface
        fun start(mode: String) {
            runOnUiThread { startCapture(mode) }
        }

        @JavascriptInterface
        fun stop() {
            runOnUiThread {
                stopService(Intent(this@MainActivity, CaptureService::class.java))
                AudioHub.reset()
            }
        }

        @JavascriptInterface
        fun read(): String = AudioHub.drainBase64()

        @JavascriptInterface
        fun status(): String = AudioHub.status

        @JavascriptInterface
        fun sampleRate(): Int = AudioHub.SAMPLE_RATE
    }

    private fun hasMic() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun neededPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33)
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        else arrayOf(Manifest.permission.RECORD_AUDIO)

    private fun startCapture(mode: String) {
        // Android requires the microphone permission for both the microphone and capturing this phone's sound
        if (!hasMic()) {
            pendingMode = mode
            requestPermissions(neededPermissions(), REQ_AUDIO)
            return
        }
        if (mode == "device" && Build.VERSION.SDK_INT >= 29) {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
        } else {
            startCaptureService("mic", 0, null)
        }
    }

    private fun startCaptureService(mode: String, resultCode: Int, data: Intent?) {
        AudioHub.reset()
        val intent = Intent(this, CaptureService::class.java)
            .putExtra("mode", mode)
            .putExtra("code", resultCode)
        if (data != null) intent.putExtra("data", data)
        startForegroundService(intent)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_FILE -> {
                fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
                fileCallback = null
            }
            REQ_PROJECTION -> {
                if (resultCode == RESULT_OK && data != null) startCaptureService("device", resultCode, data)
                else startCaptureService("mic", 0, null)   // capture declined: use the microphone
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_AUDIO) return
        val ok = hasMic()
        pendingWebPermission?.let { if (ok) it.grant(it.resources) else it.deny() }
        pendingWebPermission = null
        val mode = pendingMode
        pendingMode = null
        if (mode != null) {
            if (ok) startCapture(mode) else AudioHub.status = "no-permission"
        }
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            hideSystemBarsLegacy()
        }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBarsLegacy() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onPause() {
        web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        stopService(Intent(this, CaptureService::class.java))
        web.destroy()
        super.onDestroy()
    }

    companion object {
        private const val REQ_PROJECTION = 1
        private const val REQ_FILE = 2
        private const val REQ_AUDIO = 3
    }
}
