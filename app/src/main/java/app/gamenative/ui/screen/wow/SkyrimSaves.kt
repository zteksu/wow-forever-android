package app.gamenative.ui.screen.wow

import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Skyrim's saves in the game folder's Saves/ (see [SkyrimConfig.linkSaves]) and their backups in
 * SaveBackups/.
 *
 * A save (.ess) starts with a header naming the character, level and location, followed by an
 * uncompressed screenshot, so the launcher can show them without reading the rest of the file.
 * SKSE keeps its own data for each save in a .skse file of the same name, which travels with it.
 */
object SkyrimSaves {
    const val SAVES = "Saves"
    const val BACKUPS = "SaveBackups"
    private const val MAGIC = "TESV_SAVEGAME"
    private const val MAX_SHOT_PIXELS = 1024 * 1024

    /** Saves kept in each backup, newest first, and how many backups are kept. */
    const val SAVES_PER_BACKUP = 5
    const val BACKUPS_KEPT = 5

    private val backupTime = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    data class Save(
        val file: File,
        val name: String,
        val level: Int,
        val location: String,
        val gameDate: String,
        val saveNumber: Int,
        /** When it was saved, from the header (epoch millis), or the file's time. */
        val savedAt: Long,
        val shotWidth: Int,
        val shotHeight: Int,
        /** RGBA, [shotWidth] x [shotHeight]; null when it couldn't be read. */
        val shot: ByteArray?,
    ) {
        val kind: String
            get() = when {
                file.name.startsWith("Quicksave", ignoreCase = true) -> "Quicksave"
                file.name.startsWith("Autosave", ignoreCase = true) -> "Autosave"
                else -> "Save"
            }

        /** The screenshot as ARGB pixels for a Bitmap. */
        fun argb(): IntArray? {
            val rgba = shot ?: return null
            return IntArray(shotWidth * shotHeight) { i ->
                val p = i * 4
                (0xFF shl 24) or
                    ((rgba[p].toInt() and 0xFF) shl 16) or
                    ((rgba[p + 1].toInt() and 0xFF) shl 8) or
                    (rgba[p + 2].toInt() and 0xFF)
            }
        }
    }

    data class Backup(val file: File, val createdAt: Long, val saves: List<String>)

    fun savesDir(gameRoot: File) = File(gameRoot, SAVES)

    fun backupsDir(gameRoot: File) = File(gameRoot, BACKUPS)

    /** Reads a save's header and screenshot. */
    fun read(file: File, withShot: Boolean = true): Save = file.inputStream().buffered().use { read(it, file, withShot) }

    internal fun read(input: InputStream, file: File, withShot: Boolean): Save {
        val data = DataInputStream(input)
        val magic = ByteArray(MAGIC.length).also { data.readFully(it) }
        if (String(magic, Charsets.US_ASCII) != MAGIC) throw IOException("${file.name} isn't a Skyrim save")
        val headerSize = data.u32()
        val header = ByteArray(headerSize).also { data.readFully(it) }
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val version = buf.int
        val saveNumber = buf.int
        val name = buf.wstring()
        val level = buf.int
        val location = buf.wstring()
        val gameDate = buf.wstring()
        buf.wstring() // race
        buf.short // sex
        buf.float // current experience
        buf.float // experience to level up
        val fileTime = buf.long
        val width = buf.int
        val height = buf.int

        // Special Edition (version 12) screenshots are RGBA; Legendary Edition's were RGB.
        val bytesPerPixel = if (version >= 11) 4 else 3
        val shot = if (withShot && width > 0 && height > 0 && width.toLong() * height <= MAX_SHOT_PIXELS) {
            val raw = ByteArray(width * height * bytesPerPixel).also { data.readFully(it) }
            if (bytesPerPixel == 4) raw else ByteArray(width * height * 4) { i -> if (i % 4 == 3) -1 else raw[i / 4 * 3 + i % 4] }
        } else {
            null
        }
        return Save(
            file = file,
            name = name,
            level = level,
            location = location,
            gameDate = gameDate,
            saveNumber = saveNumber,
            savedAt = fileTimeToMillis(fileTime) ?: file.lastModified(),
            shotWidth = width,
            shotHeight = height,
            shot = shot,
        )
    }

