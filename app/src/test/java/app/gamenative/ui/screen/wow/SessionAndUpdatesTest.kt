package app.gamenative.ui.screen.wow

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SessionAndUpdatesTest {
    private fun summary(
        durationSec: Long = 3600,
        start: Int = 80,
        end: Int = 60,
        charged: Boolean = false,
        temp: Float? = 40f,
        endedAt: Long = 1_000_000_000L,
    ) = GameSessionStats.Summary(endedAt, durationSec, 58.4f, 41.2f, start, end, charged, temp)

    @Test
    fun summary_roundTripsThroughJson() {
        val original = summary()
        assertEquals(original, GameSessionStats.Summary.fromJson(JSONObject(original.toJson().toString())))
        val noTemp = summary(temp = null)
        assertNull(GameSessionStats.Summary.fromJson(noTemp.toJson()).peakTempC)
    }

    @Test
    fun batteryText_showsUseAndHourlyRateUnlessCharging() {
        assertEquals("80% → 60% (−20%, about 20% an hour)", batteryText(summary()))
        assertEquals("80% → 85% (charging)", batteryText(summary(end = 85, charged = true)))
        assertEquals("80% → 79% (−1%)", batteryText(summary(durationSec = 300, end = 79)))
        assertNull(batteryText(summary(start = -1)))
    }

    @Test
    fun sessionTip_flagsHeatBeforeDrain() {
        assertTrue(sessionTip(summary(temp = 46f))!!.startsWith("It ran hot"))
        assertTrue(sessionTip(summary(end = 40))!!.startsWith("That's a fast drain"))
        assertNull(sessionTip(summary()))
    }

    @Test
    fun playTime_countsOnlyTheLastDays() {
        val now = 10 * 86_400_000L
        val history = listOf(
            summary(durationSec = 3600, endedAt = now - 86_400_000L),
            summary(durationSec = 1800, endedAt = now - 2 * 86_400_000L),
            summary(durationSec = 7200, endedAt = now - 9 * 86_400_000L),
        )
        assertEquals(5400L, GameSessionStats.playTimeSec(history, 7, now))
        assertEquals("1h 30m", formatDuration(5400))
        assertEquals("12m", formatDuration(720))
    }

    @Test
    fun pendingUpdates_keepsEachOutdatedAddonOnce() {
        fun addon(name: String) = AddonManager.Addon(name, name, "", "", "", "", "", emptyList(), "2", 0)
        val candidates = listOf(addon("Details"), addon("details"), addon("WeakAuras"), addon("Bagnon"))
        val outdated = setOf("details", "bagnon")

        val updates = AddonManager.pendingUpdates(candidates) {
            if (it.name.lowercase() in outdated) AddonManager.State.UPDATE_AVAILABLE else AddonManager.State.INSTALLED
        }

        assertEquals(listOf("Details", "Bagnon"), updates.map { it.name })
    }
}
