package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {

    @Test
    fun isNewer_higherPatch_returnsTrue() {
        assertTrue(AppUpdater.isNewer("v2.1.3", "2.1.2"))
    }

    @Test
    fun isNewer_higherMinor_returnsTrue() {
        assertTrue(AppUpdater.isNewer("2.2.0", "2.1.2"))
    }

    @Test
    fun isNewer_higherMajor_returnsTrue() {
        assertTrue(AppUpdater.isNewer("v3.0.0", "2.1.2"))
    }

    @Test
    fun isNewer_sameVersion_returnsFalse() {
        assertFalse(AppUpdater.isNewer("v2.1.2", "2.1.2"))
        assertFalse(AppUpdater.isNewer("2.1.2", "2.1.2"))
    }

    @Test
    fun isNewer_olderVersion_returnsFalse() {
        assertFalse(AppUpdater.isNewer("v2.1.1", "2.1.2"))
        assertFalse(AppUpdater.isNewer("1.9.9", "2.1.2"))
    }

    @Test
    fun isNewer_extraSegments_comparesCorrectly() {
        assertTrue(AppUpdater.isNewer("v2.1.2.1", "2.1.2"))
        assertFalse(AppUpdater.isNewer("2.1", "2.1.2"))
    }

    @Test
    fun isNewer_handlesPrereleaseSuffix() {
        assertFalse(AppUpdater.isNewer("v2.1.2-beta1", "2.1.2"))
        assertTrue(AppUpdater.isNewer("v2.1.3-rc1", "2.1.2"))
    }

    @Test
    fun parseRelease_validNewReleaseWithApk_parsesSuccessfully() {
        val json = """
            {
                "tag_name": "v2.1.3",
                "name": "WoW Forever v2.1.3",
                "body": "Bug fixes and improvements",
                "assets": [
                    {
                        "name": "WoW-Forever.apk",
                        "browser_download_url": "https://github.com/jaredgei/wow-forever-android/releases/download/v2.1.3/WoW-Forever.apk",
                        "size": 160000000
                    }
                ]
            }
        """.trimIndent()

        val release = AppUpdater.parseRelease(json, "2.1.2", "WoW-Forever")

        assertNotNull(release)
        assertEquals("2.1.3", release?.version)
        assertEquals("WoW Forever v2.1.3", release?.name)
        assertEquals("Bug fixes and improvements", release?.notes)
        assertEquals("https://github.com/jaredgei/wow-forever-android/releases/download/v2.1.3/WoW-Forever.apk", release?.downloadUrl)
        assertEquals(160000000L, release?.sizeBytes)
    }

    @Test
    fun parseRelease_multiGameRelease_picksOwnApk() {
        val json = """
            {
                "tag_name": "v2.3.0",
                "name": "v2.3.0",
                "assets": [
                    {
                        "name": "WoW-Forever.apk",
                        "browser_download_url": "https://example.com/WoW-Forever.apk",
                        "size": 1
                    },
                    {
                        "name": "WoW-Retail.apk",
                        "browser_download_url": "https://example.com/WoW-Retail.apk",
                        "size": 2
                    }
                ]
            }
        """.trimIndent()

        assertEquals("https://example.com/WoW-Forever.apk", AppUpdater.parseRelease(json, "2.2.6", "WoW-Forever")?.downloadUrl)
        assertEquals("https://example.com/WoW-Retail.apk", AppUpdater.parseRelease(json, "2.2.6", "WoW-Retail")?.downloadUrl)
    }

    @Test
    fun tagVersion_stripsAppPrefix() {
        assertEquals("2.3.2", AppUpdater.tagVersion("octowow-v2.3.2"))
        assertEquals("2.4.0", AppUpdater.tagVersion("forever-v2.4.0"))
        assertEquals("2.3.1", AppUpdater.tagVersion("v2.3.1"))
        assertEquals("2.1.2-beta1", AppUpdater.tagVersion("v2.1.2-beta1"))
    }

    @Test
    fun parseReleases_picksNewestReleaseWithOwnApk() {
        fun release(tag: String, asset: String, draft: Boolean = false) = """
            {"tag_name": "$tag", "name": "$tag", "draft": $draft, "prerelease": false,
             "assets": [{"name": "$asset", "browser_download_url": "https://example.com/$tag/$asset", "size": 1}]}
        """
        val json = "[" + listOf(
            release("forever-v2.5.0", "WoW-Forever.apk"),
            release("octowow-v2.4.0", "OctoWoW.apk", draft = true),
            release("octowow-v2.3.2", "OctoWoW.apk"),
            release("octowow-v2.3.3", "OctoWoW.apk"),
            release("v2.3.1", "OctoWoW.apk"),
        ).joinToString(",") + "]"

        val octo = AppUpdater.parseReleases(json, "2.3.1", "OctoWoW")
        assertEquals("2.3.3", octo?.version)
        assertEquals("https://example.com/octowow-v2.3.3/OctoWoW.apk", octo?.downloadUrl)
        assertEquals("2.5.0", AppUpdater.parseReleases(json, "2.3.1", "WoW-Forever")?.version)
        assertNull(AppUpdater.parseReleases(json, "2.3.1", "WoW-Retail"))
        assertNull(AppUpdater.parseReleases(json, "2.3.3", "OctoWoW"))
    }

    @Test
    fun parseRelease_sameOrOlderVersion_returnsNull() {
        val json = """
            {
                "tag_name": "v2.1.2",
                "name": "WoW Forever v2.1.2",
                "body": "Existing release",
                "assets": [
                    {
                        "name": "WoW-Forever.apk",
                        "browser_download_url": "https://github.com/jaredgei/wow-forever-android/releases/download/v2.1.2/WoW-Forever.apk",
                        "size": 160000000
                    }
                ]
            }
        """.trimIndent()

        assertNull(AppUpdater.parseRelease(json, "2.1.2", "WoW-Forever"))
    }

    @Test
    fun parseRelease_noApkAsset_returnsNull() {
        val json = """
            {
                "tag_name": "v2.1.3",
                "name": "WoW Forever v2.1.3",
                "body": "Source only release",
                "assets": [
                    {
                        "name": "source.tar.gz",
                        "browser_download_url": "https://example.com/source.tar.gz",
                        "size": 5000
                    }
                ]
            }
        """.trimIndent()

        assertNull(AppUpdater.parseRelease(json, "2.1.2", "WoW-Forever"))
    }

    @Test
    fun parseRelease_invalidJson_returnsNull() {
        assertNull(AppUpdater.parseRelease("not-json", "2.1.2", "WoW-Forever"))
    }
}
