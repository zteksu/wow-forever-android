package app.gamenative.ui.screen.wow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkyrimGraphicsTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun presets_nameARealChoiceForEveryOption() {
        for ((name, choices) in SkyrimGraphics.PRESETS) {
            for (option in SkyrimGraphics.OPTIONS) {
                val label = choices[option.id]
                assertTrue("$name has no ${option.id}", label != null && option.choices.any { it.label == label })
            }
        }
    }

    @Test
    fun defaultPrefs_readBackAsTheMediumPreset() {
        val selection = SkyrimGraphics.read(SkyrimConfig.defaultPrefs("1280x720"))
        assertEquals("Medium", SkyrimGraphics.presetOf(selection))
    }

    @Test
    fun apply_writesEachPresetSoItReadsBack() {
        val base = SkyrimConfig.defaultPrefs("1280x720")
        for ((name, choices) in SkyrimGraphics.PRESETS) {
            val ini = SkyrimGraphics.apply(base, choices)
            assertEquals(name, SkyrimGraphics.presetOf(SkyrimGraphics.read(ini)))
            assertTrue(ini.contains("iSize W=1280\r\n"))
        }
    }

    @Test
    fun read_matchesNumbersWrittenDifferentlyAndReportsCustomMixes() {
        val ini = "[Display]\r\niShadowMapResolution=2048\r\nfShadowDistance=4000\r\nfInteriorShadowDistance=3000.0\r\niBlurDeferredShadowMask=3\r\n"
        assertEquals("High", SkyrimGraphics.read(ini)["shadows"])

        val mixed = SkyrimGraphics.PRESETS.getValue("Medium") + ("ao" to "On")
        assertNull(SkyrimGraphics.presetOf(mixed))
    }

    @Test
    fun saveAndLoad_keepSettingsAsPendingUntilThePrefixExists() {
        val pending = tempFolder.root.resolve("pending.ini")
        val high = SkyrimGraphics.apply(SkyrimConfig.defaultPrefs("1280x720"), SkyrimGraphics.PRESETS.getValue("High"))

        SkyrimGraphics.save(null, pending, high)
        assertEquals(high, SkyrimGraphics.load(null, pending, "1280x720"))

        val documents = tempFolder.newFolder("Documents")
        SkyrimGraphics.save(documents, pending, high)
        assertTrue(!pending.exists())
        assertTrue(SkyrimConfig.isTuned(SkyrimConfig.prefsFile(documents)))
        assertEquals("High", SkyrimGraphics.presetOf(SkyrimGraphics.read(SkyrimGraphics.load(documents, pending, "1280x720"))))
    }
}
