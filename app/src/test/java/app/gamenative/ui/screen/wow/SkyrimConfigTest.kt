package app.gamenative.ui.screen.wow

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkyrimConfigTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun prefsFile(documents: File) = File(documents, "My Games/Skyrim Special Edition GOG/SkyrimPrefs.ini")

    @Test
    fun ensure_writesHandheldDefaultsOnFirstLaunch() {
        val documents = tempFolder.newFolder("Documents")

        SkyrimConfig.ensure(documents, "1280x720")

        val text = prefsFile(documents).readText()
        assertTrue(text.contains("iSize W=1280\r\niSize H=720\r\n"))
        assertTrue(text.contains("bGamepadEnable=1"))
        assertTrue(text.contains("bFull Screen=1"))
    }

    @Test
    fun ensure_keepsPlayerSettingsAndMatchesTheResolutionToTheScreen() {
        val documents = tempFolder.newFolder("Documents")
        val prefs = prefsFile(documents).apply {
            parentFile!!.mkdirs()
            writeText("[Display]\r\niSize W=1920\r\niSize H=1080\r\nfGamma=1.2000\r\n")
        }
        File(prefs.parentFile, ".performance-v${SkyrimConfig.PERFORMANCE_VERSION}").createNewFile()

        SkyrimConfig.ensure(documents, "1280x720")
        assertEquals("[Display]\r\niSize W=1280\r\niSize H=720\r\nfGamma=1.2000\r\n", prefs.readText())

        // A smaller size is raised too: the launcher's resolution picks the screen size.
        prefs.writeText("[Display]\r\niSize W=1024\r\niSize H=576\r\n")
        SkyrimConfig.ensure(documents, "1280x720")
        assertEquals("[Display]\r\niSize W=1280\r\niSize H=720\r\n", prefs.readText())
    }

    @Test
    fun setResolution_addsADisplaySectionWhenMissing() {
        assertEquals(
            "[Controls]\nbGamepadEnable=1\n[Display]\niSize W=1280\niSize H=720\n",
            SkyrimConfig.setResolution("[Controls]\nbGamepadEnable=1\n", "1280x720"),
        )
    }

    @Test
    fun ensure_appliesPerformanceSettingsOnceToExistingPrefs() {
        val documents = tempFolder.newFolder("Documents")
        val prefs = prefsFile(documents).apply {
            parentFile!!.mkdirs()
            writeText("[Display]\r\niSize W=1280\r\niSize H=720\r\nfShadowDistance=8000.0000\r\nbDrawLandShadows=1\r\n\r\n[Grass]\r\niMinGrassSize=20\r\n")
        }

        SkyrimConfig.ensure(documents, "1280x720")
        val text = prefs.readText()
        assertTrue(text.contains("[Display]\r\niSize W=1280\r\niSize H=720\r\nfShadowDistance=2000.0000\r\nbDrawLandShadows=0\r\n"))
        assertTrue(text.contains("[Grass]\r\niMinGrassSize=60\r\n"))
        assertTrue(text.contains("[LOD]\r\nfLODFadeOutMultObjects=7.0000\r\n"))
        assertTrue(!text.contains("\n\n") || text.contains("\r\n\r\n"))

        // A setting the player turns back up in game stays that way.
        prefs.writeText(text.replace("bDrawLandShadows=0", "bDrawLandShadows=1"))
        SkyrimConfig.ensure(documents, "1280x720")
        assertTrue(prefs.readText().contains("bDrawLandShadows=1"))
    }

    @Test
    fun ensure_usesSettingsSavedBeforeThePrefixExisted() {
        val documents = tempFolder.newFolder("Documents")
        val pending = tempFolder.newFile("pending.ini").apply { writeText("[Display]\r\nbSAOEnable=1\r\n") }

        SkyrimConfig.ensure(documents, "1280x720", pending)

        assertEquals("[Display]\r\niSize W=1280\r\niSize H=720\r\nbSAOEnable=1\r\n", prefsFile(documents).readText())
        assertTrue(!pending.exists())
    }

    @Test
    fun defaultPrefs_includePerformanceSettings() {
        val text = SkyrimConfig.defaultPrefs("1280x720")
        assertTrue(text.contains("bVolumetricLightingEnable=0"))
        assertTrue(text.contains("[Imagespace]\r\nbDoDepthOfField=0\r\n"))
        assertEquals(1, Regex("(?m)^\\[Display\\]").findAll(text).count())
    }

    @Test
    fun setValue_insertsBeforeTheBlankLineEndingASection() {
        assertEquals(
            "[A]\nx=1\ny=2\n\n[B]\nz=3\n",
            SkyrimConfig.setValue("[A]\nx=1\n\n[B]\nz=3\n", "A", "y", "2"),
        )
        assertEquals("[A]\nx=5\n", SkyrimConfig.setValue("[A]\nX=1\n", "A", "x", "5"))
    }

    @Test
    fun linkSaves_movesExistingSavesIntoTheGameFolderAndLinksThem() {
        val documents = tempFolder.newFolder("Documents")
        val game = tempFolder.newFolder("Game")
        val saves = File(documents, "My Games/Skyrim Special Edition GOG/Saves").apply { mkdirs() }
        File(saves, "Save1.ess").writeText("old")
        File(game, "Saves").mkdirs()
        File(game, "Saves/Save2.ess").writeText("kept")

        SkyrimConfig.linkSaves(documents, game)
        SkyrimConfig.linkSaves(documents, game)

        assertTrue(Files.isSymbolicLink(saves.toPath()))
        assertEquals("old", File(game, "Saves/Save1.ess").readText())
        assertEquals("old", File(saves, "Save1.ess").readText())
        assertEquals("kept", File(saves, "Save2.ess").readText())
        assertTrue(File(saves.parentFile, "Saves.old/Save1.ess").isFile)

        File(saves, "Save3.ess").writeText("new")
        assertTrue(File(game, "Saves/Save3.ess").isFile)
    }

    @Test
    fun applyIntro_editsSkyrimIniOrStartsItFromTheGameDefaults() {
        val documents = tempFolder.newFolder("Documents")
        val game = tempFolder.newFolder("Game")
        val ini = File(documents, "My Games/Skyrim Special Edition GOG/Skyrim.ini")

        // Nothing to start from yet: left for the game to create.
        SkyrimConfig.applyIntro(documents, game, skip = true)
        assertTrue(!ini.exists())

        File(game, "Skyrim_Default.ini").writeText("[General]\r\nsLanguage=ENGLISH\r\n\r\n[Display]\r\nfShadowLODMaxStartFade=1000.0\r\n")
        SkyrimConfig.applyIntro(documents, game, skip = true)
        assertEquals("[General]\r\nsLanguage=ENGLISH\r\nsIntroSequence=\r\n\r\n[Display]\r\nfShadowLODMaxStartFade=1000.0\r\n", ini.readText())

        SkyrimConfig.applyIntro(documents, game, skip = false)
        assertTrue(ini.readText().contains("sIntroSequence=BGS_LOGO.BIK\r\n"))
    }

    @Test
    fun launchExe_usesSkseOnlyWhenInstalledAndWanted() {
        val game = tempFolder.newFolder("Game")
        assertEquals("SkyrimSE.exe", SkyrimConfig.launchExe(game, true, "SkyrimSE.exe"))
        File(game, "skse64_loader.exe").writeText("")
        assertEquals("skse64_loader.exe", SkyrimConfig.launchExe(game, true, "SkyrimSE.exe"))
        assertEquals("SkyrimSE.exe", SkyrimConfig.launchExe(game, false, "SkyrimSE.exe"))
    }
}
