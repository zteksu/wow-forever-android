package app.gamenative.ui.screen.wow

import app.gamenative.BuildConfig
import app.gamenative.ui.screen.wow.WowClientDownloader.TARGET_PRODUCT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WowClientDownloaderTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun readBuildInfo_parsesValidPipeDelimitedFile() {
        val root = tempFolder.newFolder("wow")
        val buildInfo = File(root, ".build.info")
        buildInfo.writeText(
            "Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|CDN Key!HEX:16|Install Key!HEX:16|IM Size!DEC:4|CDN Path!STRING:0|CDN Hosts!STRING:0|Tags!STRING:0|Arm64!DEC:1|Version!STRING:0|Product!STRING:0\n" +
            "us|1|9d5b7a136208535a8dfbc971489e8ae8|b1f5f5904d9c7cb3241bc6eeea50cce8|f68b323351f044ef3c9902641775796b|10332|tpr/wow|level3.blizzard.com|?|1|1.15.5.58238|wow_classic_era\n"
        )

        val result = WowClientDownloader.readBuildInfo(buildInfo)
        assertNotNull(result)
        assertEquals("wow_classic_era", result?.get("Product"))
        assertEquals("1.15.5.58238", result?.get("Version"))
    }

    @Test
    fun checkVersion_rejectsWrongProduct() {
        val root = tempFolder.newFolder("classic_era")
        val buildInfo = File(root, ".build.info")
        buildInfo.writeText(
            "Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|CDN Key!HEX:16|Install Key!HEX:16|IM Size!DEC:4|CDN Path!STRING:0|CDN Hosts!STRING:0|Tags!STRING:0|Arm64!DEC:1|Version!STRING:0|Product!STRING:0\n" +
            "us|1|9d5b7a136208535a8dfbc971489e8ae8|b1f5f5904d9c7cb3241bc6eeea50cce8|f68b323351f044ef3c9902641775796b|10332|tpr/wow|level3.blizzard.com|?|1|1.15.5.58238|wow_classic_era\n"
        )

        val result = WowClientDownloader.checkVersion(root)
        assertNull(result)
    }

    @Test
    fun readBuildInfo_selectsTargetProductFromMultiGameInstall() {
        // MPQ clients (OctoWoW) have no .build.info product to select.
        assumeTrue(BuildConfig.BLIZZARD_CLIENT)
        val root = tempFolder.newFolder("multi_wow")
        val buildInfo = File(root, ".build.info")
        buildInfo.writeText(MULTI_GAME_HEADER + MULTI_GAME_ROWS.values.joinToString(""))

        val result = WowClientDownloader.readBuildInfo(buildInfo)
        assertNotNull(result)
        assertEquals(TARGET_PRODUCT, result?.get("Product"))
        assertEquals(MULTI_GAME_VERSIONS.getValue(TARGET_PRODUCT), result?.get("Version"))
    }

    @Test
    fun readBuildInfo_detectsIncompatibleGameWhenTargetMissingFromMultiGame() {
        // MPQ clients (OctoWoW) have no .build.info product to select.
        assumeTrue(BuildConfig.BLIZZARD_CLIENT)
        val root = tempFolder.newFolder("multi_incompatible")
        val buildInfo = File(root, ".build.info")
        buildInfo.writeText(MULTI_GAME_HEADER + MULTI_GAME_ROWS.filterKeys { it != TARGET_PRODUCT }.values.joinToString(""))

        val result = WowClientDownloader.readBuildInfo(buildInfo)
        assertNotNull(result)
        assertNotEquals(TARGET_PRODUCT, result?.get("Product"))
        val check = WowClientDownloader.checkVersion(root)
        assertNull(check)
    }

    @Test
    fun readBuildInfo_parsesHostInstallationIfExists() {
        val hostFile = File("/Applications/World of Warcraft/.build.info")
        if (!hostFile.isFile || "|$TARGET_PRODUCT" !in hostFile.readText()) return
        val result = WowClientDownloader.readBuildInfo(hostFile)
        assertNotNull(result)
        assertEquals(TARGET_PRODUCT, result?.get("Product"))
    }

    @Test
    fun installedProducts_listsBetaWhenForeverMissing() {
        val buildInfo = File(tempFolder.newFolder("beta_only"), ".build.info")
        buildInfo.writeText(BETA_BUILD_INFO)

        assertEquals(setOf("wow_classic_era", "wow_classic_beta"), WowClientDownloader.installedProducts(buildInfo))
        assertEquals(emptySet<String>(), WowClientDownloader.installedProducts(File(tempFolder.root, "missing")))
    }

    @Test
    fun upsertBuildInfo_addsForeverRowAndKeepsBetaRegion() {
        assumeTrue(TARGET_PRODUCT == "wowf")
        val root = tempFolder.newFolder("beta_upgrade")
        val buildInfo = File(root, ".build.info")
        buildInfo.writeText(BETA_BUILD_INFO)

        WowClientDownloader.upsertBuildInfo(buildInfo, TARGET)

        val lines = buildInfo.readLines()
        assertEquals(4, lines.size)
        assertEquals(BETA_BUILD_INFO.lines().take(3), lines.take(3))
        val row = WowClientDownloader.readBuildInfo(buildInfo)!!
        assertEquals("wowf", row["Product"])
        assertEquals("eu", row["Branch"])
        assertEquals("1", row["Active"])
        assertEquals("1.60.1.70658", row["Version"])
        assertEquals("165410aca770316ea0f88100bb4830c2", row["Build Key"])
        assertEquals("f0fcf03baf658e38f2f6c96a77798a2c", row["CDN Key"])
        assertEquals("level3.blizzard.com us.cdn.blizzard.com", row["CDN Hosts"])
        assertEquals("http://level3.blizzard.com/?maxhosts=8 https://us.cdn.blizzard.com/?maxhosts=4&fallback=1", row["CDN Servers"])
        assertEquals("Windows arm64 EU? enUS speech?:Windows arm64 EU? enUS text?", row["Tags"])
    }

    @Test
    fun upsertBuildInfo_createsFileForFreshInstall() {
        assumeTrue(TARGET_PRODUCT == "wowf")
        val buildInfo = File(tempFolder.newFolder("fresh"), ".build.info")

        WowClientDownloader.upsertBuildInfo(buildInfo, TARGET)

        val lines = buildInfo.readLines()
        assertEquals(2, lines.size)
        assertEquals(15, lines[0].split("|").size)
        assertEquals(15, lines[1].split("|").size)
        val row = WowClientDownloader.readBuildInfo(buildInfo)!!
        assertEquals("wowf", row["Product"])
        assertEquals("tpr/wow", row["CDN Path"])
        assertEquals("1.60.1.70658", row["Version"])
    }

    @Test
    fun upsertBuildInfo_updatesExistingForeverRowInPlace() {
        assumeTrue(TARGET_PRODUCT == "wowf")
        val buildInfo = File(tempFolder.newFolder("update"), ".build.info")
        WowClientDownloader.upsertBuildInfo(buildInfo, TARGET.copy(remoteVersion = "1.60.1.70000", remoteBuildConfig = "0".repeat(32)))

        WowClientDownloader.upsertBuildInfo(buildInfo, TARGET)

        assertEquals(2, buildInfo.readLines().size)
        assertEquals("1.60.1.70658", WowClientDownloader.readBuildInfo(buildInfo)?.get("Version"))
        assertEquals("165410aca770316ea0f88100bb4830c2", WowClientDownloader.readBuildInfo(buildInfo)?.get("Build Key"))
    }

    @Test
    fun readBuildInfo_returnsNullOnMissingFile() {
        val nonExistent = File(tempFolder.root, "does_not_exist")
        assertNull(WowClientDownloader.readBuildInfo(nonExistent))
    }

    private companion object {
        const val MULTI_GAME_HEADER = "Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|CDN Key!HEX:16|Install Key!HEX:16|IM Size!DEC:4|CDN Path!STRING:0|CDN Hosts!STRING:0|Tags!STRING:0|Arm64!DEC:1|Version!STRING:0|Product!STRING:0\n"

        val MULTI_GAME_ROWS = mapOf(
            "wow" to "us|1|11111111111111111111111111111111|22222222222222222222222222222222|33333333333333333333333333333333|10000|tpr/wow|level3.blizzard.com|?|0|11.0.7.58238|wow\n",
            "wow_classic_era" to "us|1|44444444444444444444444444444444|55555555555555555555555555555555|66666666666666666666666666666666|10000|tpr/wow|level3.blizzard.com|?|0|1.15.5.58238|wow_classic_era\n",
            "wow_classic_beta" to "us|1|77777777777777777777777777777777|88888888888888888888888888888888|99999999999999999999999999999999|10000|tpr/wow|level3.blizzard.com|?|1|1.60.1.61118|wow_classic_beta\n",
            "wowf" to "us|1|aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa|bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb|cccccccccccccccccccccccccccccccc|10000|tpr/wow|level3.blizzard.com|?|1|1.60.1.70630|wowf\n",
        )
        val BETA_BUILD_INFO =
            "Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|CDN Key!HEX:16|Install Key!HEX:16|IM Size!DEC:4|CDN Path!STRING:0|CDN Hosts!STRING:0|CDN Servers!STRING:0|Tags!STRING:0|Version!STRING:0|Product!STRING:0\n" +
                "eu|1|77777777777777777777777777777777|88888888888888888888888888888888|||tpr/wow|level3.blizzard.com|http://level3.blizzard.com/?maxhosts=8|OSX x86_64 EU? enUS speech?|1.15.9.70003|wow_classic_era\n" +
                "eu|1|99999999999999999999999999999999|88888888888888888888888888888888|||tpr/wow|level3.blizzard.com|http://level3.blizzard.com/?maxhosts=8|OSX x86_64 EU? enUS speech?|1.60.1.70205|wow_classic_beta\n"

        val TARGET = WowClientDownloader.VersionCheckResult(
            localVersion = "",
            remoteVersion = "1.60.1.70658",
            isOutdated = true,
            remoteBuildConfig = "165410aca770316ea0f88100bb4830c2",
            remoteCdnConfig = "f0fcf03baf658e38f2f6c96a77798a2c",
            remoteCdnHosts = listOf("level3.blizzard.com", "us.cdn.blizzard.com"),
            remoteCdnPath = "tpr/wow",
            remoteCdnServers = listOf("http://level3.blizzard.com/?maxhosts=8", "https://us.cdn.blizzard.com/?maxhosts=4&fallback=1"),
        )

        val MULTI_GAME_VERSIONS = mapOf("wow" to "11.0.7.58238", "wow_classic_era" to "1.15.5.58238", "wow_classic_beta" to "1.60.1.61118", "wowf" to "1.60.1.70630")
    }
}
