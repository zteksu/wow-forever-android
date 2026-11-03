package app.azerothsurvivors

import java.io.Closeable
import java.io.File

/** StormLib, through libazerothmpq.so (tools/azeroth/mpq_jni.cpp). */
object MpqNative {
    init {
        System.loadLibrary("azerothmpq")
    }

    @JvmStatic external fun nativeOpen(path: String): Long

    @JvmStatic external fun nativeRead(handle: Long, name: String): ByteArray?

    @JvmStatic external fun nativeClose(handle: Long)
}

/** The order the game searches its archives: the newest patch first, so patch-7 overrides patch-6, and so on. */
object MpqOrder {
    private val PATCH = Regex("""patch(?:-(\d+))?\.mpq""", RegexOption.IGNORE_CASE)

    fun isArchive(file: File) = file.isFile && file.name.endsWith(".mpq", ignoreCase = true)

    fun sort(files: List<File>): List<File> = files.sortedWith(
        compareByDescending<File> { PATCH.matchEntire(it.name) != null }
            .thenByDescending { PATCH.matchEntire(it.name)?.groupValues?.get(1)?.toIntOrNull() ?: 1 }
            .thenBy { it.name.lowercase() },
    )

    /** The MPQ archives in [dir] or its Data/ folder, in search order. */
    fun archivesIn(dir: File): List<File> {
        val data = listOf(dir, File(dir, "Data"), File(dir, "AzerothSurvivors/Data")).firstOrNull { d ->
            d.listFiles()?.any(::isArchive) == true
        } ?: return emptyList()
        return sort(data.listFiles().orEmpty().filter(::isArchive))
    }
}

/** The game's MPQ archives, read-only. A file comes from the first archive (in [MpqOrder]) that has it. */
class Mpq private constructor(private val archives: List<Pair<File, Long>>) : Closeable {
    val files: List<File> get() = archives.map { it.first }

    /** The file's bytes, by its in-archive name like `DBFilesClient\Spell.dbc`, or null if no archive has it. */
    @Synchronized
    fun read(name: String): ByteArray? {
        for ((_, handle) in archives) {
            MpqNative.nativeRead(handle, name)?.let { return it }
        }
        return null
    }

    @Synchronized
    override fun close() {
        archives.forEach { MpqNative.nativeClose(it.second) }
    }

    companion object {
        /** Opens the archives in [dir]; null if there are none or none can be opened. */
        fun open(dir: File): Mpq? {
            val opened = MpqOrder.archivesIn(dir).mapNotNull { file ->
                MpqNative.nativeOpen(file.path).takeIf { it != 0L }?.let { file to it }
            }
            return if (opened.isEmpty()) null else Mpq(opened)
        }
    }
}
