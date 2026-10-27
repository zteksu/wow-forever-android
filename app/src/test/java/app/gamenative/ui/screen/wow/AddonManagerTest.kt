package app.gamenative.ui.screen.wow

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AddonManagerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun zip(entries: Map<String, String>): File {
        val file = tempFolder.newFile()
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (name, text) ->
                out.putNextEntry(ZipEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    @Test
    fun parseCatalog_readsTocFieldsAndSkipsUnsupportedHosts() {
        val json = """
            [
              {"name": "pfQuest-turtle", "owner": "shagu", "git": "https://github.com/shagu/pfQuest-turtle.git", "branch": "master",
               "toc": {"Title": "|cff33ffccpf|cffffffffQuest |cffcccccc[Turtle]", "Notes": "Turtle data", "Author": "Shagu",
                       "Version": "1.0", "Dependencies": "pfQuest"},
               "lastUpdated": "2026-10-01T00:00:00Z", "stars": 12},
              {"name": "Elsewhere", "git": "https://example.com/x/y.git", "branch": "main", "toc": {}}
            ]
        """.trimIndent()

        val catalog = AddonManager.parseCatalog(json)

        assertEquals(1, catalog.size)
        val addon = catalog.single()
        assertEquals("pfQuest [Turtle]", addon.title)
        assertEquals(listOf("pfQuest"), addon.dependencies)
        assertEquals(12, addon.stars)
    }

    @Test
    fun archiveUrl_coversGithubAndOctowowGitea() {
        assertEquals(
            "https://github.com/pepordev/ConsoleExperienceClassic/archive/refs/heads/main.zip",
            AddonManager.archiveUrl("https://github.com/pepordev/ConsoleExperienceClassic.git", "main"),
        )
        assertEquals(
            "https://git.octowow.st/git/Ade/AdeAlts/archive/main.zip",
            AddonManager.archiveUrl("https://git.octowow.st/git/Ade/AdeAlts.git", "main"),
        )
        assertNull(AddonManager.archiveUrl("http://github.com/a/b.git", "main"))
        assertNull(AddonManager.archiveUrl("https://example.com/a/b.git", "main"))
    }

    @Test
    fun extractAddon_namesFolderAfterRootTocAndPrefersCatalogName() {
        val addons = tempFolder.newFolder("AddOns")
        val archive = zip(
            mapOf(
                "pfquest-master/pfQuest.toc" to "## Title: pfQuest",
                "pfquest-master/pfQuest-tbc.toc" to "## Title: pfQuest TBC",
                "pfquest-master/db/quests.lua" to "-- data",
            ),
        )

        val folders = AddonManager.extractAddon(archive, addons, "pfQuest")

        assertEquals(listOf("pfQuest"), folders)
        assertTrue(File(addons, "pfQuest/pfQuest.toc").isFile)
        assertTrue(File(addons, "pfQuest/db/quests.lua").isFile)
        assertFalse(File(addons, ".install-tmp").exists())
    }

    @Test
    fun extractAddon_installsEachSubfolderAddonAndIgnoresPathTraversal() {
        val addons = tempFolder.newFolder("AddOns")
        val archive = zip(
            mapOf(
                "AtlasLoot-main/AtlasLoot/AtlasLoot.toc" to "## Title: AtlasLoot",
                "AtlasLoot-main/AtlasLoot_Data/AtlasLoot_Data.toc" to "## Title: Data",
                "AtlasLoot-main/README.md" to "readme",
                "../evil.lua" to "nope",
            ),
        )

        val folders = AddonManager.extractAddon(archive, addons, "AtlasLoot")

        assertEquals(setOf("AtlasLoot", "AtlasLoot_Data"), folders.toSet())
        assertTrue(File(addons, "AtlasLoot_Data/AtlasLoot_Data.toc").isFile)
        assertFalse(File(addons.parentFile, "evil.lua").exists())
    }
}
