package app.gamenative.ui.screen.wow

import android.content.Context
import app.gamenative.utils.Net
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Turnip (Mesa's open Adreno Vulkan driver) builds in AdreNoTools format: a zip holding meta.json and
 * the driver library it names. They're kept in contents/adrenotools/<name>, where the container picks
 * one by name (see AdrenotoolsManager.setDriverById), so a downloaded driver works like a bundled one.
 *
 * Downloads come from the GitHub releases of the projects that publish such zips, like the driver
 * fetchers in Switch emulators.
 */
object TurnipDrivers {
    data class Source(val repo: String, val label: String)

    /** whitebelyash builds Turnip for Adreno 8xx (gen8), including the A830; the bundled driver is one. */
    val SOURCES = listOf(
        Source("whitebelyash/AdrenoToolsDrivers", "Mainline Turnip (whitebelyash)"),
        Source("K11MCH1/AdrenoToolsDrivers", "K11MCH1"),
    )

    data class Release(
        val repo: String,
        val tag: String,
        val title: String,
        val publishedAt: String,
        val prerelease: Boolean,
        val notes: String,
        val assets: List<Asset>,
    )

    data class Asset(val name: String, val url: String, val size: Long)

    /** Where a downloaded driver came from, kept next to it so the app can offer that project's newer builds. */
    data class Origin(val repo: String, val tag: String, val publishedAt: String, val asset: String)

    data class Installed(
        val name: String,
        val driverVersion: String,
        val description: String,
        val bundled: Boolean,
        val origin: Origin? = null,
    )

    /** A newer build of an installed driver, from the project it was downloaded from. */
    data class Update(val driver: Installed, val release: Release, val asset: Asset)

    private const val MAX_RELEASES = 10
    private const val META = "meta.json"
    private const val ORIGIN = "origin.json"

    fun driversDir(context: Context) = File(context.filesDir, "contents/adrenotools")

    /** Releases with at least one zip, newest first. */
    internal fun parseReleases(repo: String, json: JSONArray): List<Release> =
        (0 until json.length()).mapNotNull { json.optJSONObject(it) }
            .filterNot { it.optBoolean("draft") }
            .mapNotNull { release ->
                val assets = release.optJSONArray("assets") ?: JSONArray()
                val zips = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
                    .filter { it.optString("name").endsWith(".zip", ignoreCase = true) }
                    .map { Asset(it.optString("name"), it.optString("browser_download_url"), it.optLong("size")) }
                    .filter { it.url.startsWith("https://") }
                if (zips.isEmpty()) return@mapNotNull null
                Release(
                    repo = repo,
                    tag = release.optString("tag_name"),
                    title = release.optString("name").ifBlank { release.optString("tag_name") },
                    publishedAt = release.optString("published_at").take(10),
                    prerelease = release.optBoolean("prerelease"),
                    notes = release.optString("body"),
                    assets = zips,
                )
            }

    fun fetchReleases(source: Source): List<Release> {
        val request = Request.Builder()
            .url("https://api.github.com/repos/${source.repo}/releases?per_page=$MAX_RELEASES")
            .header("Accept", "application/vnd.github+json")
            .build()
        Net.http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} from GitHub for ${source.repo}")
            return parseReleases(source.repo, JSONArray(response.body?.string() ?: "[]"))
        }
    }

    /** The drivers in [dir]; [bundled] names the ones that ship with the app and can't be removed. */
    fun installed(dir: File, bundled: Set<String>): List<Installed> =
        dir.listFiles { f -> f.isDirectory && File(f, META).isFile }.orEmpty()
            .mapNotNull { folder ->
                val meta = runCatching { JSONObject(File(folder, META).readText()) }.getOrNull() ?: return@mapNotNull null
                Installed(
                    name = folder.name,
                    driverVersion = meta.optString("driverVersion"),
                    description = meta.optString("description"),
                    bundled = folder.name in bundled,
                    origin = readOrigin(File(folder, ORIGIN)),
                )
            }
            .sortedWith(compareByDescending<Installed> { it.bundled }.thenBy { it.name.lowercase() })

    /**
     * Installs an AdreNoTools zip into [dir] under the name in its meta.json and returns that name.
     * meta.json may sit at the top of the zip or in one folder. A driver with a bundled driver's name is
     * refused, and an earlier download with the same name is replaced.
     */
    fun install(input: InputStream, dir: File, bundled: Set<String>): String {
        val staging = File(dir, ".staging-${System.nanoTime()}")
        try {
            extract(input, staging)
            val metaFile = staging.walkTopDown().maxDepth(2).firstOrNull { it.isFile && it.name == META }
                ?: throw IOException("Not an AdreNoTools driver: the zip has no meta.json")
            val root = metaFile.parentFile!!
            val meta = runCatching { JSONObject(metaFile.readText()) }.getOrElse { throw IOException("The driver's meta.json can't be read") }
            val library = meta.optString("libraryName")
            if (library.isBlank() || !File(root, library).isFile) throw IOException("The zip doesn't contain the driver library its meta.json names")
            val name = safeName(meta.optString("name"))
            if (name in bundled) throw IOException("$name already comes with the app")

            val target = File(dir, name)
            target.deleteRecursively()
            if (!root.renameTo(target)) throw IOException("Couldn't install $name")
            return name
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Downloads and installs [asset] from [release], noting where it came from so updates can be found later. */
    fun download(release: Release, asset: Asset, dir: File, bundled: Set<String>): String {
        require(asset.url.startsWith("https://")) { "Driver downloads must use HTTPS" }
        Net.http.newCall(Request.Builder().url(asset.url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} downloading the driver")
            val body = response.body ?: throw IOException("Empty download")
            val name = body.byteStream().use { install(it, dir, bundled) }
            writeOrigin(File(File(dir, name), ORIGIN), Origin(release.repo, release.tag, release.publishedAt, asset.name))
            return name
        }
    }

    /**
     * Newer builds of the downloaded drivers in [installed], from [releases] of the projects they came from: the
     * newest full release of the same project published after the installed one, with the zip that matches the
     * installed zip's name once version numbers are ignored (or the release's only zip).
     */
    internal fun updates(installed: List<Installed>, releases: List<Release>): List<Update> =
        installed.mapNotNull { driver ->
            val origin = driver.origin ?: return@mapNotNull null
            val newest = releases.filter { it.repo == origin.repo && !it.prerelease }.maxByOrNull { it.publishedAt }
                ?: return@mapNotNull null
            if (newest.tag == origin.tag || newest.publishedAt <= origin.publishedAt) return@mapNotNull null
            val asset = newest.assets.firstOrNull { assetFamily(it.name) == assetFamily(origin.asset) }
                ?: newest.assets.singleOrNull()
                ?: return@mapNotNull null
            Update(driver, newest, asset)
        }

    /**
     * A zip name without its version numbers, e.g. "turnip_v25.1.0_gen8.zip" and "turnip_v25.2.1_gen8.zip" match.
     * Only v-prefixed or dotted numbers count as versions, so "gen7" and "gen8" stay apart.
     */
    internal fun assetFamily(name: String) = name.lowercase().replace(VERSION, "#")

    private val VERSION = Regex("""v\d+(?:[._-]\d+)*|\d+(?:[._]\d+)+""")

    /**
     * The project to look in first for this GPU: whitebelyash's mainline builds cover Adreno 8xx (gen8), while
     * K11MCH1's are the usual pick for Adreno 6xx and 7xx.
     */
    fun recommendedSource(adreno8xx: Boolean): Source = if (adreno8xx) SOURCES[0] else SOURCES[1]

    private fun readOrigin(file: File): Origin? = runCatching {
        if (!file.isFile) return null
        val json = JSONObject(file.readText())
        Origin(json.getString("repo"), json.getString("tag"), json.optString("publishedAt"), json.optString("asset"))
    }.getOrNull()

    private fun writeOrigin(file: File, origin: Origin) {
        runCatching {
            file.writeText(
                JSONObject()
                    .put("repo", origin.repo)
                    .put("tag", origin.tag)
                    .put("publishedAt", origin.publishedAt)
                    .put("asset", origin.asset)
                    .toString(),
            )
        }
    }

    fun remove(dir: File, name: String, bundled: Set<String>) {
        require(name !in bundled) { "Bundled drivers can't be removed" }
        File(dir, safeName(name)).deleteRecursively()
    }

    /** A folder name made from a driver's own name: no path separators or leading dots. */
    internal fun safeName(name: String): String {
        val cleaned = name.trim().map { if (it.isLetterOrDigit() || it in "-_.+ ") it else '_' }.joinToString("").trimStart('.').trim()
        if (cleaned.isEmpty()) throw IOException("The driver's meta.json has no name")
        return cleaned
    }

    private fun extract(input: InputStream, into: File) {
        into.mkdirs()
        val base = into.canonicalFile
        ZipInputStream(input.buffered()).use { zip ->
            for (entry in generateSequence { zip.nextEntry }) {
                val target = File(into, entry.name).canonicalFile
                // Ignore entries that would land outside the folder.
                if (!target.path.startsWith(base.path + File.separator)) continue
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                }
            }
        }
    }
}

/** The driver the next launch uses: a name in [TurnipDrivers.driversDir], or null for the GPU's recommended one. */
object DriverChoice {
    fun get(context: Context): String? = SkyrimGraphics.driver(context)

    fun set(context: Context, driver: String?) = SkyrimGraphics.setDriver(context, driver)

    /** The picked driver if it's still installed, else null. */
    fun installed(context: Context): String? =
        get(context)?.takeIf { File(TurnipDrivers.driversDir(context), "$it/meta.json").isFile }
}
