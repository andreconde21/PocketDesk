package dev.outsmartis.pocketdesk.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

/**
 * The PocketDesk desktop page full screen on a TV. The page itself turns the D-pad into a mouse
 * cursor (it sees " PocketDeskTV" in the user agent); this activity only forwards the keys a page
 * never gets: Back and Menu. Back on the pairing screen (or a long press anywhere) leaves the app.
 */
class TvActivity : Activity() {

    private lateinit var web: WebView
    private var backLongPressed = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        web = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0B0D10"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.userAgentString = settings.userAgentString + " PocketDeskTV/" + BuildConfig.VERSION_NAME
            isFocusable = true
            isFocusableInTouchMode = true
            webViewClient = object : WebViewClient() {
                // the desktop stays in the app; links elsewhere (help pages) open here too, Back returns
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false
            }
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) = request.deny()
            }
        }
        setContentView(web)
        hideSystemBars()
        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else web.loadUrl(intent?.data?.takeIf { it.scheme == "https" }?.toString() ?: BuildConfig.DESK_URL)
        web.requestFocus()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.data?.takeIf { it.scheme == "https" }?.let { web.loadUrl(it.toString()) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (event.repeatCount == 0) {
                        event.startTracking()
                        backLongPressed = false
                    } else if (event.isLongPress && !backLongPressed) {
                        backLongPressed = true
                        Toast.makeText(this, "Closing PocketDesk", Toast.LENGTH_SHORT).show()
                        moveTaskToBack(true)
                    }
                } else if (event.action == KeyEvent.ACTION_UP && !backLongPressed && !event.isCanceled) {
                    remote("back") { used -> if (!used) onNothingToGoBackTo() }
                }
                return true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_PROG_RED -> {
                if (event.action == KeyEvent.ACTION_UP) remote("menu") {}
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun onNothingToGoBackTo() {
        if (web.canGoBack() && Uri.parse(web.url ?: "").path != "/") web.goBack() else moveTaskToBack(true)
    }

    private fun remote(cmd: String, done: (Boolean) -> Unit) {
        web.evaluateJavascript("(window.pdRemote ? window.pdRemote('$cmd') : false) === true") { r ->
            done(r == "true")
        }
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