    /** A Windows FILETIME (100 ns ticks since 1601) as epoch millis, or null if it's implausible. */
    internal fun fileTimeToMillis(fileTime: Long): Long? {
        val millis = fileTime / 10_000 - 11_644_473_600_000L
        return millis.takeIf { it > 946_684_800_000L } // after 2000
    }

    /** The saves in [gameRoot], newest file first. */
    fun saveFiles(gameRoot: File): List<File> =
        savesDir(gameRoot).listFiles { f -> f.isFile && f.name.endsWith(".ess", ignoreCase = true) }
            .orEmpty()
            .sortedByDescending { it.lastModified() }

    /** The newest [limit] saves that can be read. */
    fun list(gameRoot: File, limit: Int = Int.MAX_VALUE, withShots: Boolean = true): List<Save> =
        saveFiles(gameRoot).asSequence().mapNotNull { runCatching { read(it, withShots) }.getOrNull() }.take(limit).toList()

    fun latest(gameRoot: File): Save? = list(gameRoot, limit = 1).firstOrNull()

    private fun companions(save: File): List<File> =
        listOf(save) + File(save.parentFile, save.nameWithoutExtension + ".skse").takeIf { it.isFile }.let { listOfNotNull(it) }

    fun backups(gameRoot: File): List<Backup> =
        backupsDir(gameRoot).listFiles { f -> f.isFile && f.name.endsWith(".zip") }
            .orEmpty()
            .sortedByDescending { it.name }
            .map { zip ->
                val names = runCatching {
                    ZipInputStream(zip.inputStream().buffered()).use { input ->
                        generateSequence { input.nextEntry }.map { it.name }.filter { it.endsWith(".ess", true) }.toList()
                    }
                }.getOrDefault(emptyList())
                Backup(zip, zip.lastModified(), names)
            }

    /**
     * Zips the newest [SAVES_PER_BACKUP] saves (and their SKSE data) into SaveBackups/, keeping the
     * newest [BACKUPS_KEPT] backups. Skipped, returning null, when no save is newer than the last
     * backup, unless [force].
     */
    fun backUp(gameRoot: File, force: Boolean = false, now: Long = System.currentTimeMillis(), keep: File? = null): File? {
        val saves = saveFiles(gameRoot).take(SAVES_PER_BACKUP)
        if (saves.isEmpty()) return null
        val dir = backupsDir(gameRoot).apply { mkdirs() }
        val last = backups(gameRoot).firstOrNull()
        if (!force && last != null && saves.first().lastModified() <= last.createdAt) return null

        var zip = File(dir, "Saves-${backupTime.format(Date(now))}.zip")
        var n = 2
        while (zip.exists()) zip = File(dir, "Saves-${backupTime.format(Date(now))}-${n++}.zip")
        val partial = File(dir, zip.name + ".part")
        ZipOutputStream(partial.outputStream().buffered()).use { out ->
            // Saves are compressed already.
            out.setLevel(0)
            for (file in saves.flatMap { companions(it) }) {
                out.putNextEntry(ZipEntry(file.name).apply { time = file.lastModified() })
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        check(partial.renameTo(zip)) { "Can't finish $zip" }
        zip.setLastModified(now)
        backups(gameRoot).drop(BACKUPS_KEPT).filter { it.file != keep }.forEach { it.file.delete() }
        return zip
    }

    /**
     * Puts [backup]'s saves back into Saves/, replacing saves of the same name. The current saves are
     * backed up first, so a restore can be undone.
     */
    fun restore(gameRoot: File, backup: File) {
        val saves = savesDir(gameRoot).apply { mkdirs() }
        backUp(gameRoot, force = true, keep = backup)
        ZipInputStream(backup.inputStream().buffered()).use { input ->
            for (entry in generateSequence { input.nextEntry }) {
                // Only plain file names: never write outside Saves/.
                if (entry.isDirectory || entry.name.contains('/') || entry.name.contains('\\') || entry.name.startsWith(".")) continue
                val target = File(saves, entry.name)
                target.outputStream().use { input.copyTo(it) }
                if (entry.time > 0) target.setLastModified(entry.time)
            }
        }
    }

    private fun DataInputStream.u32(): Int {
        val b = ByteArray(4).also { readFully(it) }
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun ByteBuffer.wstring(): String {
        val length = short.toInt() and 0xFFFF
        val bytes = ByteArray(length).also { get(it) }
        return String(bytes, charset("windows-1252"))
    }
}
