package app.gamenative.ui.screen.wow

import java.io.File

/**
 * Settings for Skyrim Special Edition, which keeps them in Documents/My Games in the Wine prefix rather
 * than in the game folder. The GOG release uses its own "... GOG" folder there.
 *
 * Handheld defaults are written only when there is no SkyrimPrefs.ini, so in-game changes stick. The
 * resolution is reset when it no longer fits the screen (for example after the container's screen size
 * changes), since Skyrim otherwise opens at whatever its default is and gets stretched or cropped.
 */
object SkyrimConfig {
    internal const val GAME_FOLDER = "Skyrim Special Edition GOG"
    private const val PREFS = "SkyrimPrefs.ini"

    fun ensure(documentsDir: File, screenSize: String) {
        val prefs = File(documentsDir, "My Games/$GAME_FOLDER/$PREFS")
        if (!prefs.isFile) {
            prefs.parentFile?.mkdirs()
            prefs.writeText(defaultPrefs(screenSize))
            return
        }
        val original = prefs.readText()
        val updated = fitResolution(original, screenSize)
        if (updated != original) prefs.writeText(updated)
    }

    internal fun defaultPrefs(screenSize: String): String {
        val (width, height) = screenSize.split("x")
        return """
            [Display]
            iSize W=$width
            iSize H=$height
            bFull Screen=1
            bBorderless=1
            iVSyncPresentInterval=1
            iMaxAnisotropy=4
            iShadowMapResolution=1024
            fShadowDistance=2500.0000
            fInteriorShadowDistance=2000.0000
            bSAOEnable=0
            fGamma=1.0000

            [Grass]
            iMinGrassSize=60

            [Controls]
            bGamepadEnable=1
            bGamePadRumble=1

            [Launcher]
            bEnableFileSelection=1
        """.trimIndent().replace("\n", "\r\n") + "\r\n"
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
