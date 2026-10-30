package app.gamenative.ui.screen.wow

import java.io.File
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
    fun ensure_keepsPlayerSettingsAndOnlyFixesAResolutionThatNoLongerFits() {
        val documents = tempFolder.newFolder("Documents")
        val prefs = prefsFile(documents).apply {
            parentFile!!.mkdirs()
            writeText("[Display]\r\niSize W=1920\r\niSize H=1080\r\nfGamma=1.2000\r\n")
        }

        SkyrimConfig.ensure(documents, "1280x720")
        assertEquals("[Display]\r\niSize W=1280\r\niSize H=720\r\nfGamma=1.2000\r\n", prefs.readText())

        // A size that fits is left alone.
        prefs.writeText("[Display]\r\niSize W=1024\r\niSize H=576\r\n")
        SkyrimConfig.ensure(documents, "1280x720")
        assertEquals("[Display]\r\niSize W=1024\r\niSize H=576\r\n", prefs.readText())
    }

    @Test
    fun fitResolution_addsADisplaySectionWhenMissing() {
        assertEquals(
            "[Controls]\nbGamepadEnable=1\n[Display]\niSize W=1280\niSize H=720\n",
            SkyrimConfig.fitResolution("[Controls]\nbGamepadEnable=1\n", "1280x720"),
        )
    }
}
