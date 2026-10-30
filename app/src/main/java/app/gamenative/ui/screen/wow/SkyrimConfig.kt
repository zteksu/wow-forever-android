package app.gamenative.ui.screen.wow

import java.io.File
import java.nio.file.Files

/**
 * Settings for Skyrim Special Edition, which keeps them in Documents/My Games in the Wine prefix rather
 * than in the game folder. The GOG release uses its own "... GOG" folder there.
 *
 * Handheld defaults are written only when there is no SkyrimPrefs.ini, so in-game changes stick. The
 * resolution always follows the container's screen size, which the launcher's Graphics options pick,
 * since Skyrim otherwise opens at whatever its default is and gets stretched or cropped.
 *
 * Under x64 emulation Skyrim is held back by the CPU, mostly by draw calls. [PERFORMANCE] (the
 * launcher's Medium preset) turns down the settings that add the most of them (shadows, distant
 * objects, reflections) and a few costly post effects. It is applied once per [PERFORMANCE_VERSION],
 * so a setting the player changes later stays changed.
 */
object SkyrimConfig {
    internal const val GAME_FOLDER = "Skyrim Special Edition GOG"
    private const val PREFS = "SkyrimPrefs.ini"
    private const val SAVES = "Saves"
    internal const val PERFORMANCE_VERSION = 2

    /** The handheld defaults: the launcher's Medium preset. */
    internal val PERFORMANCE: Map<Pair<String, String>, String>
        get() = SkyrimGraphics.values(SkyrimGraphics.PRESETS.getValue(SkyrimGraphics.DEFAULT_PRESET))

    fun prefsFile(documentsDir: File) = File(documentsDir, "My Games/$GAME_FOLDER/$PREFS")

    fun isTuned(prefs: File) = File(prefs.parentFile, ".performance-v$PERFORMANCE_VERSION").exists()

    /** Marks [prefs] as tuned, so [ensure] won't apply the defaults over the player's choices. */
    fun markTuned(prefs: File) {
        File(prefs.parentFile, ".performance-v$PERFORMANCE_VERSION").createNewFile()
    }

    /**
     * [pending] holds settings saved in the launcher before the prefix existed; they replace the
     * prefix's file once it does.
     */
    fun ensure(documentsDir: File, screenSize: String, pending: File? = null) {
        val prefs = prefsFile(documentsDir)
        val tuned = File(prefs.parentFile, ".performance-v$PERFORMANCE_VERSION")
        if (pending != null && pending.isFile) {
            prefs.parentFile?.mkdirs()
            pending.copyTo(prefs, overwrite = true)
            pending.delete()
            markTuned(prefs)
        }
        if (!prefs.isFile) {
            prefs.parentFile?.mkdirs()
            prefs.writeText(defaultPrefs(screenSize))
            markTuned(prefs)
            return
        }
        val original = prefs.readText()
        var updated = setResolution(original, screenSize)
        if (!tuned.exists()) updated = applyPerformance(updated)
        if (updated != original) prefs.writeText(updated)
        markTuned(prefs)
    }

    /**
     * Makes the prefix's Saves folder a link to `Saves` in [gameRoot], so saves are kept with the game
     * files. Saves already in the prefix are copied over first (an existing save of the same name in the
     * game folder wins) and the old folder is kept as `Saves.old`.
     */
    fun linkSaves(documentsDir: File, gameRoot: File) {
        val target = File(gameRoot, SAVES)
        val link = File(documentsDir, "My Games/$GAME_FOLDER/$SAVES").toPath()
        target.mkdirs()
        check(target.isDirectory) { "Can't create $target" }
        if (Files.isSymbolicLink(link)) {
            if (Files.readSymbolicLink(link) == target.toPath()) return
            Files.delete(link)
        } else if (Files.isDirectory(link)) {
            link.toFile().listFiles()?.forEach { save ->
                val copy = File(target, save.name)
                if (!copy.exists()) save.copyRecursively(copy)
            }
            val old = File(link.parent.toFile(), "$SAVES.old")
            old.deleteRecursively()
            check(link.toFile().renameTo(old)) { "Can't move the old Saves folder aside" }
        }
        Files.createDirectories(link.parent)
        Files.createSymbolicLink(link, target.toPath())
    }

    internal fun defaultPrefs(screenSize: String): String {
        val (width, height) = screenSize.split("x")
        val base = """
            [Display]
            iSize W=$width
            iSize H=$height
            bFull Screen=1
            bBorderless=1
            iVSyncPresentInterval=1
            iMaxAnisotropy=4
            fGamma=1.0000

            [Controls]
            bGamepadEnable=1
            bGamePadRumble=1

            [Launcher]
            bEnableFileSelection=1
        """.trimIndent().replace("\n", "\r\n") + "\r\n"
        return applyPerformance(base)
    }

    internal fun applyPerformance(ini: String): String =
        PERFORMANCE.entries.fold(ini) { text, (key, value) -> setValue(text, key.first, key.second, value) }

    /** Sets [key] in [section], replacing it if it's there and adding it (and the section) if not. */
    internal fun setValue(ini: String, section: String, key: String, value: String): String {
        val newline = if ("\r\n" in ini) "\r\n" else "\n"
        val lines = ini.split(newline).toMutableList()
        val header = lines.indexOfFirst { it.trim().equals("[$section]", ignoreCase = true) }
        if (header < 0) {
            if (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.size - 1)
            if (lines.isNotEmpty() && lines.last().isNotBlank()) lines += ""
            lines += listOf("[$section]", "$key=$value", "")
            return lines.joinToString(newline)
        }
        var end = header + 1
        while (end < lines.size && !lines[end].trim().startsWith("[")) end++
        val existing = (header + 1 until end).firstOrNull { lines[it].substringBefore('=').trim().equals(key, ignoreCase = true) }
        if (existing != null) {
            lines[existing] = "$key=$value"
        } else {
            // Before the blank lines that separate this section from the next.
            var insert = end
            while (insert > header + 1 && lines[insert - 1].isBlank()) insert--
            lines.add(insert, "$key=$value")
        }
        return lines.joinToString(newline)
    }

    /** Sets iSize W/H to [screenSize]; Skyrim has no resolution setting in game, only in its launcher. */
    internal fun setResolution(ini: String, screenSize: String): String {
        val (screenWidth, screenHeight) = screenSize.split("x").map { it.toInt() }
        val width = Regex("(?im)^iSize W=(\\d+)").find(ini)?.groupValues?.get(1)?.toIntOrNull()
        val height = Regex("(?im)^iSize H=(\\d+)").find(ini)?.groupValues?.get(1)?.toIntOrNull()
        if (width == screenWidth && height == screenHeight) return ini

        val newline = if ("\r\n" in ini) "\r\n" else "\n"
        val lines = ini.split(newline).filterNot { it.startsWith("iSize W=", true) || it.startsWith("iSize H=", true) }.toMutableList()
        val size = listOf("iSize W=$screenWidth", "iSize H=$screenHeight")
        val display = lines.indexOfFirst { it.trim().equals("[Display]", ignoreCase = true) }
        if (display >= 0) {
            lines.addAll(display + 1, size)
        } else {
            if (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.size - 1)
            lines += listOf("[Display]") + size + ""
        }
        return lines.joinToString(newline)
    }
}
