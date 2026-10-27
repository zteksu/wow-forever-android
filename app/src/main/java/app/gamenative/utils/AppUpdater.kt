package app.gamenative.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import app.gamenative.BuildConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

object AppUpdater {
    // Each app (Forever, Retail, OctoWoW) releases on its own branch and tag series (forever-v2.4.0,
    // octowow-v2.3.2, ...), so "latest" may belong to another app: read recent releases and pick our own.
    private const val RELEASES_URL = "https://api.github.com/repos/zteksu/wow-forever-android/releases?per_page=30"

    data class Release(
        val version: String,
        val name: String,
        val notes: String,
        val downloadUrl: String,
        val sizeBytes: Long,
    )

    /** The version in a release tag: "octowow-v2.3.2" and "v2.3.2" both give "2.3.2". */
    fun tagVersion(tag: String): String = tag.replace(Regex("^[A-Za-z]+-"), "").trimStart('v', 'V')

    fun isNewer(latest: String, current: String = BuildConfig.VERSION_NAME): Boolean {
        val parse = { v: String ->
            v.trimStart('v', 'V')
                .split('-', '_', '+')[0]
                .split('.')
                .mapNotNull { it.toIntOrNull() }
        }
        val l = parse(latest)
        val c = parse(current)
        for (i in 0 until maxOf(l.size, c.size)) {
            val lv = l.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (lv > cv) return true
            if (lv < cv) return false
        }
        return false
    }

    // Releases carry one APK per game (WoW-Forever.apk, WoW-Retail.apk), so only take this build's own.
    fun parseRelease(
        jsonString: String,
        currentVersion: String = BuildConfig.VERSION_NAME,
        apkPrefix: String = BuildConfig.RELEASE_APK_PREFIX,
    ): Release? = runCatching {
        val json = JSONObject(jsonString)
        if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
        val tag = json.optString("tag_name")
        val version = tagVersion(tag)
        if (!isNewer(version, currentVersion)) return null

        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            if (name.startsWith(apkPrefix, ignoreCase = true) && name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) {
                return Release(
                    version = version,
                    name = json.optString("name", tag),
                    notes = json.optString("body"),
                    downloadUrl = url,
                    sizeBytes = asset.optLong("size", 0L),
                )
            }
        }
        null
    }.getOrNull()

    /** The highest-versioned release in a GitHub release list that is newer and carries this app's APK. */
    fun parseReleases(
        jsonString: String,
        currentVersion: String = BuildConfig.VERSION_NAME,
        apkPrefix: String = BuildConfig.RELEASE_APK_PREFIX,
    ): Release? = runCatching {
        val releases = JSONArray(jsonString)
        (0 until releases.length())
            .mapNotNull { i -> releases.optJSONObject(i)?.let { parseRelease(it.toString(), currentVersion, apkPrefix) } }
            .reduceOrNull { best, next -> if (isNewer(next.version, best.version)) next else best }
    }.getOrNull()

    suspend fun check(currentVersion: String = BuildConfig.VERSION_NAME): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(RELEASES_URL)
                .header("User-Agent", "${BuildConfig.RELEASE_APK_PREFIX}-Android")
                .header("Accept", "application/vnd.github+json")
                .build()
            Net.http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                parseReleases(body, currentVersion)
            }
        }.getOrElse {
            Timber.w(it, "AppUpdater check failed")
            null
        }
    }

    suspend fun download(
        context: Context,
        release: Release,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val dest = File(dir, "${BuildConfig.RELEASE_APK_PREFIX}-${release.version}.apk")
        Timber.i("AppUpdater download v${release.version} (${release.sizeBytes} bytes) -> $dest")
        if (dest.exists() && dest.length() == release.sizeBytes && release.sizeBytes > 0) {
            Timber.i("AppUpdater using cached APK")
            onProgress(1f)
            return@withContext dest
        }
        Net.fetchFile(release.downloadUrl, dest, onProgress)
        Timber.i("AppUpdater download complete: ${dest.length()} bytes")
        dest
    }

    fun canInstall(context: Context): Boolean = runCatching {
        context.packageManager.canRequestPackageInstalls()
    }.getOrElse {
        Timber.w(it, "canRequestPackageInstalls check failed")
        false
    }

    fun openInstallPermissionSettings(context: Context) {
        Timber.i("AppUpdater opening install-permission settings")
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            }
        }
    }

    fun install(context: Context, apkFile: File) {
        Timber.i("AppUpdater install ${apkFile.name} (${apkFile.length()} bytes, exists=${apkFile.exists()})")
        if (!canInstall(context)) {
            openInstallPermissionSettings(context)
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        Timber.i("AppUpdater package installer launched")
    }
}
