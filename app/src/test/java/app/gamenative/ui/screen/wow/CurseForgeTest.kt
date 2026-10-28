package app.gamenative.ui.screen.wow

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CurseForgeTest {
    @Test
    fun pickVersionType_findsForeverAndRetail() {
        val types = JSONArray(
            """[{"id": 67408, "name": "WoW Classic", "slug": "wow_classic"},
                {"id": 517, "name": "WoW Retail", "slug": "wow_retail"},
                {"id": 88568, "name": "WoW Forever", "slug": "wow-forever"}]""",
        )
        assertEquals(88568, CurseForge.pickVersionType(types, "forever"))
        assertEquals(517, CurseForge.pickVersionType(types, "mainline"))
        assertNull(CurseForge.pickVersionType(JSONArray(), "forever"))
    }

    private val mod = JSONObject(
        """{"id": 334372, "name": "Leatrix Plus", "slug": "leatrix-plus", "summary": "Quality of life",
            "downloadCount": 12500000, "authors": [{"name": "Leatrix"}],
            "links": {"websiteUrl": "https://www.curseforge.com/wow/addons/leatrix-plus"},
            "latestFilesIndexes": [
              {"fileId": 900, "filename": "LeatrixPlus-1.60.04-forever.zip", "releaseType": 1, "gameVersionTypeId": 88568},
              {"fileId": 950, "filename": "LeatrixPlus-1.60.05-forever-beta.zip", "releaseType": 2, "gameVersionTypeId": 88568},
              {"fileId": 920, "filename": "LeatrixPlus-1.60.05-forever.zip", "releaseType": 1, "gameVersionTypeId": 88568},
              {"fileId": 999, "filename": "LeatrixPlus-12.0.zip", "releaseType": 1, "gameVersionTypeId": 517}
            ]}""",
    )

    @Test
    fun pickFile_takesTheNewestReleaseForTheClient() {
        assertEquals(920, CurseForge.pickFile(mod, 88568)!!.getInt("fileId"))
        assertEquals(999, CurseForge.pickFile(mod, 517)!!.getInt("fileId"))
        assertNull(CurseForge.pickFile(mod, 1))
    }

    @Test
    fun toAddon_carriesTheIdsForInstallAndUpdates() {
        val addon = CurseForge.toAddon(mod, 88568)!!
        assertEquals("LeatrixPlus", addon.name)
        assertEquals("Leatrix Plus", addon.title)
        assertEquals(334372, addon.curseModId)
        assertEquals(920, addon.curseFileId)
        assertEquals("920", addon.lastUpdated)
        assertEquals("1.60.05-forever", addon.version)
        assertEquals("CurseForge", addon.source)
    }

    @Test
    fun merge_prefersTheGitHubEntryForTheSameAddon() {
        val github = AddonManager.Addon("Questie", "Questie", "", "", "", "", "", emptyList(), "", 0, repo = "Questie/Questie")
        val curseQuestie = github.copy(repo = "", curseModId = 1)
        val curseLeatrix = CurseForge.toAddon(mod, 88568)!!
        assertEquals(listOf("Questie", "LeatrixPlus"), AddonManager.merge(listOf(github), listOf(curseQuestie, curseLeatrix)).map { it.name })
    }
}
