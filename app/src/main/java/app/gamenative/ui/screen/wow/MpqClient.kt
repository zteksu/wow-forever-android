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
    private const val PREFS = "octo_client"
    private const val KEY_LAYOUT = "controller_layout"

    /** The controller addon in use, each with its own key layout and controller profile. */
    enum class ControllerLayout(val title: String, val addonFolder: String, val profileId: Int, val profileName: String) {
        SHAGU("ShaguController", ADDON, 900, "OctoWoW Controller"),
        CONSOLE_EXPERIENCE("ConsoleExperience", "ConsoleExperienceClassic", 901, "OctoWoW ConsoleExperience"),
    }

    fun controllerLayout(context: Context): ControllerLayout =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAYOUT, null)
            ?.let { name -> ControllerLayout.entries.firstOrNull { it.name == name } } ?: ControllerLayout.SHAGU

    fun setControllerLayout(context: Context, layout: ControllerLayout) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LAYOUT, layout.name).apply()
    }

    /**
     * VanillaFixes starts WoW.exe and injects the client mods listed in dlls.txt (nampower, UnitXP, ...),
     * which is how the client is meant to be launched. Without it, WoW.exe runs unmodded.
     */
    fun launchExe(gameRoot: File): String =
        if (File(gameRoot, VANILLA_FIXES).isFile) VANILLA_FIXES else WowClientDownloader.EXE_NAME

    /**
     * Copies the bundled ShaguController addon into Interface/AddOns unless the player already has it, or
     * has switched to ConsoleExperience in the Add-ons menu.
     */
    fun installControllerAddon(context: Context, gameRoot: File) {
        if (controllerLayout(context) == ControllerLayout.SHAGU) installBundledShagu(context, gameRoot)
    }

    internal fun installBundledShagu(context: Context, gameRoot: File) {
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
     * Gives the container the controller profile for the current layout, turning gamepad input into the
     * keys its addon binds. Each layout has its own profile, so edits the player makes to one survive
     * switching; a profile the player picked themselves is left alone.
     */
    fun applyControllerProfile(context: Context, container: Container) {
        // Load the stock profiles first: the manager only copies them into an empty profiles folder.
        InputControlsManager(context).getProfiles()
        val layout = controllerLayout(context)
        val file = ControlsProfile.getProfileFile(context, layout.profileId)
        if (!file.isFile) file.writeText(controllerProfile(layout).toString())
        val ours = setOf("0") + ControllerLayout.entries.map { it.profileId.toString() }
        if (container.getExtra("profileId", "0") in ours && container.getExtra("profileId", "0") != layout.profileId.toString()) {
            container.putExtra("profileId", layout.profileId.toString())
            container.saveData()
        }
    }

    private fun bindings(layout: ControllerLayout) = when (layout) {
        ControllerLayout.SHAGU -> SHAGU_BINDINGS
        ControllerLayout.CONSOLE_EXPERIENCE -> CONSOLE_EXPERIENCE_BINDINGS
    }

    // Android key codes for gamepad buttons, and the stick-direction pseudo codes the profiles use.
    private val SHAGU_BINDINGS = listOf<Pair<Int, Any>>(
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

    // ConsoleExperience's documented layout: face buttons, D-pad and bumpers are 1-0, and the triggers are
    // held Shift and Ctrl to reach its other three bars. Start is Shift+Escape, its radial menu.
    private val CONSOLE_EXPERIENCE_BINDINGS = listOf<Pair<Int, Any>>(
        96 to "KEY_1", // A
        99 to "KEY_2", // X
        100 to "KEY_3", // Y
        97 to "KEY_4", // B
        20 to "KEY_5", // D-pad down
        21 to "KEY_6", // D-pad left
        19 to "KEY_7", // D-pad up
        22 to "KEY_8", // D-pad right
        103 to "KEY_9", // RB
        102 to "KEY_0", // LB
        104 to "KEY_SHIFT_L", // LT: second bar
        105 to "KEY_CTRL_L", // RT: third bar (both: fourth)
        108 to listOf("KEY_SHIFT_L", "KEY_ESC"), // Start: radial menu
        109 to "KEY_M", // Select: world map
        106 to "MOUSE_LEFT_BUTTON", // L3
        107 to "MOUSE_RIGHT_BUTTON", // R3: interact
        110 to "OPEN_NAVIGATION_MENU",
        -3 to "KEY_W",
        -4 to "KEY_S",
        -1 to "KEY_A",
        -2 to "KEY_D",
        -7 to "MOUSE_MOVE_UP",
        -8 to "MOUSE_MOVE_DOWN",
        -5 to "MOUSE_MOVE_LEFT",
        -6 to "MOUSE_MOVE_RIGHT",
    )

    // id, name and cursorSpeed must come first: InputControlsManager reads them with a streaming parser.
    private fun controllerProfile(layout: ControllerLayout) = JSONObject().apply {
        put("id", layout.profileId)
        put("name", layout.profileName)
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
                            bindings(layout).forEach { (keyCode, binding) ->
                                val entry = JSONObject().put("keyCode", keyCode)
                                // A list is a key combo, pressed together.
                                if (binding is List<*>) entry.put("bindings", JSONArray(binding)) else entry.put("binding", binding)
                                put(entry)
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
