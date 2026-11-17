package app.gamenative.ui.screen.wow

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gamenative.BuildConfig
import app.gamenative.ui.theme.WowGold
import app.gamenative.ui.theme.WowMuted
import kotlin.math.roundToInt

/** A session's numbers after the game closes, with play time over the last week. */
@Composable
fun SessionSummaryDialog(summary: GameSessionStats.Summary, history: List<GameSessionStats.Summary>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Session summary", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = WowGold)
                Text(
                    "${BuildConfig.GAME_NAME} · ended ${DateUtils.getRelativeTimeSpanString(summary.endedAt)}",
                    fontSize = 11.sp,
                    color = WowMuted,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Stat("Played", formatDuration(summary.durationSec))
                if (summary.avgFps > 0) {
                    Stat(
                        "Frame rate",
                        "${summary.avgFps.roundToInt()} fps average" +
                            if (summary.lowFps > 0) " · ${summary.lowFps.roundToInt()} fps 1% low" else "",
                    )
                }
                batteryText(summary)?.let { Stat("Battery", it) }
                summary.peakTempC?.let { Stat("Peak battery temp", "${it.roundToInt()} °C") }
                Stat("Last 7 days", formatDuration(GameSessionStats.playTimeSec(history, 7)))
                sessionTip(summary)?.let {
                    Text(it, fontSize = 12.sp, color = Color(0xFFB9C6DA), modifier = Modifier.padding(top = 4.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK", color = WowGold) } },
        containerColor = Color(0xFF1E263B),
        shape = RoundedCornerShape(16.dp),
    )
}

@Composable
private fun Stat(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, fontSize = 13.sp, color = WowMuted, modifier = Modifier.weight(1f))
        Text(value, fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

internal fun formatDuration(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "${seconds}s"
    }
}

internal fun batteryText(summary: GameSessionStats.Summary): String? = when {
    summary.batteryStart < 0 || summary.batteryEnd < 0 -> null
    summary.charged -> "${summary.batteryStart}% → ${summary.batteryEnd}% (charging)"
    else -> "${summary.batteryStart}% → ${summary.batteryEnd}% (−${summary.batteryUsed}%" +
        (summary.batteryPerHour?.let { ", about $it% an hour" } ?: "") + ")"
}

/** One suggestion when the session ran hot or drained fast, else nothing. */
internal fun sessionTip(summary: GameSessionStats.Summary): String? = when {
    (summary.peakTempC ?: 0f) >= 45f ->
        "It ran hot. A lower resolution, a lower frame cap or taking the case off keeps the device from throttling."
    (summary.batteryPerHour ?: 0) >= 35 ->
        "That's a fast drain. 720p and a 30 or 40 fps cap last much longer on battery."
    else -> null
}
