package app.azerothsurvivors

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * Lets the game run offline. The page asks the site for its data one file at a time (`/data/<name>`,
 * with names like `DBFilesClient\Spell.dbc`), which the site pulls out of the game's MPQ archives. The
 * app answers those requests itself:
 *
 *  1. from its own saved copy of anything the site sent before (kept for good, unlike the browser's
 *     cache, which the site limits to a day);
 *  2. from the MPQ archives of the Windows download, once they're imported, when they match the site;
 *  3. from the site, saving what comes back.
 *
 * The page itself (HTML, scripts, the WebAssembly client) goes to the site first so updates arrive, and
 * falls back to the saved copy when there's no connection.
 */
class OfflineData private constructor(context: Context) {
    private val root = File(context.filesDir, "offline")
    private val saved = File(root, "saved")
    val mpqDir = File(root, "mpq")
    private val prefs = context.getSharedPreferences("offline", Context.MODE_PRIVATE)

    @Volatile private var mpq: Mpq? = null

    init {
        saved.mkdirs()
    }

    /** Whether the archives' files matched the site's when checked; if not, they're only used offline. */
    var matchesSite: Boolean
        get() = prefs.getBoolean(KEY_MATCHES, true)
        set(value) = prefs.edit().putBoolean(KEY_MATCHES, value).apply()

    fun archiveDir(): File? = mpqDir.takeIf { MpqOrder.archivesIn(it).isNotEmpty() }

    fun archiveBytes(): Long = MpqOrder.archivesIn(mpqDir).sumOf { it.length() }

    @Synchronized
    fun archives(): Mpq? {
        mpq?.let { return it }
        val dir = archiveDir() ?: return null
        return runCatching { Mpq.open(dir) }.onFailure { Log.w(TAG, "Couldn't open the MPQs", it) }.getOrNull()?.also { mpq = it }
    }

    @Synchronized
    fun reopenArchives() {
        mpq?.close()
        mpq = null
    }

    fun savedBytes(): Long = saved.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun clearSaved() {
        saved.deleteRecursively()
        saved.mkdirs()
    }

