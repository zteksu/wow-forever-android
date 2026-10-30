package app.gamenative.ui.screen.wow

import android.content.Context
import app.gamenative.PrefManager
import java.io.File

/**
 * The launcher's graphics options for Skyrim, like the Options window of the game's own launcher.
 *
 * Quality options are read from and written to SkyrimPrefs.ini, so they always show what the game will
 * use. Resolution and the frame limit belong to the emulated display and DXVK, so the app keeps them.
 */
object SkyrimGraphics {
    data class Choice(val label: String, val values: Map<Pair<String, String>, String>)

    data class Option(val id: String, val label: String, val section: String, val choices: List<Choice>)

    private fun choice(label: String, vararg values: Pair<String, String>) =
        Choice(label, values.associate { (key, value) -> key.substringBefore('/') to key.substringAfter('/') to value })

    private fun toggle(vararg keys: String) = listOf(
        choice("Off", *keys.map { it to "0" }.toTypedArray()),
        choice("On", *keys.map { it to "1" }.toTypedArray()),
    )

    const val QUALITY = "Quality"
    const val EFFECTS = "Effects"
    const val DISTANCE = "View distance"

    val OPTIONS: List<Option> = listOf(
        Option(
            "aa", "Antialiasing", QUALITY,
            listOf(
                choice("Off", "Display/bUseTAA" to "0", "Display/bFXAAEnabled" to "0"),
                choice("FXAA", "Display/bUseTAA" to "0", "Display/bFXAAEnabled" to "1"),
                choice("TAA", "Display/bUseTAA" to "1", "Display/bFXAAEnabled" to "0"),
            ),
        ),
        Option(
            "aniso", "Anisotropic filtering", QUALITY,
            listOf("Off" to "0", "2x" to "2", "4x" to "4", "8x" to "8", "16x" to "16").map { (label, value) ->
                choice(label, "Display/iMaxAnisotropy" to value)
            },
        ),
        Option(
            "textures", "Texture quality", QUALITY,
            listOf("Low" to "2", "Medium" to "1", "High" to "0").map { (label, value) ->
                choice(label, "Display/iTexMipMapSkip" to value)
            },
        ),
        Option(
            "shadows", "Shadow quality", QUALITY,
            listOf(
                listOf("Low", "512", "1500.0000", "1500.0000", "1"),
                listOf("Medium", "1024", "2000.0000", "2000.0000", "1"),
                listOf("High", "2048", "4000.0000", "3000.0000", "3"),
                listOf("Ultra", "4096", "8000.0000", "4000.0000", "3"),
            ).map { (label, resolution, distance, interior, blur) ->
                choice(
                    label,
                    "Display/iShadowMapResolution" to resolution,
                    "Display/fShadowDistance" to distance,
                    "Display/fInteriorShadowDistance" to interior,
                    "Display/iBlurDeferredShadowMask" to blur,
                )
            },
        ),
        Option("shadowDetail", "Shadows on land, trees and grass", QUALITY, toggle("Display/bDrawLandShadows", "Display/bTreesReceiveShadows", "Display/bShadowsOnGrass")),
        Option(
            "decals", "Decals", QUALITY,
            listOf(
                listOf("Off", "0", "0", "0", "0"),
                listOf("Low", "1", "40", "0", "0"),
                listOf("Medium", "1", "100", "1", "35"),
                listOf("High", "1", "1000", "1", "100"),
            ).map { (label, enabled, decals, skinned, skin) ->
                choice(
                    label,
                    "Decals/bDecals" to enabled,
                    "Decals/uMaxDecals" to decals,
                    "Decals/bSkinnedDecals" to skinned,
                    "Decals/uMaxSkinDecals" to skin,
                )
            },
        ),
        Option(
            "grass", "Grass density", QUALITY,
            listOf("Low" to "80", "Medium" to "60", "High" to "40", "Ultra" to "20").map { (label, value) ->
                choice(label, "Grass/iMinGrassSize" to value)
            },
        ),
        Option(
            "particles", "Particles", QUALITY,
            listOf("Low" to "500", "Medium" to "750", "High" to "1500", "Ultra" to "3000").map { (label, value) ->
                choice(label, "Particles/iMaxDesired" to value)
            },
        ),
        Option("ao", "Ambient occlusion", EFFECTS, toggle("Display/bSAOEnable")),
        Option("volumetric", "Volumetric lighting (god rays)", EFFECTS, toggle("Display/bVolumetricLightingEnable")),
        Option("ssr", "Screen-space reflections", EFFECTS, toggle("Display/bScreenSpaceReflectionEnabled")),
        Option("water", "Distant water reflections", EFFECTS, toggle("Water/bReflectLODObjects", "Water/bReflectLODTrees", "Water/bReflectLODLand")),
        Option("dof", "Depth of field", EFFECTS, toggle("Imagespace/bDoDepthOfField")),
        Option("lensFlare", "Lens flare", EFFECTS, toggle("Display/bIBLFEnable", "Imagespace/bLensFlare")),
        Option("snow", "Snow shader", EFFECTS, toggle("Display/bEnableImprovedSnow")),
        Option(
            "objects", "Objects", DISTANCE,
            listOf("Near" to "5.0000", "Medium" to "7.0000", "Far" to "10.0000", "Max" to "15.0000").map { (label, value) ->
                choice(label, "LOD/fLODFadeOutMultObjects" to value)
            },
        ),
        Option(
            "actors", "Characters", DISTANCE,
            listOf("Near" to "6.0000", "Medium" to "8.0000", "Far" to "10.0000", "Max" to "15.0000").map { (label, value) ->
                choice(label, "LOD/fLODFadeOutMultActors" to value)
            },
        ),
        Option(
            "items", "Items", DISTANCE,
            listOf("Near" to "2.0000", "Medium" to "3.0000", "Far" to "4.0000", "Max" to "6.0000").map { (label, value) ->
                choice(label, "LOD/fLODFadeOutMultItems" to value)
            },
        ),
        Option(
            "grassDistance", "Grass", DISTANCE,
            listOf("Near" to "2000.0000", "Medium" to "3000.0000", "Far" to "5000.0000", "Max" to "7000.0000").map { (label, value) ->
                choice(label, "Grass/fGrassStartFadeDistance" to value)
            },
        ),
    )

