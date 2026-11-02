package app.gamenative.ui.screen.wow

import java.io.File

/**
 * No Man's Sky's settings: Binaries/SETTINGS/TKGRAPHICSSETTINGS.MXML in the game folder, an XML file of
 * `<Property name="..." value="..." />` lines. The game writes it on its first run from what it detects,
 * so the launcher edits the file that's there rather than writing its own: top-level values like the
 * resolution, and the quality levels in the GraphicsDetail block, where each category holds a nested
 * `GraphicDetail` of Low, Medium, High or Ultra.
 */
object NmsConfig {
    const val SETTINGS = "Binaries/SETTINGS/TKGRAPHICSSETTINGS.MXML"
    internal const val HANDHELD_VERSION = 1
    val LEVELS = listOf("Low", "Medium", "High", "Ultra")

    /** Quality for each category the first time the launcher sees the file: the game is heavy here. */
    const val HANDHELD_LEVEL = "Low"

    /** The saves folder in the prefix, linked to Saves/ in the game folder. */
    const val PREFIX_SAVES = ".wine/drive_c/users/xuser/AppData/Roaming/HelloGames/NMS"

    fun settingsFile(gameRoot: File) = File(gameRoot, SETTINGS)

    private fun escape(name: String) = Regex.escape(name)

    /** A top-level property's value, or null if the file doesn't have it. */
    fun value(xml: String, name: String): String? =
        Regex("""<Property\s+name="${escape(name)}"\s+value="([^"]*)"""").find(xml)?.groupValues?.get(1)

    /** Sets a property the file already has; properties it lacks are left out rather than guessed. */
    fun setValue(xml: String, name: String, value: String): String {
        val match = Regex("""(<Property\s+name="${escape(name)}"\s+value=")([^"]*)(")""").find(xml) ?: return xml
        return xml.replaceRange(match.range, match.groupValues[1] + value + match.groupValues[3])
    }

    private val QUALITY = Regex("""<Property\s+name="(\w+)"[^>]*>\s*<Property\s+name="GraphicDetail"\s+value="(\w+)"\s*/>""")

    /** Quality category -> level, in the file's order. */
    fun quality(xml: String): Map<String, String> =
        QUALITY.findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }

    fun setQuality(xml: String, category: String, level: String): String {
        val match = QUALITY.findAll(xml).firstOrNull { it.groupValues[1] == category } ?: return xml
        val group = match.groups[2]!!
        return xml.replaceRange(group.range, level)
    }

    fun setAllQuality(xml: String, level: String): String =
        quality(xml).keys.fold(xml) { text, category -> setQuality(text, category, level) }

    /** "TextureQuality" -> "Texture quality". */
    fun label(category: String): String =
        category.replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase().replaceFirstChar { it.uppercase() }

    /** The level every category is at, or null for a mix. */
    fun preset(xml: String): String? = quality(xml).values.toSet().singleOrNull()

    /**
     * Before each launch: the render size follows the screen size the launcher picked, borderless, and
     * the game's own frame cap matches the launcher's. The first time the file is seen, every quality
     * category goes to [HANDHELD_LEVEL] once; later changes stick.
     */
    fun ensure(gameRoot: File, screenSize: String, frameLimit: Int) {
        val file = settingsFile(gameRoot)
        if (!file.isFile) return
        val (width, height) = screenSize.split("x")
        val original = file.readText()
        var xml = original
        xml = setValue(xml, "UseScreenResolution", "false")
        xml = setValue(xml, "ResolutionWidth", width)
        xml = setValue(xml, "ResolutionHeight", height)
        xml = setValue(xml, "FullScreen", "false")
        xml = setValue(xml, "Borderless", "true")
        xml = setValue(xml, "MaxframeRate", frameLimit.toString())
        val tuned = File(file.parentFile, ".handheld-v$HANDHELD_VERSION")
        if (!tuned.exists()) xml = setAllQuality(xml, HANDHELD_LEVEL)
        if (xml != original) file.writeText(xml)
        tuned.createNewFile()
    }

    fun markTuned(gameRoot: File) {
        File(settingsFile(gameRoot).parentFile, ".handheld-v$HANDHELD_VERSION").createNewFile()
    }
}

/**
 * No Man's Sky's saves: one folder per account (DefaultUser for GOG) holding save.hg, save2.hg, ...
 * with mf_ metadata files beside them. Each slot uses two files in turn, so save.hg and save2.hg are
 * slot 1, save3.hg and save4.hg slot 2, and so on. The saves are compressed and carry no screenshot,
 * so the launcher shows the slot and when it was saved.
 */
object NmsSaves : SaveStore {
    override val backupContents = "every save and its metadata"
    override val loadHint = "Pick it from the game's save slots."

    private val SAVE = Regex("""save(\d*)\.hg""", RegexOption.IGNORE_CASE)

    /** The slot a save file belongs to, or null for account data and metadata. */
    internal fun slotOf(name: String): Int? {
        val match = SAVE.matchEntire(name) ?: return null
        val n = match.groupValues[1].ifEmpty { "1" }.toIntOrNull() ?: return null
        return (n + 1) / 2
    }

    /** Every file under Saves/, for backups: saves, metadata and account data. */
    fun allFiles(gameRoot: File): List<File> =
        SkyrimSaves.savesDir(gameRoot).walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.toList()

    override fun recent(gameRoot: File, limit: Int): List<SaveSummary> =
        allFiles(gameRoot)
            .mapNotNull { file -> slotOf(file.name)?.let { file to it } }
            .sortedByDescending { it.first.lastModified() }
            .distinctBy { it.second to it.first.parentFile?.name }
            .take(limit)
            .map { (file, slot) ->
                SaveSummary(
                    title = "Save slot $slot",
                    subtitle = file.parentFile?.name.orEmpty(),
                    detail = file.name,
                    savedAt = file.lastModified(),
                )
            }

    private fun isSave(name: String) = slotOf(name) != null

    override fun backUp(gameRoot: File, force: Boolean) = SkyrimSaves.backUp(gameRoot, force, select = ::allFiles)
    override fun backups(gameRoot: File) = SkyrimSaves.backups(gameRoot, ::isSave)
    override fun restore(gameRoot: File, backup: File) = SkyrimSaves.restore(gameRoot, backup, select = ::allFiles)
}
