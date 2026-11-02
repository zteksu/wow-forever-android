package app.gamenative.ui.screen.wow

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NmsConfigTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    // Shaped like the file the game writes: top-level values, then per-category levels in GraphicsDetail.
    private val settings = """
        <?xml version="1.0" encoding="utf-8"?>
        <Data template="TkGraphicsSettings">
          <Property name="Version" value="4" />
          <Property name="FullScreen" value="true" />
          <Property name="Borderless" value="false" />
          <Property name="UseScreenResolution" value="true" />
          <Property name="ResolutionWidth" value="2560" />
          <Property name="ResolutionHeight" value="1440" />
          <Property name="MaxframeRate" value="144" />
          <Property name="GraphicsDetail" value="TkGraphicsDetailPreset.xml">
            <Property name="TextureQuality" value="TkGraphicsDetailTypes.xml">
              <Property name="GraphicDetail" value="Ultra" />
            </Property>
            <Property name="ShadowQuality" value="TkGraphicsDetailTypes.xml">
              <Property name="GraphicDetail" value="High" />
            </Property>
            <Property name="PlanetQuality" value="TkGraphicsDetailTypes.xml">
              <Property name="GraphicDetail" value="Ultra" />
            </Property>
          </Property>
        </Data>
    """.trimIndent()

    @Test
    fun quality_readsEachCategoryInOrder() {
        assertEquals(
            linkedMapOf("TextureQuality" to "Ultra", "ShadowQuality" to "High", "PlanetQuality" to "Ultra"),
            NmsConfig.quality(settings),
        )
        assertNull(NmsConfig.preset(settings))
        assertEquals("Texture quality", NmsConfig.label("TextureQuality"))
    }

    @Test
    fun setQuality_changesOnlyThatCategory() {
        val xml = NmsConfig.setQuality(settings, "ShadowQuality", "Low")
        assertEquals(linkedMapOf("TextureQuality" to "Ultra", "ShadowQuality" to "Low", "PlanetQuality" to "Ultra"), NmsConfig.quality(xml))
        assertEquals("Medium", NmsConfig.preset(NmsConfig.setAllQuality(xml, "Medium")))
    }

    @Test
    fun setValue_editsExistingPropertiesAndNeverAddsNewOnes() {
        assertEquals("1280", NmsConfig.value(NmsConfig.setValue(settings, "ResolutionWidth", "1280"), "ResolutionWidth"))
        assertEquals(settings, NmsConfig.setValue(settings, "NotASetting", "1"))
    }

    @Test
    fun ensure_matchesTheScreenEveryLaunchButSetsHandheldQualityOnce() {
        val game = tempFolder.newFolder("NMS")
        val file = NmsConfig.settingsFile(game).apply {
            parentFile!!.mkdirs()
            writeText(settings)
        }

        NmsConfig.ensure(game, "1280x720", 30)
        val first = file.readText()
        assertEquals("1280", NmsConfig.value(first, "ResolutionWidth"))
        assertEquals("720", NmsConfig.value(first, "ResolutionHeight"))
        assertEquals("false", NmsConfig.value(first, "UseScreenResolution"))
        assertEquals("true", NmsConfig.value(first, "Borderless"))
        assertEquals("false", NmsConfig.value(first, "FullScreen"))
        assertEquals("30", NmsConfig.value(first, "MaxframeRate"))
        assertEquals("Low", NmsConfig.preset(first))

        // The player's own quality choice survives the next launch.
        file.writeText(NmsConfig.setQuality(first, "TextureQuality", "High"))
        NmsConfig.ensure(game, "1600x900", 40)
        val second = file.readText()
        assertEquals("High", NmsConfig.quality(second)["TextureQuality"])
        assertEquals("1600", NmsConfig.value(second, "ResolutionWidth"))
    }

    @Test
    fun ensure_waitsForTheGameToWriteItsFile() {
        val game = tempFolder.newFolder("NMS")
        NmsConfig.ensure(game, "1280x720", 30)
        assertTrue(!NmsConfig.settingsFile(game).exists())
    }

    @Test
    fun saves_slotsBackupsAndRestore() {
        val game = tempFolder.newFolder("NMS")
        val user = File(game, "Saves/DefaultUser").apply { mkdirs() }
        fun hg(name: String, time: Long) = File(user, name).apply { writeText(name); setLastModified(time) }
        hg("save.hg", 1_000_000_000_000)
        hg("save2.hg", 1_000_000_100_000)
        hg("mf_save2.hg", 1_000_000_100_000)
        hg("save3.hg", 1_000_000_050_000)
        hg("accountdata.hg", 1_000_000_200_000)

        assertEquals(1, NmsSaves.slotOf("save.hg"))
        assertEquals(1, NmsSaves.slotOf("save2.hg"))
        assertEquals(2, NmsSaves.slotOf("save3.hg"))
        assertNull(NmsSaves.slotOf("mf_save2.hg"))
        assertNull(NmsSaves.slotOf("accountdata.hg"))
        assertEquals(listOf("save2.hg", "save3.hg"), NmsSaves.recent(game, 10).map { it.detail })

        val backup = NmsSaves.backUp(game, force = false)!!
        assertEquals(3, NmsSaves.backups(game).single().saves.size)

        File(user, "save2.hg").writeText("broken")
        NmsSaves.restore(game, backup)
        assertEquals("save2.hg", File(user, "save2.hg").readText())
        assertTrue(File(user, "accountdata.hg").isFile)
    }

    @Test
    fun linkFolder_movesTheSavesFolderIntoTheGameFolder() {
        val prefixSaves = File(tempFolder.root, "prefix/AppData/Roaming/HelloGames/NMS/DefaultUser").apply { mkdirs() }
        File(prefixSaves, "save.hg").writeText("slot 1")
        val game = tempFolder.newFolder("NMS")
        val link = prefixSaves.parentFile!!

        SkyrimConfig.linkFolder(link, SkyrimSaves.savesDir(game))

        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertEquals("slot 1", File(game, "Saves/DefaultUser/save.hg").readText())
        assertTrue(File(link.parentFile, "NMS.old/DefaultUser/save.hg").isFile)
    }
}