    private val EFFECT_IDS = OPTIONS.filter { it.section == EFFECTS }.map { it.id }
    private val DISTANCE_IDS = OPTIONS.filter { it.section == DISTANCE }.map { it.id }

    private fun preset(base: Map<String, String>, effects: String, distance: String) =
        base + EFFECT_IDS.associateWith { effects } + DISTANCE_IDS.associateWith { distance }

    /** Option id -> choice label for each preset. Medium is the handheld default. */
    val PRESETS: Map<String, Map<String, String>> = linkedMapOf(
        "Low" to preset(
            mapOf(
                "aa" to "FXAA", "aniso" to "Off", "textures" to "Medium", "shadows" to "Low", "shadowDetail" to "Off",
                "decals" to "Low", "grass" to "Low", "particles" to "Low",
            ),
            effects = "Off", distance = "Near",
        ),
        "Medium" to preset(
            mapOf(
                "aa" to "TAA", "aniso" to "4x", "textures" to "High", "shadows" to "Medium", "shadowDetail" to "Off",
                "decals" to "Medium", "grass" to "Medium", "particles" to "Medium",
            ),
            effects = "Off", distance = "Medium",
        ),
        "High" to preset(
            mapOf(
                "aa" to "TAA", "aniso" to "8x", "textures" to "High", "shadows" to "High", "shadowDetail" to "On",
                "decals" to "High", "grass" to "High", "particles" to "High",
            ),
            effects = "On", distance = "Far",
        ),
        "Ultra" to preset(
            mapOf(
                "aa" to "TAA", "aniso" to "16x", "textures" to "High", "shadows" to "Ultra", "shadowDetail" to "On",
                "decals" to "High", "grass" to "Ultra", "particles" to "Ultra",
            ),
            effects = "On", distance = "Max",
        ),
    )

    const val DEFAULT_PRESET = "Medium"

    /** The INI values a set of option choices stands for. */
    fun values(selection: Map<String, String>): Map<Pair<String, String>, String> =
        OPTIONS.flatMap { option ->
            option.choices.firstOrNull { it.label == selection[option.id] }?.values?.entries.orEmpty()
        }.associate { it.key to it.value }

    /** The choice each option matches in [ini]; options that match none (or aren't set) are left out. */
    fun read(ini: String): Map<String, String> {
        val current = parse(ini)
        return OPTIONS.mapNotNull { option ->
            option.choices.firstOrNull { choice ->
                choice.values.all { (key, value) -> sameValue(current[key.first.lowercase() to key.second.lowercase()], value) }
            }?.let { option.id to it.label }
        }.toMap()
    }

    /** The preset [selection] matches exactly, or null for a custom mix. */
    fun presetOf(selection: Map<String, String>): String? =
        PRESETS.entries.firstOrNull { (_, choices) -> choices.all { (id, label) -> selection[id] == label } }?.key

    fun apply(ini: String, selection: Map<String, String>): String =
        values(selection).entries.fold(ini) { text, (key, value) -> SkyrimConfig.setValue(text, key.first, key.second, value) }

    private fun sameValue(current: String?, wanted: String): Boolean {
        if (current == null) return false
        val a = current.toDoubleOrNull()
        val b = wanted.toDoubleOrNull()
        return if (a != null && b != null) a == b else current.equals(wanted, ignoreCase = true)
    }

