package app.azerothsurvivors

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.view.inputmethod.InputMethodManager
import androidx.core.view.ViewCompat
import android.net.Uri
import android.util.Base64
import android.webkit.ValueCallback
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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

    /** The game's Options → Saves page hands its .sav file over as a download; it's written to Download/. */
    private inner class SaveFiles {
        @JavascriptInterface
        fun save(name: String, base64: String) {
            val result = runCatching { DownloadsFolder.write(this@GameActivity, name, Base64.decode(base64, Base64.DEFAULT)) }
            runOnUiThread {
                val text = result.fold({ "Saved to Download/${DownloadsFolder.NAME}/$it" }, { "Couldn't save the file: ${it.message}" })
                Toast.makeText(this@GameActivity, text, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * The game keeps a hidden textarea focused while one of its text fields takes text, and hands focus
     * back to it after every tap: on a touchscreen that would open the keyboard on each tap. So its text
     * boxes ask for no on-screen keyboard ([KEYBOARD_SCRIPT]), and a ⌨ button, shown while a field takes
     * text, opens it on purpose.
     */
    private inner class Keyboard {
        @JavascriptInterface
        fun wants(on: Boolean) {
            runOnUiThread { keyboardButton.visibility = if (on) View.VISIBLE else View.GONE }
        }
    }

    private lateinit var keyboardButton: TextView
    private var keyboardOpen = false

    private fun setKeyboard(open: Boolean) {
        keyboardOpen = open
        web.evaluateJavascript("window.__androidKeyboard && window.__androidKeyboard($open)", null)
        val imm = getSystemService(InputMethodManager::class.java)
        if (open) {
            web.requestFocus()
            web.postDelayed({ imm?.showSoftInput(web, InputMethodManager.SHOW_IMPLICIT) }, 150)
        } else {
            imm?.hideSoftInputFromWindow(web.windowToken, 0)
        }
    }

    // The game's "load a save" asks for a file; Android's picker answers it.
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        fileCallback?.onReceiveValue(uri?.let { arrayOf(it) })
        fileCallback = null
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
        web.addJavascriptInterface(SaveFiles(), "AndroidSaves")
        web.addJavascriptInterface(Keyboard(), "AndroidKeyboard")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, PadState.BRIDGE_SCRIPT, setOf("https://${BuildConfig.GAME_HOST}"))
            WebViewCompat.addDocumentStartJavaScript(web, SAVE_SCRIPT, setOf("https://${BuildConfig.GAME_HOST}"))
            WebViewCompat.addDocumentStartJavaScript(web, KEYBOARD_SCRIPT, setOf("https://${BuildConfig.GAME_HOST}"))
            WebViewCompat.addDocumentStartJavaScript(web, COMPAT_SCRIPT, setOf("https://${BuildConfig.GAME_HOST}"))
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
                    view.evaluateJavascript(SAVE_SCRIPT, null)
                    view.evaluateJavascript(KEYBOARD_SCRIPT, null)
                    view.evaluateJavascript(COMPAT_SCRIPT, null)
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
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return runCatching { filePicker.launch(arrayOf("*/*")) }.onFailure { fileCallback = null }.isSuccess
            }

            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.i("AzerothSurvivors", "${message.messageLevel()}: ${message.message()}")
                return true
            }
        }

        loading = buildLoading()
        keyboardButton = TextView(this).apply {
            text = "⌨"
            textSize = 26f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(150, 0, 0, 0))
                setStroke(3, Color.argb(200, 229, 193, 88))
            }
            contentDescription = "Keyboard"
            visibility = View.GONE
            setOnClickListener { setKeyboard(!keyboardOpen) }
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(loading, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val size = (56 * resources.displayMetrics.density).toInt()
            val margin = (16 * resources.displayMetrics.density).toInt()
            addView(keyboardButton, FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END).apply { setMargins(margin, margin, margin, margin) })
        }
        setContentView(root)
        // Closing the keyboard (back, or its own button) goes back to taps not opening it.
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            if (keyboardOpen && !insets.isVisible(WindowInsetsCompat.Type.ime())) setKeyboard(false)
            insets
        }

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
        /**
         * Runs before the page's scripts. A link that downloads a blob (how the page hands over its .sav
         * file) can't be fetched from outside the page, so its bytes go to [SaveFiles] instead.
         */
        private const val SAVE_SCRIPT = """
(() => {
  if (!window.AndroidSaves) return;
  const click = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    if (this.download && this.href && this.href.startsWith('blob:')) {
      const name = this.download;
      fetch(this.href).then((r) => r.arrayBuffer()).then((buf) => {
        const bytes = new Uint8Array(buf);
        let s = '';
        for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
        AndroidSaves.save(name, btoa(s));
      }).catch((e) => console.warn('Save download failed', e));
      return;
    }
    return click.call(this);
  };
})();
"""

        /**
         * Runs before the page's scripts. The game asks for a normal WebGPU adapter and stops with a "no usable
         * graphics card" message when there is none. Some GPUs only get WebGPU's reduced compatibility mode, so
         * when the normal request comes back empty, ask again for that one. (Experimental: the game may still
         * need more than that mode offers.)
         */
        private const val COMPAT_SCRIPT = """
(() => {
  if (!navigator.gpu || !navigator.gpu.requestAdapter) return;
  const original = navigator.gpu.requestAdapter.bind(navigator.gpu);
  navigator.gpu.requestAdapter = async function (options) {
    const adapter = await original(options);
    if (adapter) return adapter;
    try {
      const compat = await original(Object.assign({}, options, { featureLevel: 'compatibility' }));
      if (compat) { console.warn('WebGPU: no normal adapter; using compatibility mode'); return compat; }
    } catch (e) {}
    return null;
  };
})();
"""

        /**
         * Runs before the page's scripts: text boxes ask for no on-screen keyboard unless the ⌨ button
         * opened it, and the game's "a field takes text" signal (window.__rogue_ime.typing) is passed on
         * to [Keyboard] so the button shows only then.
         */
        private const val KEYBOARD_SCRIPT = """
(() => {
  if (!window.AndroidKeyboard) return;
  let open = false;
  const apply = () => {
    for (const el of document.querySelectorAll('textarea, input:not([type=file])')) el.inputMode = open ? 'text' : 'none';
  };
  new MutationObserver(apply).observe(document.documentElement, { childList: true, subtree: true });
  window.__androidKeyboard = (show) => {
    open = !!show;
    apply();
    const el = document.activeElement;
    if (el && (el.tagName === 'TEXTAREA' || el.tagName === 'INPUT')) {
      el.blur();
      el.focus({ preventScroll: true });
    }
  };
  let ime;
  Object.defineProperty(window, '__rogue_ime', {
    configurable: true,
    get() { return ime; },
    set(value) {
      ime = value;
      if (value && typeof value.typing === 'function') {
        const typing = value.typing.bind(value);
        value.typing = (on, x, y) => {
          AndroidKeyboard.wants(!!on);
          if (!on && open) window.__androidKeyboard(false);
          return typing(on, x, y);
        };
      }
    },
  });
})();
"""

        fun open(context: android.content.Context) {
            context.startActivity(Intent(context, GameActivity::class.java))
        }
    }
}
