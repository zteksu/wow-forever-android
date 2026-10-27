package app.gamenative.ui.screen.wow

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OctoClientUpdaterTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    // Three files over 4-byte pieces, so pieces straddle file boundaries.
    private val contents = linkedMapOf(
        "WoW.exe" to "abcdefg".toByteArray(),
        "Data/patch 1.mpq" to "hijklmnopq".toByteArray(),
        "realmlist.wtf" to "rs".toByteArray(),
    )

    private fun torrentBytes(files: Map<String, ByteArray>, pieceLength: Int = 4, seed: String = "https://dl.example/client/"): ByteArray {
        val all = files.values.fold(ByteArray(0)) { acc, b -> acc + b }
        val pieces = all.toList().chunked(pieceLength)
            .map { MessageDigest.getInstance("SHA-1").digest(it.toByteArray()) }
            .fold(ByteArray(0)) { acc, b -> acc + b }
        val out = ByteArrayOutputStream()
        fun str(s: ByteArray) { out.write("${s.size}:".toByteArray()); out.write(s) }
        fun str(s: String) = str(s.toByteArray())
        out.write("d".toByteArray())
        str("info"); out.write("d".toByteArray())
        str("files"); out.write("l".toByteArray())
        for ((path, data) in files) {
            out.write("d".toByteArray())
            str("length"); out.write("i${data.size}e".toByteArray())
            str("path"); out.write("l".toByteArray()); path.split('/').forEach { str(it) }; out.write("e".toByteArray())
            out.write("e".toByteArray())
        }
        out.write("e".toByteArray())
        str("name"); str("client")
        str("piece length"); out.write("i${pieceLength}e".toByteArray())
        str("pieces"); str(pieces)
        out.write("e".toByteArray())
        str("url-list"); str(seed)
        out.write("e".toByteArray())
        return out.toByteArray()
    }

    private fun writeClient(root: File, files: Map<String, ByteArray>) =
        files.forEach { (path, data) -> File(root, path).apply { parentFile?.mkdirs() }.writeBytes(data) }

    @Test
    fun parseTorrent_readsFilesOffsetsAndWebSeed() {
        val torrent = OctoClientUpdater.parseTorrent(torrentBytes(contents))

        assertEquals("client", torrent.name)
        assertEquals(4L, torrent.pieceLength)
        assertEquals(listOf("WoW.exe", "Data/patch 1.mpq", "realmlist.wtf"), torrent.files.map { it.path })
        assertEquals(listOf(0L, 7L, 17L), torrent.files.map { it.offset })
        assertEquals(19L, torrent.totalLength)
        assertEquals(0..1, torrent.pieceRange(torrent.files[0]))
        assertEquals(1..4, torrent.pieceRange(torrent.files[1]))
        assertEquals(3, torrent.pieceSize(4))
        assertEquals("https://dl.example/client/client/Data/patch%201.mpq", torrent.fileUrl(torrent.files[1]))
    }

    @Test
    fun pieceMatches_hashesAcrossFileBoundaries() {
        val root = tempFolder.newFolder("client")
        writeClient(root, contents)
        val torrent = OctoClientUpdater.parseTorrent(torrentBytes(contents))

        assertTrue((0 until 5).all { OctoClientUpdater.pieceMatches(root, torrent, it) })

        File(root, "Data/patch 1.mpq").writeBytes("hijklmnopX".toByteArray())
        assertTrue(OctoClientUpdater.pieceMatches(root, torrent, 1))
        assertFalse(OctoClientUpdater.pieceMatches(root, torrent, 4))
    }

    @Test
    fun unchangedSince_onlyFlagsFilesWhosePiecesChanged() {
        val old = OctoClientUpdater.parseTorrent(torrentBytes(contents))
        val changed = LinkedHashMap(contents).apply { put("realmlist.wtf", "zz".toByteArray()) }
        val new = OctoClientUpdater.parseTorrent(torrentBytes(changed))

        assertTrue(OctoClientUpdater.unchangedSince(old, new, new.files[0]))
        // patch 1.mpq shares its last piece with realmlist.wtf, so it has to be checked too.
        assertFalse(OctoClientUpdater.unchangedSince(old, new, new.files[1]))
        assertFalse(OctoClientUpdater.unchangedSince(old, new, new.files[2]))
        assertTrue(old.infoHash != new.infoHash)
    }
}
