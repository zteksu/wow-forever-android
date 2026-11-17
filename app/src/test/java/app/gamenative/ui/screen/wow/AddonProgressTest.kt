package app.gamenative.ui.screen.wow

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AddonProgressTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun timeLeft_estimatesFromTheShareDoneSoFar() {
        assertEquals("about 30 s left", timeLeft(0.25f, 10_000))
        assertEquals("about 3 min left", timeLeft(0.1f, 20_000))
        assertEquals("almost done", timeLeft(0.999f, 10_000))
    }

    @Test
    fun timeLeft_waitsUntilThereIsEnoughToGoOn() {
        assertNull(timeLeft(0.01f, 10_000))
        assertNull(timeLeft(0.5f, 100))
        assertNull(timeLeft(1f, 10_000))
    }

    @Test
    fun extractAddon_reportsUnpackingProgressUpToTheEnd() {
        val zip = tempFolder.newFile("addon.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("Big/Big.toc"))
            out.write("## Title: Big".toByteArray())
            out.closeEntry()
            repeat(99) {
                out.putNextEntry(ZipEntry("Big/file$it.lua"))
                out.write("-- $it".toByteArray())
                out.closeEntry()
            }
        }
        val reported = mutableListOf<Float>()

        val folders = AddonManager.extractAddon(zip, tempFolder.newFolder("AddOns"), "Big") { reported += it }

        assertEquals(listOf("Big"), folders)
        assertTrue(reported.isNotEmpty())
        assertEquals(reported.sorted(), reported)
        assertTrue(reported.last() <= 1f && reported.last() >= 0.9f)
        assertTrue(File(tempFolder.root, "AddOns/Big/file98.lua").isFile)
    }
}
