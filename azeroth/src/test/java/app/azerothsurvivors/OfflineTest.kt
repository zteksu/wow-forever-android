package app.azerothsurvivors

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun archivesAreSearchedNewestPatchFirst() {
        val names = listOf("dbc.MPQ", "patch.MPQ", "patch-2.MPQ", "patch-10.mpq", "interface.MPQ", "patch-7.MPQ")
        assertEquals(
            listOf("patch-10.mpq", "patch-7.MPQ", "patch-2.MPQ", "patch.MPQ", "dbc.MPQ", "interface.MPQ"),
            MpqOrder.sort(names.map { File(it) }).map { it.name },
        )
    }

    @Test
    fun archivesAreFoundInTheDownloadsDataFolder() {
        val root = tempFolder.newFolder("unpacked")
        File(root, "AzerothSurvivors/Data").mkdirs()
        File(root, "AzerothSurvivors/AzerothSurvivors.exe").writeText("exe")
        File(root, "AzerothSurvivors/Data/patch.MPQ").writeText("mpq")
        File(root, "AzerothSurvivors/Data/patch-2.MPQ").writeText("mpq")

        assertEquals(listOf("patch-2.MPQ", "patch.MPQ"), MpqOrder.archivesIn(root).map { it.name })
        assertTrue(MpqOrder.archivesIn(tempFolder.newFolder("empty")).isEmpty())
    }

    @Test
    fun dataPathsDecodeToArchiveNames() {
        assertEquals("DBFilesClient\\Spell.dbc", OfflineData.dataName("/data/DBFilesClient%5CSpell.dbc"))
        assertEquals("Interface\\Glue XML\\a+b.lua", OfflineData.dataName("/data/Interface%5CGlue%20XML%5Ca%2Bb.lua"))
        assertNull(OfflineData.dataName("/data/__index"))
        assertNull(OfflineData.dataName("/data/"))
    }

    @Test
    fun browserSensitiveTypesAreRight() {
        assertEquals("text/javascript", OfflineData.mimeFor("wenilla.js"))
        assertEquals("application/wasm", OfflineData.mimeFor("wenilla_bg.wasm"))
        assertEquals("application/octet-stream", OfflineData.mimeFor("DBFilesClient\\Spell.dbc"))
    }

    @Test
    fun savedCopiesAreKeyedByPathAndQuery() {
        val a = OfflineData.keyFor("/rogue.js", "v=1")
        assertEquals(a, OfflineData.keyFor("/rogue.js", "v=1"))
        assertNotEquals(a, OfflineData.keyFor("/rogue.js", "v=2"))
        assertEquals(40, a.length)
    }

    @Test
    fun theBuildStampComesFromThePagesScriptLinks() {
        val html = """<link rel="stylesheet" href="./rogue.css?v=7960c92a7f17"><script type="module" src="./rogue.js?v=7960c92a7f17"></script>"""
        assertEquals("7960c92a7f17", OfflineData.buildStamp(html))
        assertNull(OfflineData.buildStamp("<html><body>No stamps here</body></html>"))
        assertTrue(OfflineData.isDocument("/"))
        assertTrue(!OfflineData.isDocument("/rogue.js"))
    }
}
