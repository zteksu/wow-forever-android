package app.azerothsurvivors

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Sets up offline play from the game's Windows download: unpacks its .rar with bsdtar (libbsdtar.so)
 * and keeps the Data folder's .MPQ archives, or copies the .MPQ files of an already unpacked one. Then it checks a few
 * files against the site, so archives from a different version of the game aren't trusted while online.
 */
object OfflineImport {
    private const val TAG = "AzerothImport"
    private const val SAMPLE = 6

    data class State(
        val running: Boolean = false,
        val progress: Float = -1f,
        val status: String = "",
        val error: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    @Volatile private var process: Process? = null
    @Volatile private var cancelled = false

    /** Where the site's download goes, so the game's server is asked for it only once. */
    const val DOWNLOAD_URL = "https://wow.aethus.pl/download/AzerothSurvivorsWindowsNew.rar"

    private fun rarFile(context: Context) = File(context.filesDir, "offline/download.rar")

    /**
     * Downloads the Windows build from the game's site into the app's own storage, carrying on from
     * where an interrupted download stopped, and unpacks it.
     */
    fun fromSite(context: Context, offline: OfflineData) = run("Downloading the Windows build...") {
        val rar = rarFile(context).apply { parentFile?.mkdirs() }
        val done = File(rar.path + ".done")
        if (!done.exists()) download(DOWNLOAD_URL, rar)
        done.createNewFile()
        // Kept until it has unpacked, so a retry doesn't download it again.
        unpack(context, offline, rar)
        rar.delete()
        done.delete()
    }

    /**
     * Unpacks the .rar the player picked. bsdtar runs as its own program and can't open files through
     * the app's file picker, so the .rar is copied into the app's storage first.
     */
    fun fromRar(context: Context, offline: OfflineData, uri: Uri) = run("Copying the Windows download...") {
        val rar = rarFile(context).apply { parentFile?.mkdirs() }
        try {
            val size = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
            ensureSpace(rar.parentFile!!, if (size > 0) size * 21 / 10 else 0)
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Can't open the file")
            input.use { src -> rar.outputStream().use { dst -> copyWithProgress(src, dst, size) } }
            unpack(context, offline, rar)
        } finally {
            rar.delete()
        }
    }

    private suspend fun unpack(context: Context, offline: OfflineData, rar: File) {
        val bsdtar = File(context.applicationInfo.nativeLibraryDir, "libbsdtar.so")
        if (!bsdtar.isFile) throw IOException("The unpacking tool isn't in this build")
        val staging = File(context.filesDir, "offline/unpacking").apply { deleteRecursively(); mkdirs() }
        try {
            val size = rar.length()
            ensureSpace(staging, size * 11 / 10)
            _state.value = State(running = true, progress = 0f, status = "Unpacking the Windows download...")
            val proc = ProcessBuilder(bsdtar.path, "-x", "-f", rar.path, "-C", staging.path).redirectErrorStream(true).start()
            process = proc
            val output = StringBuilder()
            val reader = Thread { proc.inputStream.bufferedReader().forEachLine { if (output.length < 2000) output.appendLine(it) } }.apply { start() }
            while (proc.isAlive) {
                val unpacked = staging.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                _state.value = _state.value.copy(progress = (unpacked.toFloat() / size).coerceIn(0f, 0.99f))
                delay(500)
            }
            reader.join()
            process = null
            if (proc.exitValue() != 0) throw IOException("Couldn't unpack it (exit ${proc.exitValue()}): ${output.toString().take(300)}")

            val archives = MpqOrder.archivesIn(staging)
            if (archives.isEmpty()) throw IOException("No .MPQ archives in it. Is this the Azeroth Survivors Windows download?")
            replaceArchives(offline) { dir ->
                for (file in archives) {
                    if (!file.renameTo(File(dir, file.name))) file.copyTo(File(dir, file.name), overwrite = true)
                }
            }
        } finally {
            staging.deleteRecursively()
        }
        check(offline)
    }

    private fun ensureSpace(dir: File, needed: Long) {
        val free = StatFs(dir.path).availableBytes
        if (free < needed) throw IOException("Not enough space: about ${gb(needed)} GB needed, ${gb(free)} GB free")
    }

    private suspend fun copyWithProgress(src: java.io.InputStream, dst: java.io.OutputStream, size: Long, startAt: Long = 0) {
        val buffer = ByteArray(1 shl 16)
        var copied = startAt
        var lastUpdate = 0L
        while (true) {
            if (cancelled) throw IOException("Cancelled")
            val n = src.read(buffer)
            if (n < 0) break
            dst.write(buffer, 0, n)
            copied += n
            if (size > 0 && copied - lastUpdate > (1 shl 22)) {
                lastUpdate = copied
                _state.value = _state.value.copy(progress = (copied.toFloat() / size).coerceIn(0f, 1f))
            }
        }
    }

    /** Downloads [url] into [target], resuming a partial file with a Range request when the server allows it. */
    private suspend fun download(url: String, target: File) {
        val have = if (target.isFile) target.length() else 0L
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }
        try {
            val code = connection.responseCode
            val resumed = code == 206 && have > 0
            if (code == 429) throw IOException("The game's server is busy (too many players today). Try again later.")
            if (code != 200 && !resumed) throw IOException("Download failed (HTTP $code)")
            val length = connection.contentLengthLong
            val total = if (resumed && length > 0) have + length else length
            ensureSpace(target.parentFile!!, ((total - (if (resumed) have else 0)) + total * 11 / 10).coerceAtLeast(0))
            connection.inputStream.use { src ->
                java.io.FileOutputStream(target, resumed).use { dst -> copyWithProgress(src, dst, total, if (resumed) have else 0) }
            }
            if (total > 0 && target.length() != total) throw IOException("The download stopped early; tap again to carry on")
        } finally {
            connection.disconnect()
        }
    }

