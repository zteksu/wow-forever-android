package app.gamenative.ui.screen.wow

import android.content.Context
import app.gamenative.BuildConfig
import app.gamenative.utils.Net
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Installs addons into the client's Interface/AddOns.
 *
 * OctoWoW (the MPQ client) uses its own catalog, the list its launcher offers: each addon is a git
 * repository downloaded as a branch zip. Blizzard's clients use GitHub releases built by the BigWigs
 * packager, whose release.json says which zip is for which game flavor (Forever is "forever"), from a
 * curated list of popular addons plus any repository the player adds.
 */
object AddonManager {
    private const val CATALOG_URL = "https://octowow.st/api/addons.json"
    private const val PREFS = "octo_addons"
    private const val SOURCES_PREFS = "addon_sources"
    private const val KEY_CUSTOM_REPOS = "custom_repos"
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
        /** GitHub owner/repo and the release zip for this client, for release-packaged addons. */
        val repo: String = "",
        val releaseFile: String = "",
        /** CurseForge mod and file ids, for addons from CurseForge. */
        val curseModId: Int = 0,
        val curseFileId: Int = 0,
        val downloads: Long = 0,
    ) {
        val source get() = when {
            curseModId != 0 -> "CurseForge"
            repo.isNotEmpty() -> "GitHub"
            else -> ""
        }
    }

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

    /** The catalog for this client: OctoWoW's list, or GitHub releases built for this game flavor. */
    fun fetchCatalog(context: Context): List<Addon> =
        if (BuildConfig.MPQ_CLIENT) fetchOctoCatalog() else fetchReleaseCatalog(context)

    /** GitHub releases first; CurseForge entries for the same addon are dropped. */
    fun merge(first: List<Addon>, more: List<Addon>): List<Addon> {
        val names = first.flatMap { listOf(it.name.lowercase(), it.title.lowercase()) }.toSet()
        return first + more.filter { it.name.lowercase() !in names && it.title.lowercase() !in names }.distinctBy { it.name }
    }

    fun fetchOctoCatalog(): List<Addon> {
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

    // ---- GitHub releases ----

    private data class ReleaseSource(val repo: String, val title: String, val notes: String)

    // Popular addons whose GitHub releases carry a release.json; the ones without a zip for this client's
    // flavor are left out when the list loads.
    private val RELEASE_SOURCES = listOf(
        ReleaseSource("Questie/Questie", "Questie", "Shows quests, objectives and quest givers on the map and minimap."),
        ReleaseSource("RestedXP/RXPGuides", "RestedXP Guides", "Step-by-step leveling guides with waypoints."),
        ReleaseSource("max-ri/Guidelime", "Guidelime", "Leveling guide with an in-game step list and arrow."),
        ReleaseSource("DeadlyBossMods/DeadlyBossMods", "Deadly Boss Mods", "Boss timers and warnings."),
        ReleaseSource("BigWigsMods/BigWigs", "BigWigs", "Lightweight boss timers and warnings."),
        ReleaseSource("BigWigsMods/LittleWigs", "LittleWigs", "BigWigs modules for dungeons."),
        ReleaseSource("Tercioo/Plater-Nameplates", "Plater Nameplates", "Customisable nameplates with threat colours and scripts."),
        ReleaseSource("Bodify/BetterBlizzPlates", "BetterBlizzPlates", "Improves the default nameplates."),
        ReleaseSource("Bodify/BetterBlizzFrames", "BetterBlizzFrames", "Improves the default unit frames."),
        ReleaseSource("Nevcairiel/Bartender4", "Bartender4", "Movable, configurable action bars."),
        ReleaseSource("tullamods/Dominos", "Dominos", "Action bar replacement."),
        ReleaseSource("tullamods/tullaRange", "tullaRange", "Colours action buttons red when out of range."),
        ReleaseSource("tullamods/OmniCC", "OmniCC", "Cooldown numbers on everything."),
        ReleaseSource("Nevcairiel/Mapster", "Mapster", "World map tweaks: scale, opacity, coordinates."),
        ReleaseSource("d4kir92/MoveAny", "MoveAny", "Move and scale any frame of the interface."),
        ReleaseSource("syndenbock/MinimapButtonButton", "MinimapButtonButton", "Gathers minimap buttons into one."),
        ReleaseSource("Stanzilla/AdvancedInterfaceOptions", "Advanced Interface Options", "Settings the options menu hides."),
        ReleaseSource("BigWigsMods/Capping", "Capping", "Battleground timers and objectives."),
        ReleaseSource("funkydude/BugSack", "BugSack", "Catches Lua errors in a list instead of popups."),
        ReleaseSource("funkydude/BadBoy", "BadBoy", "Blocks gold-seller spam in chat."),
        ReleaseSource("simulationcraft/simc-addon", "SimulationCraft", "Exports your character to SimulationCraft."),
        ReleaseSource("Hekili/hekili", "Hekili", "Priority helper that shows your next ability."),
    )

    /** The packager's flavor name for this client. */
    fun clientFlavor(): String = if (BuildConfig.TARGET_PRODUCT == "wow") "mainline" else "forever"

    private fun releaseUrl(repo: String, file: String): String {
        val encoded = URLEncoder.encode(file, "UTF-8").replace("+", "%20")
        return "https://github.com/$repo/releases/latest/download/$encoded"
    }

    /**
     * The zip in a release.json for [flavor]. Several zips may include it; the one built for the fewest
     * flavors is the one made for this client.
     */
    internal fun pickReleaseFile(json: String, flavor: String): String? {
        val releases = JSONObject(json).optJSONArray("releases") ?: return null
        return (0 until releases.length())
            .mapNotNull { releases.optJSONObject(it) }
            .mapNotNull { release ->
                val metadata = release.optJSONArray("metadata") ?: return@mapNotNull null
                val flavors = (0 until metadata.length()).mapNotNull { metadata.optJSONObject(it)?.optString("flavor") }.toSet()
                val name = release.optString("filename")
                if (flavor in flavors && name.endsWith(".zip") && !release.optBoolean("nolib")) name to flavors.size else null
            }
            .minByOrNull { it.second }
            ?.first
    }

    /** "Questie-v12.3.1+v1.0.9.zip" -> "v12.3.1+v1.0.9". */
    internal fun versionOf(file: String): String {
        val base = file.removeSuffix(".zip")
        val dash = base.indexOf('-')
        return if (dash in 1 until base.length - 1) base.substring(dash + 1) else base
    }

    /** "owner/repo" from that or a github.com URL. */
    internal fun parseRepo(input: String): String? {
        val trimmed = input.trim().removeSuffix("/").removeSuffix(".git")
        val path = if (trimmed.contains("github.com/")) trimmed.substringAfter("github.com/") else trimmed
        val parts = path.split('/')
        if (parts.size < 2) return null
        val repo = "${parts[0]}/${parts[1]}"
        return repo.takeIf { Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(it) }
    }

    private fun fetchRelease(source: ReleaseSource): Addon? {
        val json = Net.http.newCall(Request.Builder().url(releaseUrl(source.repo, "release.json")).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string() ?: return null
        }
        val file = pickReleaseFile(json, clientFlavor()) ?: return null
        val repoName = source.repo.substringAfter('/')
        return Addon(
            name = repoName,
            title = source.title,
            notes = source.notes,
            author = source.repo.substringBefore('/'),
            version = versionOf(file),
            git = "https://github.com/${source.repo}",
            branch = "",
            dependencies = emptyList(),
            lastUpdated = file,
            stars = 0,
            repo = source.repo,
            releaseFile = file,
        )
    }

    private fun customRepos(context: Context): List<String> =
        context.getSharedPreferences(SOURCES_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_REPOS, "")!!
            .split('\n')
            .filter { it.isNotBlank() }

    fun fetchReleaseCatalog(context: Context): List<Addon> {
        val known = RELEASE_SOURCES.map { it.repo.lowercase() }.toSet()
        val sources = RELEASE_SOURCES + customRepos(context)
            .filter { it.lowercase() !in known }
            .map { ReleaseSource(it, it.substringAfter('/'), "Added from GitHub") }
        val pool = Executors.newFixedThreadPool(6)
        try {
            val results = sources.map { source ->
                pool.submit<Result<Addon?>> { runCatching { fetchRelease(source) } }
            }.map { it.get(60, TimeUnit.SECONDS) }
            if (results.all { it.isFailure }) throw results.first().exceptionOrNull() ?: IOException("No addons loaded")
            return results.mapNotNull { it.getOrNull() }
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * Adds any GitHub repository whose releases carry a release.json with a zip for this client, and
     * returns it as a catalog entry.
     */
    fun addCustomRepo(context: Context, input: String): Addon {
        val repo = parseRepo(input) ?: throw IOException("Enter it as owner/repository, like Questie/Questie")
        val addon = fetchRelease(ReleaseSource(repo, repo.substringAfter('/'), "Added from GitHub"))
            ?: throw IOException("$repo has no release built for this game (it needs a release.json from the BigWigs packager)")
        val prefs = context.getSharedPreferences(SOURCES_PREFS, Context.MODE_PRIVATE)
        val repos = (customRepos(context) + repo).distinctBy { it.lowercase() }
        prefs.edit().putString(KEY_CUSTOM_REPOS, repos.joinToString("\n")).apply()
        return addon
    }

    // ---- State ----

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private data class Record(val lastUpdated: String, val folders: List<String>, val curseModId: Int = 0)

    private fun record(context: Context, name: String): Record? =
        prefs(context).getString(name, null)?.let { json ->
            runCatching {
                val o = JSONObject(json)
                val folders = o.getJSONArray("folders").let { a -> (0 until a.length()).map { a.getString(it) } }
                Record(o.optString("lastUpdated"), folders, o.optInt("curseModId"))
            }.getOrNull()
        }

    private fun saveRecord(context: Context, name: String, record: Record?) {
        val editor = prefs(context).edit()
        if (record == null) {
            editor.remove(name)
        } else {
            val json = JSONObject().put("lastUpdated", record.lastUpdated).put("folders", JSONArray(record.folders))
            if (record.curseModId != 0) json.put("curseModId", record.curseModId)
            editor.putString(name, json.toString())
        }
        editor.apply()
    }

    internal fun addonsDir(gameRoot: File) =
        File(if (BuildConfig.FLAVOR_DIR.isEmpty()) gameRoot else File(gameRoot, BuildConfig.FLAVOR_DIR), "Interface/AddOns")

    fun state(context: Context, gameRoot: File, addon: Addon): State {
        val record = record(context, addon.name)
        val installed = record?.folders?.any { File(addonsDir(gameRoot), it).isDirectory } == true
        return when {
            installed && addon.lastUpdated.isNotBlank() && addon.lastUpdated != record!!.lastUpdated -> State.UPDATE_AVAILABLE
            installed -> State.INSTALLED
            File(addonsDir(gameRoot), addon.name).isDirectory -> State.INSTALLED_BY_PLAYER
            else -> State.NOT_INSTALLED
        }
    }

    // ---- Install / remove ----

    /**
     * How far an install has got: [fraction] of the whole job (dependencies included), or null while the size is
     * unknown, and [bytes] of [totalBytes] downloaded for the add-on in progress (totalBytes is -1 when unknown).
     */
    data class Progress(val fraction: Float?, val step: String, val bytes: Long = 0, val totalBytes: Long = -1)

    /** Installs [addon] and any catalog addons it requires that aren't installed yet. */
    fun install(
        context: Context,
        gameRoot: File,
        addon: Addon,
        catalog: List<Addon>,
        onStatus: (String) -> Unit,
        onProgress: (Progress) -> Unit = {},
    ) {
        val required = if (addon.curseModId != 0) {
            CurseForge.requiredDependencies(context, addon)
        } else {
            addon.dependencies.mapNotNull { dep -> catalog.firstOrNull { it.name.equals(dep, ignoreCase = true) } }
        }
        val missing = required.filter { state(context, gameRoot, it) == State.NOT_INSTALLED }
        val all = missing + addon
        all.forEachIndexed { index, item ->
            // Each add-on is an equal share of the bar: its download, then its unpacking.
            installOne(context, gameRoot, item, onStatus) { part, step, bytes, total ->
                onProgress(Progress(part?.let { (index + it) / all.size }, step, bytes, total))
            }
        }
    }

    /** The share of one add-on's bar its download takes; unpacking takes the rest. */
    private const val DOWNLOAD_SHARE = 0.85f

    private fun installOne(
        context: Context,
        gameRoot: File,
        addon: Addon,
        onStatus: (String) -> Unit,
        onProgress: (part: Float?, step: String, bytes: Long, totalBytes: Long) -> Unit = { _, _, _, _ -> },
    ) {
        val url = if (addon.curseModId != 0) {
            CurseForge.downloadUrl(context, addon)
        } else if (addon.releaseFile.isNotEmpty()) {
            releaseUrl(addon.repo, addon.releaseFile)
        } else {
            archiveUrl(addon.git, addon.branch) ?: throw IOException("Unsupported addon host for ${addon.name}")
        }
        onStatus("Downloading ${addon.title}...")
        val zip = File(context.cacheDir, "addon-${addon.name}.zip")
        try {
            Net.http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code} downloading ${addon.title}")
                val body = response.body ?: throw IOException("Empty download for ${addon.title}")
                val total = body.contentLength()
                val step = "Downloading ${addon.title}"
                onProgress(if (total > 0) 0f else null, step, 0, total)
                zip.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        var lastReport = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            done += read
                            // About every 64 KB is plenty for a smooth bar without flooding the UI.
                            if (done - lastReport >= buffer.size || done == total) {
                                lastReport = done
                                onProgress(if (total > 0) DOWNLOAD_SHARE * done / total else null, step, done, total)
                            }
                        }
                    }
                }
            }
            onStatus("Installing ${addon.title}...")
            val unpack = "Unpacking ${addon.title}"
            onProgress(DOWNLOAD_SHARE, unpack, 0, -1)
            val folders = extractAddon(zip, addonsDir(gameRoot), addon.name) { part ->
                onProgress(DOWNLOAD_SHARE + (1 - DOWNLOAD_SHARE) * part, unpack, 0, -1)
            }
            saveRecord(context, addon.name, Record(addon.lastUpdated, folders, addon.curseModId))
            Timber.i("Installed addon ${addon.name} into $folders")
        } finally {
            zip.delete()
        }
    }

    // ---- Updates ----

    /**
     * The installed add-ons with a newer version: from this client's catalog, and from CurseForge for add-ons
     * installed from there (when a key is set). A source that can't be reached is skipped.
     */
    fun findUpdates(context: Context, gameRoot: File): List<Addon> {
        val catalog = runCatching { fetchCatalog(context) }.onFailure { Timber.w(it, "Add-on catalog check failed") }.getOrDefault(emptyList())
        val curseIds = prefs(context).all.filterValues { it is String }.keys
            .mapNotNull { record(context, it)?.curseModId?.takeIf { id -> id != 0 } }.distinct()
        val curse = if (BuildConfig.MPQ_CLIENT || curseIds.isEmpty()) {
            emptyList()
        } else {
            runCatching { CurseForge.mods(context, curseIds) }.onFailure { Timber.w(it, "CurseForge update check failed") }
                .getOrDefault(emptyList())
        }
        return pendingUpdates(catalog + curse) { state(context, gameRoot, it) }
    }

    /** The add-ons in [candidates] that [stateOf] says have an update, once each. */
    internal fun pendingUpdates(candidates: List<Addon>, stateOf: (Addon) -> State): List<Addon> =
        candidates.distinctBy { it.name.lowercase() }.filter { stateOf(it) == State.UPDATE_AVAILABLE }

    /** Updates each of [addons] in turn, reporting progress across all of them. */
    fun updateAll(context: Context, gameRoot: File, addons: List<Addon>, onProgress: (Progress) -> Unit = {}) {
        val failed = mutableListOf<String>()
        addons.forEachIndexed { index, addon ->
            try {
                installOne(context, gameRoot, addon, {}) { part, step, bytes, total ->
                    onProgress(Progress(part?.let { (index + it) / addons.size }, step, bytes, total))
                }
            } catch (e: Exception) {
                // One add-on that can't be downloaded shouldn't stop the rest.
                Timber.w(e, "Updating ${addon.name} failed")
                failed += addon.title
            }
        }
        if (failed.isNotEmpty()) throw IOException("Couldn't update ${failed.joinToString()}")
    }

    // When the launcher last checked for updates, so it checks at most every few hours (GitHub limits API calls).
    private const val KEY_LAST_CHECK = "_last_update_check"
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

    fun isUpdateCheckDue(context: Context, now: Long = System.currentTimeMillis()): Boolean =
        now - context.getSharedPreferences(SOURCES_PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_CHECK, 0) >= CHECK_INTERVAL_MS

    fun markUpdateChecked(context: Context, now: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(SOURCES_PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_LAST_CHECK, now).apply()
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
    /** "MoveAny_Vanilla.toc" -> "MoveAny": the addon a (possibly per-game-version) .toc belongs to. */
    internal fun tocBase(file: String): String {
        val base = file.removeSuffix(".toc").removeSuffix(".TOC")
        return base.replace(TOC_FLAVOR_SUFFIX, "")
    }

    private val TOC_FLAVOR_SUFFIX = Regex(
        "[-_](Vanilla|Classic|TBC|BCC|Wrath|WOTLKC|Cata|Mists|Mainline|Forever|Titan|Camelot|Standard)$",
        RegexOption.IGNORE_CASE,
    )

    internal fun extractAddon(
        zipFile: File,
        addonsDir: File,
        preferredName: String,
        onProgress: (Float) -> Unit = {},
    ): List<String> {
        ZipFile(zipFile).use { zip ->
            val entries = zip.entries().toList().filterNot { it.name.contains("..") || it.name.startsWith("/") }
            val prefix = entries.map { it.name.substringBefore('/', "") + "/" }.distinct().singleOrNull()
                ?.takeIf { p -> p != "/" && entries.all { it.name.startsWith(p) } } ?: ""
            val tocs = entries.map { it.name.removePrefix(prefix) }.filter { it.endsWith(".toc", ignoreCase = true) }

            val rootTocs = tocs.filter { '/' !in it }.map(::tocBase)
            val topFolder = prefix.removeSuffix("/")
            val roots: Map<String, String> = if (rootTocs.isNotEmpty()) {
                // Several root .toc files (one per game version) still make one addon. A release zip's own
                // folder is the addon's name; a git branch zip's ("repo-main") isn't, so then the catalog name.
                val folder = rootTocs.firstOrNull { it.equals(topFolder, true) }?.let { topFolder }
                    ?: rootTocs.firstOrNull { it.equals(preferredName, true) }
                    ?: rootTocs.minByOrNull { it.length }!!
                mapOf("" to folder)
            } else {
                tocs.filter { it.count { c -> c == '/' } == 1 }
                    .map { it.substringBefore('/') to tocBase(it.substringAfter('/')) }
                    .filter { (dir, toc) -> dir.equals(toc, ignoreCase = true) }
                    .associate { (dir, _) -> "$dir/" to dir }
            }
            if (roots.isEmpty()) throw IOException("No addon (.toc file) found in the download")

            addonsDir.mkdirs()
            val staging = File(addonsDir, ".install-tmp").apply { deleteRecursively(); mkdirs() }
            try {
                var extracted = 0
                val count = (entries.size * roots.size).coerceAtLeast(1)
                for ((sub, folder) in roots) {
                    val target = File(staging, folder)
                    for (entry in entries) {
                        if (++extracted % 25 == 0) onProgress(extracted.toFloat() / count)
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
