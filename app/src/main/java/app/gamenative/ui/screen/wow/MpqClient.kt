package app.gamenative.ui.screen.wow

import android.content.Context
import com.winlator.container.Container
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.InputControlsManager
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Launch support for vanilla 1.12 clients such as OctoWoW: a plain folder of MPQ archives with a
 * 32-bit x86 WoW.exe, no Blizzard CDN and no built-in gamepad mode.
 */
object MpqClient {
    private const val VANILLA_FIXES = "VanillaFixes.exe"
    private const val ADDON = "ShaguController"
    private const val PROFILE_ID = 900
    private const val PROFILE_NAME = "OctoWoW Controller"

    /**
     * VanillaFixes starts WoW.exe and injects the client mods listed in dlls.txt (nampower, UnitXP, ...),
     * which is how the client is meant to be launched. Without it, WoW.exe runs unmodded.
     */
    fun launchExe(gameRoot: File): String =
        if (File(gameRoot, VANILLA_FIXES).isFile) VANILLA_FIXES else WowClientDownloader.EXE_NAME

    /** Copies the bundled ShaguController addon into Interface/AddOns unless the player already has it. */
    fun installControllerAddon(context: Context, gameRoot: File) {
        val dest = File(gameRoot, "Interface/AddOns/$ADDON")
        if (File(dest, "$ADDON.toc").isFile) return
        runCatching { copyAssetDir(context, "addons/$ADDON", dest) }
            .onFailure { Timber.e(it, "Failed to install $ADDON") }
    }

    private fun copyAssetDir(context: Context, assetPath: String, dest: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input -> dest.outputStream().use { input.copyTo(it) } }
            return
        }
        children.forEach { copyAssetDir(context, "$assetPath/$it", File(dest, it)) }
    }

    /**
     * Gives the container a controller profile that turns gamepad input into the keyboard and mouse
     * layout ShaguController binds (the Steam Deck "Keyboard (WASD) and Mouse" template). A profile the
     * player already picked or edited is left alone.
     */
    fun applyControllerProfile(context: Context, container: Container) {
        // Load the stock profiles first: the manager only copies them into an empty profiles folder.
        InputControlsManager(context).getProfiles()
        val file = ControlsProfile.getProfileFile(context, PROFILE_ID)
        if (!file.isFile) file.writeText(controllerProfile().toString())
        if (container.getExtra("profileId", "0") == "0") {
            container.putExtra("profileId", PROFILE_ID.toString())
            container.saveData()
        }
    }

    // Android key codes for gamepad buttons, and the stick-direction pseudo codes the profiles use.
    private val CONTROLLER_BINDINGS = listOf(
        96 to "KEY_SPACE", // A: jump
        97 to "KEY_E", // B: action button 3
        99 to "KEY_R", // X: action button 2
        100 to "KEY_F", // Y: action button 1
        102 to "MOUSE_SCROLL_DOWN", // L1: previous target
        103 to "MOUSE_SCROLL_UP", // R1: next target
        104 to "MOUSE_RIGHT_BUTTON", // L2: hold to turn the camera with the right stick
        105 to "MOUSE_LEFT_BUTTON", // R2: click / interact
        106 to "KEY_1", // L3: bags and character
        107 to "KEY_2", // R3: quest log
        108 to "KEY_ESC", // Start: game menu
        109 to "KEY_TAB", // Select: world map
        110 to "OPEN_NAVIGATION_MENU",
        19 to "KEY_UP", // D-pad: action buttons 5, 10, 11, 12
        20 to "KEY_DOWN",
        21 to "KEY_LEFT",
        22 to "KEY_RIGHT",
        -3 to "KEY_W", // Left stick: move
        -4 to "KEY_S",
        -1 to "KEY_A",
        -2 to "KEY_D",
        -7 to "MOUSE_MOVE_UP", // Right stick: mouse
        -8 to "MOUSE_MOVE_DOWN",
        -5 to "MOUSE_MOVE_LEFT",
        -6 to "MOUSE_MOVE_RIGHT",
    )

    // id, name and cursorSpeed must come first: InputControlsManager reads them with a streaming parser.
    private fun controllerProfile() = JSONObject().apply {
        put("id", PROFILE_ID)
        put("name", PROFILE_NAME)
        put("cursorSpeed", 1)
        put("elements", JSONArray())
        put(
            "controllers",
            JSONArray().put(
                JSONObject().apply {
                    put("id", "*")
                    put("name", "Wildcard Controller")
                    put(
                        "controllerBindings",
                        JSONArray().apply {
                            CONTROLLER_BINDINGS.forEach { (keyCode, binding) ->
                                put(JSONObject().put("keyCode", keyCode).put("binding", binding))
                            }
                        },
                    )
                },
            ),
        )
    }

    // Written only when the player has no Config.wtf. 1.12 has no gamepad or D3D11 settings, so this
    // just sizes the window to the Android screen.
    fun ensureGameConfig(root: File, screenSize: String) {
        val configWtf = File(root, "WTF/Config.wtf")
        val resolution = "SET gxResolution \"$screenSize\""
        if (!configWtf.exists()) {
            configWtf.parentFile?.mkdirs()
            configWtf.writeText(listOf(resolution, "SET gxWindow \"1\"", "SET gxMaximize \"1\"").joinToString("\n") + "\n")
            return
        }
        val original = configWtf.readLines()
        val line = original.firstOrNull { it.startsWith("SET gxResolution ", ignoreCase = true) }
        if (line != null && fitsScreen(line.substringAfter(" ").substringAfter(" ").trim('"'), screenSize)) return
        val updated = original.filterNot { it.startsWith("SET gxResolution ", ignoreCase = true) } + resolution
        configWtf.writeText(updated.joinToString("\n") + "\n")
    }
}
