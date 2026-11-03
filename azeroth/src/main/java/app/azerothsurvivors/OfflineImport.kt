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

    /**
     * Unpacks the .rar the player picked. The app has no storage permission, so bsdtar reads it through
     * this process's open file (/proc/<pid>/fd/<n>), which it can open as the same app.
     */
    fun fromRar(context: Context, offline: OfflineData, rar: Uri) = run("Unpacking the Windows download...") {
        val bsdtar = File(context.applicationInfo.nativeLibraryDir, "libbsdtar.so")
        if (!bsdtar.isFile) throw IOException("The unpacking tool isn't in this build")
        val staging = File(context.filesDir, "offline/unpacking").apply { deleteRecursively(); mkdirs() }
        val pfd = context.contentResolver.openFileDescriptor(rar, "r") ?: throw IOException("Can't open the file")
        try {
            val size = pfd.statSize.takeIf { it > 0 } ?: throw IOException("Can't read the file's size")
            val free = StatFs(staging.path).availableBytes
            if (free < size * 11 / 10) {
                throw IOException("Not enough space: about ${gb(size * 11 / 10)} GB needed, ${gb(free)} GB free")
            }
            val source = "/proc/${android.os.Process.myPid()}/fd/${pfd.fd}"
            val proc = ProcessBuilder(bsdtar.path, "-x", "-f", source, "-C", staging.path).redirectErrorStream(true).start()
            process = proc
            val output = StringBuilder()
            val reader = Thread { proc.inputStream.bufferedReader().forEachLine { if (output.length < 2000) output.appendLine(it) } }.apply { start() }
            while (proc.isAlive) {
                val done = staging.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                _state.value = _state.value.copy(progress = (done.toFloat() / size).coerceIn(0f, 0.99f))
                delay(500)
            }
            reader.join()
            process = null
            if (proc.exitValue() != 0) throw IOException("Couldn't unpack it (exit ${proc.exitValue()}): ${output.toString().take(300)}")

            val archives = MpqOrder.archivesIn(staging)
            if (archives.isEmpty()) throw IOException("No Data/*.MPQ in it. Is this the Azeroth Survivors Windows download?")
            replaceArchives(offline) { dir ->
                for (file in archives) {
                    if (!file.renameTo(File(dir, file.name))) file.copyTo(File(dir, file.name), overwrite = true)
                }
            }
        } finally {
            pfd.close()
            staging.deleteRecursively()
        }
        check(offline)
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
        _state.value = State(status = "Removed the game data. The game will download what it needs again.")
    }

    fun cancel() {
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
        _state.value = State(running = true, status = status)
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                Log.w(TAG, "Offline setup failed", e)
                _state.value = State(error = if (isActive) e.message ?: "Failed" else "Cancelled")
            }
        }
    }

    private fun gb(bytes: Long) = "%.1f".format(bytes / 1_073_741_824.0)
}
