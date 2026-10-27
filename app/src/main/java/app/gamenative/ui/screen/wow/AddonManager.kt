package app.gamenative.ui.screen.wow

import android.content.Context
import app.gamenative.utils.Net
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.zip.ZipFile
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Installs 1.12 addons for the MPQ client from OctoWoW's own addon catalog, the list its launcher offers.
 * Each addon is a git repository; the app downloads the branch as a zip from the host's archive endpoint
 * rather than cloning.
 */
object AddonManager {
    private const val CATALOG_URL = "https://octowow.st/api/addons.json"
    private const val PREFS = "octo_addons"
    private const val ADDONS_DIR = "Interface/AddOns"
    private const val DISABLED_DIR = "Interface/AddOns-disabled"

    data class Addon(
        val name: String,
        val title: String,
        val notes: String,
        val author: String,
        val version: String,
        val git: String,
        val branch: String,
        val dependencies: List<String>,
        val lastUpdated: String,
        val stars: Int,
    )

    enum class State { NOT_INSTALLED, INSTALLED, UPDATE_AVAILABLE, INSTALLED_BY_PLAYER }

    /** The 1.12 ConsolePort-style addon behind the ConsoleExperience controller layout. */
    val CONSOLE_EXPERIENCE = Addon(
        name = "ConsoleExperienceClassic",
        title = "ConsoleExperience Classic",
        notes = "Controller-style UI for 1.12: gamepad action bars with trigger modifiers, cursor navigation, " +
            "radial menu and on-screen keyboard (early alpha).",
        author = "pepordev",
        version = "",
        git = "https://github.com/pepordev/ConsoleExperienceClassic.git",
        branch = "main",
        dependencies = emptyList(),
        lastUpdated = "",
        stars = 0,
    )

    // ---- Catalog ----