    /** The WebViewClient hook: an answer for the game site's GET requests, or null to let WebView fetch. */
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        if (request.method != "GET" || url.host != BuildConfig.GAME_HOST) return null
        val path = url.encodedPath ?: return null
        if (path.startsWith("/download")) return null
        return try {
            if (path.startsWith(DATA)) data(url, path) else page(url, path)
        } catch (e: Exception) {
            Log.w(TAG, "Offline lookup failed for $url", e)
            null
        }
    }

    private fun data(url: Uri, path: String): WebResourceResponse? {
        val key = keyFor(url)
        load(key)?.let { return it }
        val name = dataName(path)
        val mpqFirst = matchesSite
        if (mpqFirst && name != null) fromArchives(name)?.let { return it }
        fetch(url, key)?.let { return it }
        if (!mpqFirst && name != null) fromArchives(name)?.let { return it }
        return null
    }

    private fun page(url: Uri, path: String): WebResourceResponse? {
        val key = keyFor(url)
        val fetched = fetchRaw(url) ?: return load(key)
        if (fetched.code == 200) {
            save(key, fetched.type, fetched.bytes)
            if (isDocument(path)) buildStamp(String(fetched.bytes))?.let { onBuild(it, key, fetched) }
        }
        return fetched.toResponse()
    }

    /**
     * The page names its scripts with a build stamp (`rogue.js?v=<stamp>`) that changes with each release.
     * When it changes, the saved copies are from the old version: they're dropped so the new files are
     * fetched, and the imported archives are checked against the site again (used only offline until then).
     */
    @Synchronized
    private fun onBuild(stamp: String, documentKey: String, document: Fetched) {
        val last = prefs.getString(KEY_BUILD, null)
        prefs.edit().putString(KEY_BUILD, stamp).apply()
        if (last == null || last == stamp) return
        Log.i(TAG, "New game version $stamp (was $last): dropping saved files")
        clearSaved()
        save(documentKey, document.type, document.bytes)
        if (archiveDir() != null) {
            matchesSite = false
            OfflineImport.recheck(this)
        }
    }

    private fun fromArchives(name: String): WebResourceResponse? {
        val bytes = archives()?.read(name) ?: return null
        // The page reads data files as raw bytes, as the site serves them.
        return response("application/octet-stream", bytes)
    }

    private fun load(key: String): WebResourceResponse? {
        val body = File(saved, key)
        if (!body.isFile) return null
        val type = File(saved, "$key.type").takeIf { it.isFile }?.readText() ?: "application/octet-stream"
        return response(type, body.readBytes())
    }

    private class Fetched(val code: Int, val reason: String, val type: String, val bytes: ByteArray)

    private fun Fetched.toResponse() = response(type, bytes, code, reason)

    /** Fetches from the site and saves a successful answer; other answers (like "too busy") pass through. */
    private fun fetch(url: Uri, key: String): WebResourceResponse? {
        val fetched = fetchRaw(url) ?: return null
        if (fetched.code == 200) save(key, fetched.type, fetched.bytes)
        return fetched.toResponse()
    }

    /** The site's answer, or null with no connection (the caller then tries the saved copy or the archives). */
    private fun fetchRaw(url: Uri): Fetched? {
        val connection = (URL(url.toString()).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
        return try {
            val code = connection.responseCode
            val type = connection.contentType?.substringBefore(';')?.trim()?.ifEmpty { null } ?: mimeFor(url.path.orEmpty())
            val stream = if (code == 200) connection.inputStream else connection.errorStream
            Fetched(code, connection.responseMessage ?: if (code == 200) "OK" else "Error", type, stream?.use { it.readBytes() } ?: ByteArray(0))
        } catch (e: IOException) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun save(key: String, type: String, bytes: ByteArray) {
        val tmp = File(saved, "$key.part")
        tmp.writeBytes(bytes)
        File(saved, "$key.type").writeText(type)
        tmp.renameTo(File(saved, key))
    }

    private fun response(type: String, bytes: ByteArray, code: Int = 200, reason: String = "OK") =
        WebResourceResponse(type, if (type.startsWith("text/") || type.endsWith("javascript") || type.endsWith("json")) "utf-8" else null, code, reason, HEADERS, ByteArrayInputStream(bytes))

    companion object {
        @Volatile private var instance: OfflineData? = null

        fun get(context: Context): OfflineData =
            instance ?: synchronized(this) { instance ?: OfflineData(context.applicationContext).also { instance = it } }

        private const val TAG = "AzerothOffline"
        private const val DATA = "/data/"
        private const val KEY_MATCHES = "matches_site"
        private const val KEY_BUILD = "build"
        private val STAMP = Regex("""[?&]v=([0-9A-Za-z_-]{6,})""")

        internal fun isDocument(path: String) = path == "/" || path.isEmpty() || path.endsWith(".html")

        /** The build stamp in the page's script and style links, or null if it has none. */
        internal fun buildStamp(html: String): String? = STAMP.find(html)?.groupValues?.get(1)
        private const val CONNECT_TIMEOUT_MS = 6_000
        private const val READ_TIMEOUT_MS = 60_000
        private val HEADERS = mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store")

        /** A file name for a URL's path and query, which together name one version of a file. */
        internal fun keyFor(url: Uri): String = keyFor(url.encodedPath.orEmpty(), url.encodedQuery)

        internal fun keyFor(path: String, query: String?): String {
            val digest = MessageDigest.getInstance("SHA-1").digest("$path?${query.orEmpty()}".toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }

        /** The in-archive name of a `/data/<encoded name>` path, or null for the site's own index files. */
        internal fun dataName(encodedPath: String): String? {
            // The page builds these with encodeURIComponent, which never leaves a bare '+'.
            val name = URLDecoder.decode(encodedPath.removePrefix(DATA), "UTF-8")
            return name.takeIf { it.isNotEmpty() && !it.startsWith("__") }
        }

        /** Content types the browser insists on: modules must be JavaScript, streamed WebAssembly application/wasm. */
        internal fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html"
            "js", "mjs" -> "text/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            "wasm" -> "application/wasm"
            "png" -> "image/png"
            "svg" -> "image/svg+xml"
            "lua", "xml", "toc", "txt" -> "text/plain"
            else -> "application/octet-stream"
        }
    }
}
