package app.gamenative.ui.screen.wow

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TurnipDriversTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val bundled = setOf("Turnip-V32-RP6sched")

    private fun zip(vararg entries: Pair<String, String>): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { out ->
            for ((name, text) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }

    private fun meta(name: String, library: String = "libvulkan_freedreno.so") =
        """{"schemaVersion":1,"name":"$name","driverVersion":"26.3.0","libraryName":"$library"}"""

    @Test
    fun install_usesTheNameInMetaJsonEvenInsideAFolder() {
        val dir = tempFolder.newFolder("adrenotools")
        val name = TurnipDrivers.install(
            zip("Turnip_v32/meta.json" to meta("Mainline Turnip v32"), "Turnip_v32/libvulkan_freedreno.so" to "elf"),
            dir,
            bundled,
        )

        assertEquals("Mainline Turnip v32", name)
        assertTrue(File(dir, "$name/libvulkan_freedreno.so").isFile)
        assertEquals(listOf("Mainline Turnip v32"), TurnipDrivers.installed(dir, bundled).map { it.name })
        assertEquals(listOf("Mainline Turnip v32"), dir.list()!!.toList())
    }

    @Test
    fun install_refusesZipsThatArentDriversOrClashWithABundledOne() {
        val dir = tempFolder.newFolder("adrenotools")
        val noMeta = runCatching { TurnipDrivers.install(zip("readme.txt" to "hi"), dir, bundled) }
        val noLibrary = runCatching { TurnipDrivers.install(zip("meta.json" to meta("X")), dir, bundled) }
        val clash = runCatching {
            TurnipDrivers.install(zip("meta.json" to meta("Turnip-V32-RP6sched"), "libvulkan_freedreno.so" to "elf"), dir, bundled)
        }

        assertTrue(noMeta.isFailure && noLibrary.isFailure && clash.isFailure)
        assertTrue(dir.list()!!.isEmpty())
    }

    @Test
    fun install_ignoresPathsOutsideTheFolderAndCleansUpNames() {
        val dir = tempFolder.newFolder("adrenotools")
        val name = TurnipDrivers.install(
            zip("meta.json" to meta("../evil/name"), "libvulkan_freedreno.so" to "elf", "../../outside.txt" to "x"),
            dir,
            bundled,
        )

        assertEquals("_evil_name", name)
        assertTrue(!File(tempFolder.root, "outside.txt").exists())
    }

    @Test
    fun parseReleases_keepsZipAssetsAndSkipsDrafts() {
        val json = JSONArray(
            """
            [
              {"tag_name":"tu_v32","name":"Mainline Turnip v32","published_at":"2026-09-19T13:30:00Z","prerelease":false,"body":"A830 notes",
               "assets":[{"name":"turnip_v32.zip","browser_download_url":"https://github.com/a/b/releases/download/tu_v32/turnip_v32.zip","size":12345},
                         {"name":"checksums.txt","browser_download_url":"https://github.com/x","size":10}]},
              {"tag_name":"draft","draft":true,"assets":[{"name":"d.zip","browser_download_url":"https://x/d.zip"}]},
              {"tag_name":"src","name":"Source only","assets":[]}
            ]
            """,
        )
        val releases = TurnipDrivers.parseReleases("a/b", json)

        assertEquals(1, releases.size)
        assertEquals("Mainline Turnip v32", releases[0].title)
        assertEquals("2026-09-19", releases[0].publishedAt)
        assertEquals(listOf("turnip_v32.zip"), releases[0].assets.map { it.name })
    }
}
