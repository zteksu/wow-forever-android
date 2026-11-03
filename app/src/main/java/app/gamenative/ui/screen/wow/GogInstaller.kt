package app.gamenative.ui.screen.wow

import android.content.Context
import android.os.StatFs
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Installs a game from GOG's offline installer (setup_*.exe plus setup_*-N.bin parts) by unpacking it
 * with innoextract, built for Android and shipped as libinnoextract.so, instead of running the Windows
 * installer under emulation. The installer's own extras (redistributables, temporary files) are left out.
 *
 * It unpacks into a hidden staging folder next to the destination, finds the folder holding the game's
 * executable inside it, and moves that into place, so the result doesn't depend on how the installer
 * names its folders. Runs in its own scope so leaving the screen doesn't stop it.
 */
object GogInstaller {
    private const val BINARY = "libinnoextract.so"

    /** Space wanted beyond the installer's own size; games rarely unpack to less than their installer. */
    internal const val SPACE_FACTOR = 1.15

    data class State(
        val running: Boolean = false,
        val progress: Float = 0f,
        val status: String = "",
        val error: String? = null,
        val installed: File? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private var job: Job? = null
    @Volatile private var process: Process? = null

    fun isAvailable(context: Context) = binary(context).isFile

    private fun binary(context: Context) = File(context.applicationInfo.nativeLibraryDir, BINARY)

    /** The installer and its .bin parts: GOG names them setup_x.exe, setup_x-1.bin, setup_x-2.bin, ... */
    internal fun parts(exe: File): List<File> {
        val base = exe.nameWithoutExtension
        val bins = exe.parentFile?.listFiles { f ->
            f.isFile && f.name.startsWith("$base-", ignoreCase = true) && f.name.endsWith(".bin", ignoreCase = true)
        }.orEmpty().sortedBy { it.name.substringAfterLast('-').substringBefore('.').toIntOrNull() ?: Int.MAX_VALUE }
        return listOf(exe) + bins
    }

    internal fun requiredBytes(parts: List<File>): Long = (parts.sumOf { it.length() } * SPACE_FACTOR).toLong()

    private val PERCENT = Regex("""(\d{1,3}(?:\.\d+)?)%""")

    /** The progress (0..1) in a line of innoextract's progress bar, if it has one. */
    internal fun parseProgress(line: String): Float? =
        PERCENT.findAll(line).lastOrNull()?.groupValues?.get(1)?.toFloatOrNull()?.takeIf { it in 0f..100f }?.div(100f)

    /** The folder in [staging] that holds [marker] (a path like "Binaries/NMS.exe"), searched a few levels deep. */
    internal fun findGameRoot(staging: File, marker: String): File? {
        var level = listOf(staging)
        repeat(4) {
            level.firstOrNull { File(it, marker).isFile }?.let { return it }
            level = level.flatMap { dir -> dir.listFiles { f -> f.isDirectory }.orEmpty().toList() }
        }
        return null
    }

    /** Where the game goes: [chosen] itself when it's empty or already named for the game, else a folder in it. */
    internal fun destination(chosen: File, folderName: String): File =
        if (chosen.name.equals(folderName, ignoreCase = true) || chosen.listFiles().isNullOrEmpty()) chosen else File(chosen, folderName)

    /**
     * Moves the unpacked game from [root] into [target], which must be empty or missing. Both are on the
     * same storage, so this is a rename per entry, not a copy.
     */
    internal fun moveInto(root: File, target: File) {
        target.mkdirs()
        check(target.listFiles().isNullOrEmpty()) { "$target isn't empty" }
        for (entry in root.listFiles().orEmpty()) {
            val dest = File(target, entry.name)
            if (!entry.renameTo(dest)) {
                // Some storage can't rename across folders; fall back to copying.
                entry.copyRecursively(dest, overwrite = true)
                entry.deleteRecursively()
            }
        }
    }

    fun install(context: Context, exe: File, chosenDir: File, folderName: String, marker: String) {
        if (_state.value.running) return
        val bin = binary(context)
        job = scope.launch {
            val target = destination(chosenDir, folderName)
            val staging = File(target.parentFile ?: chosenDir, ".installing-${System.currentTimeMillis()}")
            try {
                _state.value = State(running = true, status = "Checking the installer...")
                if (!bin.isFile) throw IOException("The installer tool isn't in this build")
                if (!exe.isFile) throw IOException("Can't read ${exe.path}")
                if (File(target, marker).isFile) throw IOException("${target.path} already has the game")
                if (!target.listFiles().isNullOrEmpty()) throw IOException("${target.path} isn't empty; pick an empty folder")
                val parts = parts(exe)
                staging.mkdirs()
                val free = StatFs(staging.path).availableBytes
                val needed = requiredBytes(parts)
                if (free < needed) {
                    throw IOException("Not enough space: about ${gb(needed)} GB needed, ${gb(free)} GB free")
                }

                _state.value = State(running = true, status = "Unpacking ${parts.size} installer file(s)...")
                val command = listOf(bin.path, "--extract", "--exclude-temp", "--progress=1", "--color=0", "--output-dir", staging.path, exe.path)
                val proc = ProcessBuilder(command).directory(exe.parentFile).redirectErrorStream(true).start()
                process = proc
                val tail = ArrayDeque<String>()
                val buffer = StringBuilder()
                proc.inputStream.bufferedReader().use { reader ->
                    while (isActive) {
                        val c = reader.read()
                        if (c < 0) break
                        if (c == '\r'.code || c == '\n'.code) {
                            val line = buffer.toString().trim()
                            buffer.setLength(0)
                            if (line.isEmpty()) continue
                            val progress = parseProgress(line)
                            if (progress != null) {
                                _state.value = _state.value.copy(progress = progress, status = "Unpacking... ${(progress * 100).toInt()}%")
                            } else {
                                tail.addLast(line)
                                if (tail.size > 8) tail.removeFirst()
                            }
                        } else {
                            buffer.append(c.toChar())
                        }
                    }
                }
                val exit = proc.waitFor()
                process = null
                if (exit != 0) throw IOException("The installer couldn't be unpacked (exit $exit): ${tail.joinToString(" ").take(400)}")

                _state.value = _state.value.copy(progress = 1f, status = "Moving the game into place...")
                val root = findGameRoot(staging, marker)
                    ?: throw IOException("Unpacked, but $marker isn't in it. Is this the right game's installer?")
                moveInto(root, target)
                _state.value = State(status = "Installed to ${target.path}", installed = target)
            } catch (e: Exception) {
                Timber.w(e, "GOG install failed")
                process?.destroy()
                process = null
                _state.value = State(error = if (isActive) e.message ?: "Install failed" else "Cancelled")
            } finally {
                staging.deleteRecursively()
            }
        }
    }

    fun cancel() {
        process?.destroy()
        job?.cancel()
    }

    /** Forgets a finished or failed install so the screen starts fresh. */
    fun reset() {
        if (!_state.value.running) _state.value = State()
    }

    private fun gb(bytes: Long) = "%.1f".format(bytes / 1_073_741_824.0)
}
