package app.gamenative.ui.screen.wow

import android.content.Context
import app.gamenative.utils.Net
import java.io.IOException
import java.net.URLEncoder
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * CurseForge's addon catalog, through its official API. The API needs a key from the player's own
 * CurseForge for Studios account (console.curseforge.com); it is kept on the device and only sent to
 * api.curseforge.com.
 */
object CurseForge {
    private const val API = "https://api.curseforge.com/v1"
    private const val WOW_GAME_ID = 1
    private const val PREFS = "addon_sources"
    private const val KEY_API_KEY = "curseforge_api_key"
    private const val SORT_POPULARITY = 2
    private const val RELEASE = 1
    private const val REQUIRED_DEPENDENCY = 3

    // The site's own Forever and Retail filters, used if the version-type list doesn't name them.
    private const val FOREVER_VERSION_TYPE = 88568
    private const val RETAIL_VERSION_TYPE = 517

    private var versionType: Int? = null

    fun apiKey(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }

    fun setApiKey(context: Context, key: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (key.isNullOrBlank()) remove(KEY_API_KEY) else putString(KEY_API_KEY, key.trim())
        }.apply()
        versionType = null
    }

    private fun get(key: String, path: String): JSONObject {
        val request = Request.Builder().url("$API$path").header("x-api-key", key).header("Accept", "application/json").build()
        Net.http.newCall(request).execute().use { response ->
            when (response.code) {
                200 -> return JSONObject(response.body?.string() ?: throw IOException("Empty CurseForge response"))
                401, 403 -> throw IOException("CurseForge refused the API key (HTTP ${response.code})")
                else -> throw IOException("HTTP ${response.code} from CurseForge")
            }
        }
    }

    /** Checks a key by asking for WoW's version types; returns the one for this client. */
    fun verify(key: String): Int = versionTypeId(key)

    internal fun pickVersionType(types: JSONArray, flavor: String): Int? {
        val wanted = if (flavor == "mainline") listOf("wow_retail", "retail") else listOf("forever")
        for (i in 0 until types.length()) {
            val type = types.optJSONObject(i) ?: continue
            val slug = type.optString("slug").lowercase()
            val name = type.optString("name").lowercase()
            if (wanted.any { slug.contains(it) || name.contains(it) }) return type.optInt("id")
        }
        return null
    }

    private fun versionTypeId(key: String): Int {
        versionType?.let { return it }
        val flavor = AddonManager.clientFlavor()
        val types = get(key, "/games/$WOW_GAME_ID/version-types").optJSONArray("data") ?: JSONArray()
        val id = pickVersionType(types, flavor) ?: if (flavor == "mainline") RETAIL_VERSION_TYPE else FOREVER_VERSION_TYPE
        versionType = id
        return id
    }

    /** The newest file for [versionType], releases before betas and alphas. */
    internal fun pickFile(mod: JSONObject, versionType: Int): JSONObject? {
        val indexes = mod.optJSONArray("latestFilesIndexes") ?: return null
        return (0 until indexes.length())
            .mapNotNull { indexes.optJSONObject(it) }
            .filter { it.optInt("gameVersionTypeId") == versionType }
            .sortedWith(compareBy<JSONObject> { it.optInt("releaseType", RELEASE) }.thenByDescending { it.optInt("fileId") })
            .firstOrNull()
    }

    internal fun toAddon(mod: JSONObject, versionType: Int): AddonManager.Addon? {
        val file = pickFile(mod, versionType) ?: return null
        val authors = mod.optJSONArray("authors")
        val author = (0 until (authors?.length() ?: 0)).mapNotNull { authors?.optJSONObject(it)?.optString("name") }.joinToString()
        val title = mod.optString("name")
        val fileName = file.optString("filename")
        return AddonManager.Addon(
            name = title.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.ifBlank { mod.optString("slug") },
            title = title,
            notes = mod.optString("summary"),
            author = author,
            version = AddonManager.versionOf(fileName),
            git = mod.optJSONObject("links")?.optString("websiteUrl").orEmpty(),
            branch = "",
            dependencies = emptyList(),
            lastUpdated = file.optInt("fileId").toString(),
            stars = 0,
            curseModId = mod.optInt("id"),
            curseFileId = file.optInt("fileId"),
            downloads = mod.optLong("downloadCount"),
        )
    }

    /** The most popular addons for this client, or those matching [query]. */
    fun search(context: Context, query: String = ""): List<AddonManager.Addon> {
        val key = apiKey(context) ?: return emptyList()
        val type = versionTypeId(key)
        val filter = if (query.isBlank()) "" else "&searchFilter=" + URLEncoder.encode(query.trim(), "UTF-8")
        val json = get(
            key,
            "/mods/search?gameId=$WOW_GAME_ID&gameVersionTypeId=$type&sortField=$SORT_POPULARITY&sortOrder=desc&pageSize=50$filter",
        )
        val mods = json.optJSONArray("data") ?: return emptyList()
        return (0 until mods.length()).mapNotNull { mods.optJSONObject(it)?.let { mod -> toAddon(mod, type) } }
    }

    /**
     * Where to download [addon]'s file. Authors can turn off downloads through apps; then the player is
     * sent to the addon's page.
     */
    fun downloadUrl(context: Context, addon: AddonManager.Addon): String {
        val key = apiKey(context) ?: throw IOException("Add a CurseForge API key first")
        val url = runCatching {
            get(key, "/mods/${addon.curseModId}/files/${addon.curseFileId}/download-url").optString("data")
        }.getOrNull()
        if (url.isNullOrBlank() || url == "null") {
            throw IOException("${addon.title}'s author doesn't allow downloads through apps. Get it from ${addon.git.ifBlank { "curseforge.com" }}")
        }
        return url
    }

    /** Addons [addon]'s file requires, ready to install. */
    fun requiredDependencies(context: Context, addon: AddonManager.Addon): List<AddonManager.Addon> {
        val key = apiKey(context) ?: return emptyList()
        val type = versionTypeId(key)
        val file = get(key, "/mods/${addon.curseModId}/files/${addon.curseFileId}").optJSONObject("data") ?: return emptyList()
        val deps = file.optJSONArray("dependencies") ?: return emptyList()
        return (0 until deps.length())
            .mapNotNull { deps.optJSONObject(it) }
            .filter { it.optInt("relationType") == REQUIRED_DEPENDENCY }
            .mapNotNull { dep -> runCatching { get(key, "/mods/${dep.optInt("modId")}").optJSONObject("data") }.getOrNull() }
            .mapNotNull { toAddon(it, type) }
    }
}
