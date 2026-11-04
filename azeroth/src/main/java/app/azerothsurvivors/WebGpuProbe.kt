package app.azerothsurvivors

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject

/** What the WebView says about WebGPU on this phone: whether it hands out a GPU adapter, and which. */
data class WebGpuStatus(
    val hasApi: Boolean = false,
    /** A normal adapter, the kind the game asks for. */
    val adapter: Boolean = false,
    /** Only the reduced "compatibility mode" adapter could be had. */
    val compat: Boolean = false,
    val vendor: String = "",
    val architecture: String = "",
    val device: String = "",
    val description: String = "",
    val fallback: Boolean = false,
    val maxBufferSize: Long = 0,
    val maxStorageBuffers: Int = 0,
    val error: String = "",
) {
    val usable get() = hasApi && adapter

    /** The GPU as the browser names it, or "" when there isn't one. */
    val gpuName: String
        get() = listOf(description, vendor, architecture, device).firstOrNull { it.isNotBlank() }.orEmpty()

    fun summary(): String = when {
        !hasApi -> "This WebView has no WebGPU."
        adapter && fallback -> "WebGPU is running in software on ${gpuName.ifBlank { "this phone" }}, which is too slow for the game."
        adapter -> "WebGPU ready${gpuName.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}."
        compat -> "WebGPU has only its reduced compatibility mode on this GPU${gpuName.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()}. The game may not run."
        else -> "Android's WebView offers no WebGPU on this phone's GPU, so the game can't start here. " +
            "WebGPU on Android is only enabled for some GPUs (Qualcomm Adreno, Arm Mali)."
    }

    fun report(): String = buildString {
        appendLine("Azeroth Survivors WebGPU check")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE})")
        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("navigator.gpu: $hasApi")
        appendLine("requestAdapter(): ${if (adapter) "adapter" else "null"}")
        appendLine("compatibility mode adapter: $compat")
        appendLine("fallback (software) adapter: $fallback")
        appendLine("GPU: vendor='$vendor' architecture='$architecture' device='$device' description='$description'")
        appendLine("maxBufferSize=$maxBufferSize maxStorageBuffersPerShaderStage=$maxStorageBuffers")
        if (error.isNotBlank()) appendLine("error: $error")
        appendLine("Summary: ${summary()}")
    }
}

object WebGpuProbe {
    private const val TIMEOUT_MS = 10_000L

    /** Runs in a hidden page with the game's origin, since WebGPU only exists in secure contexts. */
    internal val PAGE = """
        <!doctype html><meta charset="utf-8"><script>
        (async () => {
          const out = { gpu: !!navigator.gpu };
          try {
            if (navigator.gpu) {
              const ask = async (options) => { try { return await navigator.gpu.requestAdapter(options); } catch (e) { out.error = String(e); return null; } };
              const normal = await ask();
              out.adapter = !!normal;
              const compat = normal ? null : await ask({ featureLevel: 'compatibility' });
              out.compat = !!compat;
              const a = normal || compat;
              if (a) {
                const info = a.info || (a.requestAdapterInfo ? await a.requestAdapterInfo() : {});
                out.vendor = info.vendor || ''; out.architecture = info.architecture || '';
                out.device = info.device || ''; out.description = info.description || '';
                out.fallback = !!a.isFallbackAdapter;
                out.maxBufferSize = a.limits.maxBufferSize; out.maxStorageBuffers = a.limits.maxStorageBuffersPerShaderStage;
              }
            }
          } catch (e) { out.error = String(e); }
          AndroidProbe.result(JSON.stringify(out));
        })();
        </script>
    """.trimIndent()

    internal fun parse(json: String): WebGpuStatus {
        val o = runCatching { JSONObject(json) }.getOrElse { return WebGpuStatus(error = "Unreadable result: $json") }
        return WebGpuStatus(
            hasApi = o.optBoolean("gpu"),
            adapter = o.optBoolean("adapter"),
            compat = o.optBoolean("compat"),
            vendor = o.optString("vendor"),
            architecture = o.optString("architecture"),
            device = o.optString("device"),
            description = o.optString("description"),
            fallback = o.optBoolean("fallback"),
            maxBufferSize = o.optLong("maxBufferSize"),
            maxStorageBuffers = o.optInt("maxStorageBuffers"),
            error = o.optString("error"),
        )
    }

    /** Asks the WebView for a GPU adapter in an invisible page and reports once, on the main thread. */
    @SuppressLint("SetJavaScriptEnabled")
    fun run(activity: Activity, onResult: (WebGpuStatus) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        val web = WebView(activity).apply { settings.javaScriptEnabled = true }
        var done = false
        fun finish(status: WebGpuStatus) {
            if (done) return
            done = true
            handler.removeCallbacksAndMessages(null)
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
            onResult(status)
        }
        web.addJavascriptInterface(
            object {
                @JavascriptInterface
                fun result(json: String) {
                    handler.post { finish(parse(json)) }
                }
            },
            "AndroidProbe",
        )
        activity.addContentView(web, ViewGroup.LayoutParams(1, 1))
        web.loadDataWithBaseURL("https://${BuildConfig.GAME_HOST}/", PAGE, "text/html", "utf-8", null)
        handler.postDelayed({ finish(WebGpuStatus(error = "The check didn't answer within ${TIMEOUT_MS / 1000} seconds")) }, TIMEOUT_MS)
    }
}