    private fun parse(ini: String): Map<Pair<String, String>, String> {
        val values = HashMap<Pair<String, String>, String>()
        var section = ""
        for (raw in ini.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("[") && line.endsWith("]") -> section = line.substring(1, line.length - 1).trim().lowercase()
                '=' in line && !line.startsWith(";") ->
                    values[section to line.substringBefore('=').trim().lowercase()] = line.substringAfter('=').trim()
            }
        }
        return values
    }

    fun pendingFile(context: Context) = File(context.filesDir, "SkyrimPrefs.pending.ini")

    /**
     * The settings the game will start with: the prefix's file (with the defaults the next launch adds),
     * or the settings saved before the prefix existed, or the defaults.
     */
    fun load(documentsDir: File?, pending: File, screenSize: String): String {
        val prefs = documentsDir?.let { SkyrimConfig.prefsFile(it) }
        return when {
            pending.isFile -> pending.readText()
            prefs != null && prefs.isFile -> prefs.readText().let { if (SkyrimConfig.isTuned(prefs)) it else SkyrimConfig.applyPerformance(it) }
            else -> SkyrimConfig.defaultPrefs(screenSize)
        }
    }

    fun save(documentsDir: File?, pending: File, ini: String) {
        if (documentsDir == null || !documentsDir.isDirectory) {
            pending.writeText(ini)
            return
        }
        val prefs = SkyrimConfig.prefsFile(documentsDir)
        prefs.parentFile?.mkdirs()
        prefs.writeText(ini)
        SkyrimConfig.markTuned(prefs)
        pending.delete()
    }

    // App-side settings: the emulated display, DXVK and how the game starts.
    private const val PREFS = "skyrim_graphics"
    private const val KEY_RESOLUTION = "resolution"
    private const val KEY_FRAME_LIMIT = "frame_limit"
    private const val KEY_SKIP_INTRO = "skip_intro"
    private const val KEY_OVERLAY = "overlay"
    private const val KEY_SKSE = "skse"

    /** 16:9 render sizes; the launch reshapes them to the screen's aspect ratio. */
    val RESOLUTIONS = listOf("960x540", "1280x720", "1600x900", "1920x1080")
    val FRAME_LIMITS = listOf(30, 40, 60)
    const val DEFAULT_FRAME_LIMIT = 60

    /**
     * Performance overlays: label -> DXVK_HUD value. FPS is the app's own counter (also in the in-game
     * menu); Detailed is DXVK's, with a frame-time graph and GPU load.
     */
    val OVERLAYS = linkedMapOf("Off" to "", "FPS" to "", "Detailed" to "fps,frametimes,gpuload")

    /** Low quality at 540p and 30 FPS, for long sessions on battery. */
    const val BATTERY_SAVER = "Battery saver"
    const val BATTERY_PRESET = "Low"
    const val BATTERY_RESOLUTION = "960x540"
    const val BATTERY_FRAME_LIMIT = 30

    data class Launch(
        val resolution: String? = null,
        val frameLimit: Int = DEFAULT_FRAME_LIMIT,
        val skipIntro: Boolean = true,
        val overlay: String = "Off",
        val skse: Boolean = true,
    ) {
        val hud: String get() = OVERLAYS[overlay].orEmpty()
    }

    fun launch(context: Context): Launch {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Launch(
            resolution = prefs.getString(KEY_RESOLUTION, null)?.takeIf { it in RESOLUTIONS },
            frameLimit = prefs.getInt(KEY_FRAME_LIMIT, DEFAULT_FRAME_LIMIT).takeIf { it in FRAME_LIMITS } ?: DEFAULT_FRAME_LIMIT,
            skipIntro = prefs.getBoolean(KEY_SKIP_INTRO, true),
            overlay = when {
                prefs.getString(KEY_OVERLAY, null) == "Detailed" -> "Detailed"
                PrefManager.showFps -> "FPS"
                else -> "Off"
            },
            skse = prefs.getBoolean(KEY_SKSE, true),
        )
    }

    fun saveLaunch(context: Context, launch: Launch) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .apply { if (launch.resolution == null) remove(KEY_RESOLUTION) else putString(KEY_RESOLUTION, launch.resolution) }
            .putInt(KEY_FRAME_LIMIT, launch.frameLimit)
            .putBoolean(KEY_SKIP_INTRO, launch.skipIntro)
            .putString(KEY_OVERLAY, launch.overlay)
            .putBoolean(KEY_SKSE, launch.skse)
            .apply()
        PrefManager.showFps = launch.overlay == "FPS"
    }

    /** The preset row's label: Battery saver when Low runs at its resolution and frame limit. */
    fun presetLabel(selection: Map<String, String>, launch: Launch, defaultResolution: String): String {
        val preset = presetOf(selection) ?: return "Custom"
        val resolution = launch.resolution ?: defaultResolution
        return if (preset == BATTERY_PRESET && resolution == BATTERY_RESOLUTION && launch.frameLimit == BATTERY_FRAME_LIMIT) BATTERY_SAVER else preset
    }
}
