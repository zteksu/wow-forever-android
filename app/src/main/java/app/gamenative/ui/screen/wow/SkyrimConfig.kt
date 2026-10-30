package app.gamenative.ui.screen.wow

import java.io.File
import java.nio.file.Files

/**
 * Settings for Skyrim Special Edition, which keeps them in Documents/My Games in the Wine prefix rather
 * than in the game folder. The GOG release uses its own "... GOG" folder there.
 *
 * Handheld defaults are written only when there is no SkyrimPrefs.ini, so in-game changes stick. The
 * resolution is reset when it no longer fits the screen (for example after the container's screen size
 * changes), since Skyrim otherwise opens at whatever its default is and gets stretched or cropped.
 *
 * Under x64 emulation Skyrim is held back by the CPU, mostly by draw calls. [PERFORMANCE] turns down
 * the settings that add the most of them (shadows, distant objects, reflections) and a few costly
 * post effects. It is applied once per [PERFORMANCE_VERSION], so a setting the player changes back
 * in game stays changed.
 */
object SkyrimConfig {
    internal const val GAME_FOLDER = "Skyrim Special Edition GOG"
    private const val PREFS = "SkyrimPrefs.ini"
    private const val SAVES = "Saves"
    internal const val PERFORMANCE_VERSION = 1

    internal val PERFORMANCE: Map<String, Map<String, String>> = linkedMapOf(
        "Display" to linkedMapOf(
            "fShadowDistance" to "2000.0000",
            "fInteriorShadowDistance" to "2000.0000",
            "iShadowMapResolution" to "1024",
            "bTreesReceiveShadows" to "0",
            "bDrawLandShadows" to "0",
            "bShadowsOnGrass" to "0",
            "iBlurDeferredShadowMask" to "1",
            "bVolumetricLightingEnable" to "0",
            "bScreenSpaceReflectionEnabled" to "0",
            "bSAOEnable" to "0",
            "bIBLFEnable" to "0",
            "bEnableImprovedSnow" to "0",
        ),
        "Imagespace" to linkedMapOf("bDoDepthOfField" to "0", "bLensFlare" to "0"),
        "Grass" to linkedMapOf("iMinGrassSize" to "60", "fGrassStartFadeDistance" to "3000.0000"),
        "Particles" to linkedMapOf("iMaxDesired" to "750"),
        "Water" to linkedMapOf("bReflectLODObjects" to "0", "bReflectLODTrees" to "0"),
        "LOD" to linkedMapOf(
            "fLODFadeOutMultObjects" to "7.0000",
            "fLODFadeOutMultItems" to "3.0000",
            "fLODFadeOutMultActors" to "8.0000",
        ),
    )

    fun ensure(documentsDir: File, screenSize: String) {
        val prefs = File(documentsDir, "My Games/$GAME_FOLDER/$PREFS")
        val tuned = File(prefs.parentFile, ".performance-v$PERFORMANCE_VERSION")
        if (!prefs.isFile) {
            prefs.parentFile?.mkdirs()
            prefs.writeText(defaultPrefs(screenSize))
            tuned.createNewFile()
            return
        }
        val original = prefs.readText()
        var updated = fitResolution(original, screenSize)
        if (!tuned.exists()) updated = applyPerformance(updated)
        if (updated != original) prefs.writeText(updated)
        tuned.createNewFile()
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
        PERFORMANCE.entries.fold(ini) { text, (section, values) ->
            values.entries.fold(text) { acc, (key, value) -> setValue(acc, section, key, value) }
        }

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

    /** Rewrites iSize W/H when they're missing or bigger than [screenSize]. */
    internal fun fitResolution(ini: String, screenSize: String): String {
        val (screenWidth, screenHeight) = screenSize.split("x").map { it.toInt() }
        val width = Regex("(?im)^iSize W=(\\d+)").find(ini)?.groupValues?.get(1)?.toIntOrNull()
        val height = Regex("(?im)^iSize H=(\\d+)").find(ini)?.groupValues?.get(1)?.toIntOrNull()
        if (width != null && height != null && width in 1..screenWidth && height in 1..screenHeight) return ini

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
