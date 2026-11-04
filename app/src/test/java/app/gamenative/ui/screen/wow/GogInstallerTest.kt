package app.gamenative.ui.screen.wow

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GogInstallerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun parts_findTheBinFilesInOrder() {
        val dir = tempFolder.newFolder("Downloads")
        val exe = File(dir, "setup_no_mans_sky_5.0.exe").apply { writeText("exe") }
        for (name in listOf("setup_no_mans_sky_5.0-10.bin", "setup_no_mans_sky_5.0-2.bin", "setup_no_mans_sky_5.0-1.bin", "setup_other-1.bin", "notes.txt")) {
            File(dir, name).writeText("x")
        }

        assertEquals(
            listOf("setup_no_mans_sky_5.0.exe", "setup_no_mans_sky_5.0-1.bin", "setup_no_mans_sky_5.0-2.bin", "setup_no_mans_sky_5.0-10.bin"),
            GogInstaller.parts(exe).map { it.name },
        )
        assertEquals((6 * GogInstaller.SPACE_FACTOR).toLong(), GogInstaller.requiredBytes(GogInstaller.parts(exe)))
    }

    @Test
    fun parseProgress_readsInnoextractsProgressBar() {
        assertEquals(0.453f, GogInstaller.parseProgress("[=========           ]  45.3% 12.1 MiB/s")!!, 0.0001f)
        assertEquals(1f, GogInstaller.parseProgress("100%")!!, 0.0001f)
        assertNull(GogInstaller.parseProgress(" - \"app/Binaries/NMS.exe\""))
    }

    @Test
    fun findGameRoot_findsTheFolderHoldingTheExecutable() {
        val staging = tempFolder.newFolder("staging")
        File(staging, "tmp/redist.exe").apply { parentFile!!.mkdirs(); writeText("x") }
        File(staging, "app/Binaries/NMS.exe").apply { parentFile!!.mkdirs(); writeText("x") }

        assertEquals(File(staging, "app"), GogInstaller.findGameRoot(staging, "Binaries/NMS.exe"))
        assertNull(GogInstaller.findGameRoot(staging, "SkyrimSE.exe"))
    }

    @Test
    fun destination_usesAnEmptyOrMatchingFolderElseASubfolder() {
        val empty = tempFolder.newFolder("Games")
        assertEquals(empty, GogInstaller.destination(empty, "No Man's Sky"))
        File(empty, "Other Game").mkdirs()
        assertEquals(File(empty, "No Man's Sky"), GogInstaller.destination(empty, "No Man's Sky"))
        val named = tempFolder.newFolder("No Man's Sky")
        assertEquals(named, GogInstaller.destination(named, "No Man's Sky"))
    }

    @Test
    fun moveInto_movesTheGameAndRefusesAFolderInUse() {
        val root = tempFolder.newFolder("staging", "app")
        File(root, "Binaries/NMS.exe").apply { parentFile!!.mkdirs(); writeText("game") }
        File(root, "GAMEDATA/PCBANKS/a.pak").apply { parentFile!!.mkdirs(); writeText("data") }
        val target = File(tempFolder.root, "Roms/No Man's Sky")

        GogInstaller.moveInto(root, target)
        assertEquals("game", File(target, "Binaries/NMS.exe").readText())
        assertTrue(File(target, "GAMEDATA/PCBANKS/a.pak").isFile)
        assertTrue(root.listFiles()!!.isEmpty())

        assertTrue(runCatching { GogInstaller.moveInto(tempFolder.newFolder("again"), target) }.isFailure)
    }
}
