package app.gamenative.ui.screen.wow

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkyrimSavesTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    // 2026-10-30 12:00:00 UTC as a Windows FILETIME.
    private val savedAt = 1_793_361_600_000L
    private val fileTime = (savedAt + 11_644_473_600_000L) * 10_000

    /** A Special Edition save: the header, a 2x1 RGBA screenshot, then (made-up) compressed data. */
    private fun ess(name: String = "Lydia", level: Int = 12, location: String = "Whiterun"): ByteArray {
        val header = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(12) // version
            putInt(42) // save number
            wstring(name)
            putInt(level)
            wstring(location)
            wstring("Morndas, 17th of Last Seed, 4E 201")
            wstring("NordRace")
            putShort(1)
            putFloat(10f)
            putFloat(300f)
            putLong(fileTime)
            putInt(2) // shot width
            putInt(1) // shot height
            putShort(2) // LZ4
        }
        val headerBytes = header.array().copyOf(header.position())
        return ByteArrayOutputStream().apply {
            write("TESV_SAVEGAME".toByteArray())
            write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(headerBytes.size).array())
            write(headerBytes)
            write(byteArrayOf(10, 20, 30, 0, 40, 50, 60, 0))
            write(ByteArray(64) { 7 })
        }.toByteArray()
    }

    private fun ByteBuffer.wstring(text: String) {
        putShort(text.length.toShort())
        put(text.toByteArray(Charsets.ISO_8859_1))
    }

    private fun save(game: File, name: String, modified: Long): File =
        File(game, "Saves/$name").apply {
            parentFile!!.mkdirs()
            writeBytes(ess())
            setLastModified(modified)
        }

    @Test
    fun read_parsesTheHeaderAndScreenshot() {
        val file = tempFolder.newFile("Quicksave0.ess").apply { writeBytes(ess()) }
        val save = SkyrimSaves.read(file)

        assertEquals("Lydia", save.name)
        assertEquals(12, save.level)
        assertEquals("Whiterun", save.location)
        assertEquals("Morndas, 17th of Last Seed, 4E 201", save.gameDate)
        assertEquals(42, save.saveNumber)
        assertEquals(savedAt, save.savedAt)
        assertEquals("Quicksave", save.kind)
        assertArrayEquals(intArrayOf(0xFF0A141E.toInt(), 0xFF28323C.toInt()), save.argb())
    }

    @Test
    fun read_rejectsFilesThatArentSaves() {
        val file = tempFolder.newFile("notes.ess").apply { writeText("hello there, this is not a save") }
        assertTrue(runCatching { SkyrimSaves.read(file) }.isFailure)
    }

    @Test
    fun list_putsTheNewestFirstAndSkipsUnreadableFiles() {
        val game = tempFolder.newFolder("Game")
        save(game, "Save1.ess", 1_000_000_000_000)
        save(game, "Save2.ess", 1_000_000_500_000)
        File(game, "Saves/Broken.ess").apply { writeText("junk"); setLastModified(1_000_000_900_000) }

        assertEquals(listOf("Save2.ess", "Save1.ess"), SkyrimSaves.list(game).map { it.file.name })
        assertEquals("Save2.ess", SkyrimSaves.latest(game)?.file?.name)
    }

    @Test
    fun backUp_keepsTheNewestSavesWithSkseDataAndOnlyRunsWhenSomethingChanged() {
        val game = tempFolder.newFolder("Game")
        for (i in 1..7) save(game, "Save$i.ess", 1_000_000_000_000 + i * 1000L)
        File(game, "Saves/Save7.skse").writeText("co-save")

        val first = SkyrimSaves.backUp(game, now = 1_000_000_100_000)
        assertNotNull(first)
        val backup = SkyrimSaves.backups(game).single()
        assertEquals(listOf("Save7.ess", "Save6.ess", "Save5.ess", "Save4.ess", "Save3.ess"), backup.saves)

        // Nothing newer than the last backup.
        assertNull(SkyrimSaves.backUp(game, now = 1_000_000_200_000))

        save(game, "Save8.ess", 1_000_000_300_000)
        assertNotNull(SkyrimSaves.backUp(game, now = 1_000_000_400_000))
        assertEquals(2, SkyrimSaves.backups(game).size)
    }

    @Test
    fun backUp_keepsOnlyTheNewestBackups() {
        val game = tempFolder.newFolder("Game")
        save(game, "Save1.ess", 1_000_000_000_000)
        repeat(SkyrimSaves.BACKUPS_KEPT + 2) { SkyrimSaves.backUp(game, force = true, now = 1_000_000_000_000 + it * 60_000L) }
        assertEquals(SkyrimSaves.BACKUPS_KEPT, SkyrimSaves.backups(game).size)
    }

    @Test
    fun restore_putsSavesBackAndBacksUpTheCurrentOnesFirst() {
        val game = tempFolder.newFolder("Game")
        val quicksave = save(game, "Quicksave0.ess", 1_000_000_000_000)
        val old = SkyrimSaves.backUp(game, force = true, now = 1_000_000_100_000)!!
        quicksave.writeText("overwritten by a bad save")

        SkyrimSaves.restore(game, old)

        assertArrayEquals(ess(), quicksave.readBytes())
        assertEquals(2, SkyrimSaves.backups(game).size)
    }
}