    fun fetchCatalog(): List<Addon> {
        val body = Net.http.newCall(Request.Builder().url(CATALOG_URL).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} from the OctoWoW addon list")
            response.body?.string() ?: throw IOException("Empty OctoWoW addon list")
        }
        // ConsoleExperience is switched with the controller layout, never installed alongside ShaguController.
        return parseCatalog(body).filterNot { it.name.equals(CONSOLE_EXPERIENCE.name, ignoreCase = true) }
    }

    internal fun parseCatalog(json: String): List<Addon> {
        val array = JSONArray(json)
        return (0 until array.length()).mapNotNull { i ->
            val entry = array.optJSONObject(i) ?: return@mapNotNull null
            val toc = entry.optJSONObject("toc") ?: JSONObject()
            val name = entry.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val git = entry.optString("git").takeIf { archiveUrl(it, "x") != null } ?: return@mapNotNull null
            Addon(
                name = name,
                title = stripColors(toc.optString("Title")).ifBlank { name },
                notes = stripColors(toc.optString("Notes")).ifBlank { entry.optString("description") },
                author = toc.optString("Author").ifBlank { entry.optString("owner") },
                version = toc.optString("Version"),
                git = git,
                branch = entry.optString("branch").ifBlank { "master" },
                dependencies = listOf("Dependencies", "RequiredDeps", "Dependancies")
                    .flatMap { toc.optString(it).split(',') }
                    .map { it.trim() }
                    .filter { it.isNotEmpty() },
                lastUpdated = entry.optString("lastUpdated"),
                stars = entry.optInt("stars"),
            )
        }
    }

    /** WoW colour codes (|cffRRGGBB ... |r) in titles. */
    internal fun stripColors(text: String) = text.replace(Regex("\\|c[0-9a-fA-F]{8}"), "").replace("|r", "").trim()

    /** The branch as a zip, for the hosts the catalog uses: GitHub and OctoWoW's Gitea. */
    internal fun archiveUrl(git: String, branch: String): String? {
        val uri = runCatching { URI(git) }.getOrNull() ?: return null
        if (uri.scheme != "https") return null
        val path = uri.path.removeSuffix("/").removeSuffix(".git")
        return when (uri.host?.lowercase()) {
            "github.com" -> "https://github.com$path/archive/refs/heads/$branch.zip"
            "git.octowow.st", "octowow.st", "codeberg.org" -> "https://${uri.host}$path/archive/$branch.zip"
            else -> null
        }
    }

    // ---- State ----

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private data class Record(val lastUpdated: String, val folders: List<String>)

    private fun record(context: Context, name: String): Record? =
        prefs(context).getString(name, null)?.let { json ->
            runCatching {
                val o = JSONObject(json)
                val folders = o.getJSONArray("folders").let { a -> (0 until a.length()).map { a.getString(it) } }
                Record(o.optString("lastUpdated"), folders)
            }.getOrNull()
        }

    private fun saveRecord(context: Context, name: String, record: Record?) {
        val editor = prefs(context).edit()
        if (record == null) {
            editor.remove(name)
        } else {
            editor.putString(name, JSONObject().put("lastUpdated", record.lastUpdated).put("folders", JSONArray(record.folders)).toString())
        }
        editor.apply()
    }

    private fun addonsDir(gameRoot: File) = File(gameRoot, ADDONS_DIR)

    fun state(context: Context, gameRoot: File, addon: Addon): State {
        val record = record(context, addon.name)
        val installed = record?.folders?.any { File(addonsDir(gameRoot), it).isDirectory } == true
        return when {
            installed && addon.lastUpdated.isNotBlank() && addon.lastUpdated > record!!.lastUpdated -> State.UPDATE_AVAILABLE
            installed -> State.INSTALLED
            File(addonsDir(gameRoot), addon.name).isDirectory -> State.INSTALLED_BY_PLAYER
            else -> State.NOT_INSTALLED
        }
    }

    // ---- Install / remove ----

    /** Installs [addon] and any catalog addons it requires that aren't installed yet. */
    fun install(context: Context, gameRoot: File, addon: Addon, catalog: List<Addon>, onStatus: (String) -> Unit) {
        val missing = addon.dependencies
            .mapNotNull { dep -> catalog.firstOrNull { it.name.equals(dep, ignoreCase = true) } }
            .filter { state(context, gameRoot, it) == State.NOT_INSTALLED }
        for (dependency in missing) installOne(context, gameRoot, dependency, onStatus)
        installOne(context, gameRoot, addon, onStatus)
    }

    private fun installOne(context: Context, gameRoot: File, addon: Addon, onStatus: (String) -> Unit) {
        val url = archiveUrl(addon.git, addon.branch) ?: throw IOException("Unsupported addon host for ${addon.name}")
        onStatus("Downloading ${addon.title}...")
        val zip = File(context.cacheDir, "addon-${addon.name}.zip")
        try {
            Net.http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code} downloading ${addon.title}")
                val body = response.body ?: throw IOException("Empty download for ${addon.title}")
                zip.outputStream().use { body.byteStream().copyTo(it) }
            }
            onStatus("Installing ${addon.title}...")
            val folders = extractAddon(zip, addonsDir(gameRoot), addon.name)
            saveRecord(context, addon.name, Record(addon.lastUpdated, folders))
            Timber.i("Installed addon ${addon.name} into $folders")
        } finally {
            zip.delete()
        }
    }

    fun remove(context: Context, gameRoot: File, addon: Addon) {
        val folders = record(context, addon.name)?.folders ?: listOf(addon.name)
        folders.forEach { File(addonsDir(gameRoot), it).deleteRecursively() }
        saveRecord(context, addon.name, null)
    }

    /**
     * Extracts an addon archive into [addonsDir] and returns the folder names it created. WoW only loads a
     * folder that holds a .toc of the same name, so folders are named after their .toc: one addon at the
     * archive root, or one per subfolder for repositories that bundle several.
     */
    internal fun extractAddon(zipFile: File, addonsDir: File, preferredName: String): List<String> {
        ZipFile(zipFile).use { zip ->
            val entries = zip.entries().toList().filterNot { it.name.contains("..") || it.name.startsWith("/") }
            val prefix = entries.map { it.name.substringBefore('/', "") + "/" }.distinct().singleOrNull()
                ?.takeIf { p -> p != "/" && entries.all { it.name.startsWith(p) } } ?: ""
            val tocs = entries.map { it.name.removePrefix(prefix) }.filter { it.endsWith(".toc", ignoreCase = true) }

            val rootTocs = tocs.filter { '/' !in it }.map { it.removeSuffix(".toc").removeSuffix(".TOC") }
            val roots: Map<String, String> = if (rootTocs.isNotEmpty()) {
                // Several root .toc files (e.g. per game version) still make one addon; prefer the catalog name.
                val folder = rootTocs.firstOrNull { it.equals(preferredName, true) } ?: rootTocs.sorted().first()
                mapOf("" to folder)
            } else {
                tocs.filter { it.count { c -> c == '/' } == 1 }
                    .map { it.substringBefore('/') to it.substringAfter('/').removeSuffix(".toc").removeSuffix(".TOC") }
                    .filter { (dir, toc) -> dir.equals(toc, ignoreCase = true) }
                    .associate { (dir, toc) -> "$dir/" to toc }
            }
            if (roots.isEmpty()) throw IOException("No addon (.toc file) found in the download")

            addonsDir.mkdirs()
            val staging = File(addonsDir, ".install-tmp").apply { deleteRecursively(); mkdirs() }
            try {
                for ((sub, folder) in roots) {
                    val target = File(staging, folder)
                    for (entry in entries) {
                        val relative = entry.name.removePrefix(prefix)
                        if (!relative.startsWith(sub) || relative == sub) continue
                        val out = File(target, relative.removePrefix(sub))
                        if (!out.canonicalPath.startsWith(target.canonicalPath)) continue
                        if (entry.isDirectory) {
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
                        }
                    }
                }
                for (folder in roots.values) {
                    val dest = File(addonsDir, folder)
                    dest.deleteRecursively()
                    if (!File(staging, folder).renameTo(dest)) throw IOException("Could not install $folder")
                }
            } finally {
                staging.deleteRecursively()
            }
            return roots.values.toList()
        }
    }

    // ---- Controller layout ----

    /**
     * Activates the addon behind [layout] and parks the other controller addons in AddOns-disabled, since
     * they rebind the same keys. ConsoleExperience is downloaded the first time it is chosen.
     */
    fun applyControllerLayout(context: Context, gameRoot: File, layout: MpqClient.ControllerLayout, onStatus: (String) -> Unit) {
        val addons = addonsDir(gameRoot)
        val disabled = File(gameRoot, DISABLED_DIR)
        // Get the new addon in place first, so a failed download leaves the current layout working.
        val wanted = File(addons, layout.addonFolder)
        val parked = File(disabled, layout.addonFolder)
        when {
            layout.bundled -> {
                parked.deleteRecursively()
                MpqClient.installBundled(context, gameRoot, layout)
            }
            wanted.isDirectory -> Unit
            parked.isDirectory -> parked.renameTo(wanted)
            else -> installOne(context, gameRoot, CONSOLE_EXPERIENCE, onStatus)
        }
        for (other in MpqClient.ControllerLayout.entries.filter { it != layout }) {
            val active = File(addons, other.addonFolder)
            if (active.isDirectory) {
                disabled.mkdirs()
                File(disabled, other.addonFolder).deleteRecursively()
                active.renameTo(File(disabled, other.addonFolder))
            }
        }
        MpqClient.setControllerLayout(context, layout)
    }
}
