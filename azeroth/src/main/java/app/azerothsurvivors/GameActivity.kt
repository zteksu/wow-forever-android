package app.azerothsurvivors

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * The game, full screen in a WebView. The page loads from the game's own site when there's a connection,
 * so it's the current version, and from the app's saved copy when there isn't ([OfflineData]); saves live
 * in the WebView's storage for this app. Controller input goes to the page through [PadState] because
 * WebView has no Gamepad API.
 */
class GameActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var loading: View
    private val pad = PadState()
    @Volatile private var padJson = ""
    private var audioPrimed = false

    private inner class Bridge {
        @JavascriptInterface
        fun state(): String = padJson
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true // the game keeps its saves in localStorage
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false // music starts without a tap, even on a controller
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
        }
        web.addJavascriptInterface(Bridge(), "AndroidPad")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, PadState.BRIDGE_SCRIPT, setOf("https://${BuildConfig.GAME_HOST}"))
        }
        val offline = OfflineData.get(this)
        web.webViewClient = object : WebViewClient() {
            // Game files come from the app's own copy or the imported archives when it has them (see OfflineData).
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = offline.intercept(request)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.host == BuildConfig.GAME_HOST) return false
                // Discord, the Windows download and other links open outside the game.
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    view.evaluateJavascript(PadState.BRIDGE_SCRIPT, null)
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                loading.visibility = View.GONE
                web.requestFocus()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) showError("Couldn't reach the game (${error.description}). Check the internet connection.")
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.i("AzerothSurvivors", "${message.messageLevel()}: ${message.message()}")
                return true
            }
        }

        loading = buildLoading()
        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                addView(loading, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            },
        )

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = confirmExit()
            },
        )

        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(BuildConfig.GAME_URL)
    }

    private fun buildLoading(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(Color.BLACK)
        addView(ProgressBar(context).apply { isIndeterminate = true })
        addView(
            TextView(context).apply {
                text = getString(R.string.loading)
                setTextColor(Color.argb(200, 255, 255, 255))
                textSize = 14f
                setPadding(0, 32, 0, 0)
            },
        )
    }

    private fun showError(message: String) {
        loading.visibility = View.GONE
        AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setMessage(message)
            .setPositiveButton("Retry") { _, _ ->
                loading.visibility = View.VISIBLE
                web.reload()
            }
            .setNegativeButton("Quit") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun confirmExit() {
        AlertDialog.Builder(this)
            .setTitle("Leave the game?")
            .setMessage("A run in progress may not be saved.")
            .setPositiveButton("Leave") { _, _ -> finish() }
            .setNegativeButton("Keep playing", null)
            .show()
    }

    private fun isController(device: InputDevice?): Boolean {
        val sources = device?.sources ?: return false
        return sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
    }

    private fun publish(device: InputDevice?) {
        device?.name?.let { pad.name = it }
        padJson = pad.toJson()
        // The page starts its audio on its first key or tap; controller input never reaches it as one.
        if (!audioPrimed) {
            audioPrimed = true
            web.evaluateJavascript("window.dispatchEvent(new KeyboardEvent('keydown'))", null)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isController(event.device) && PadState.buttonIndex(event.keyCode) != null) {
            if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) {
                pad.onKey(event.keyCode, event.action == KeyEvent.ACTION_DOWN, SystemClock.uptimeMillis())
                publish(event.device)
            }
            // Handled here, so the WebView doesn't also treat the d-pad as arrow keys.
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val device = event.device
        if (isController(device) && event.action == MotionEvent.ACTION_MOVE) {
            val flat = device?.getMotionRange(MotionEvent.AXIS_X, event.source)?.flat ?: 0.1f
            pad.onMotion(
                lx = event.getAxisValue(MotionEvent.AXIS_X),
                ly = event.getAxisValue(MotionEvent.AXIS_Y),
                rx = event.getAxisValue(MotionEvent.AXIS_Z),
                ry = event.getAxisValue(MotionEvent.AXIS_RZ),
                lt = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE)),
                rt = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS)),
                hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X),
                hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y),
                flat = flat,
                now = SystemClock.uptimeMillis(),
            )
            publish(device)
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        web.onPause()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    companion object {
        fun open(context: android.content.Context) {
            context.startActivity(Intent(context, GameActivity::class.java))
        }
    }
}