    /** Copies .MPQ files the player picked (from an already unpacked download). */
    fun fromFiles(context: Context, offline: OfflineData, files: List<Pair<String, Uri>>) = run("Copying the archives...") {
        val archives = files.filter { (name, _) -> name.endsWith(".mpq", ignoreCase = true) }
        if (archives.isEmpty()) throw IOException("Pick the .MPQ files from the download's Data folder")
        replaceArchives(offline) { dir ->
            archives.forEachIndexed { i, (name, uri) ->
                _state.value = _state.value.copy(progress = i.toFloat() / archives.size, status = "Copying $name...")
                val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Can't open $name")
                input.use { src -> File(dir, File(name).name).outputStream().use { src.copyTo(it) } }
            }
        }
        check(offline)
    }

    private inline fun replaceArchives(offline: OfflineData, fill: (File) -> Unit) {
        offline.reopenArchives()
        offline.mpqDir.deleteRecursively()
        offline.mpqDir.mkdirs()
        fill(offline.mpqDir)
        offline.reopenArchives()
    }

    fun remove(offline: OfflineData) = run("Removing...") {
        offline.reopenArchives()
        offline.mpqDir.deleteRecursively()
        offline.matchesSite = true
        File(offline.mpqDir.parentFile, "download.rar").delete()
        File(offline.mpqDir.parentFile, "download.rar.done").delete()
        _state.value = State(status = "Removed the game data. The game will download what it needs again.")
    }

    /** After a game update: compares the archives with the new version's files again. */
    fun recheck(offline: OfflineData) = run("Checking the game data against the new version...") { check(offline) }

    fun cancel() {
        cancelled = true
        process?.destroy()
    }

    /** Compares a few start-up files with the site's; sets [OfflineData.matchesSite]. */
    private fun check(offline: OfflineData) {
        val mpq = offline.archives() ?: throw IOException("The archives couldn't be opened")
        val count = mpq.files.size
        _state.value = State(running = true, status = "Comparing with the site...")
        val names = runCatching { sampleNames() }.getOrNull()
        if (names == null) {
            offline.matchesSite = true
            _state.value = State(status = "Ready: $count archives. Not compared with the site (no connection); used as they are.")
            return
        }
        val compared = names.mapNotNull { name ->
            val local = mpq.read(name) ?: return@mapNotNull null
            val remote = runCatching { download(dataUrl(name)) }.getOrNull() ?: return@mapNotNull null
            local.contentEquals(remote)
        }
        val matches = compared.isEmpty() || compared.all { it }
        offline.matchesSite = matches
        _state.value = State(
            status = if (matches) {
                "Ready: $count archives, matching the site. The game loads its data from them now."
            } else {
                "Ready: $count archives, but they differ from the site's version. They're used when offline; online, the site's files are used and saved."
            },
        )
    }

    private fun sampleNames(): List<String> {
        val manifest = JSONObject(String(download(BuildConfig.GAME_URL + "boot-manifest.json")))
        val all = manifest.optJSONArray("names") ?: return emptyList()
        return (0 until all.length()).map { all.optString(it) }.filter { it.isNotEmpty() && !it.startsWith("__") }.take(SAMPLE)
    }

    private fun dataUrl(name: String) = BuildConfig.GAME_URL + "data/" + URLEncoder.encode(name, "UTF-8").replace("+", "%20")

    private fun download(url: String): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6_000
            readTimeout = 30_000
        }
        try {
            if (connection.responseCode != 200) throw IOException("HTTP ${connection.responseCode}")
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun run(status: String, block: suspend () -> Unit) {
        if (_state.value.running) return
        cancelled = false
        _state.value = State(running = true, status = status)
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                Log.w(TAG, "Offline setup failed", e)
                _state.value = State(error = if (cancelled || !isActive) "Cancelled" else e.message ?: "Failed")
            }
        }
    }

    private fun gb(bytes: Long) = "%.1f".format(bytes / 1_073_741_824.0)
}
