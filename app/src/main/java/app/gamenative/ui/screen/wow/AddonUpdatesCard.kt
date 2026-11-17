package app.gamenative.ui.screen.wow

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gamenative.ui.theme.WowError
import app.gamenative.ui.theme.WowGold
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Checks the installed add-ons for updates when the launcher opens and, if any have one, offers to update them all
 * with a progress bar. Shows nothing when everything is up to date. A check that finds nothing isn't repeated for a
 * few hours; one that finds updates is repeated on each open until they're installed.
 */
@Composable
fun AddonUpdatesCard(gameRoot: File, enabled: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var updates by remember(gameRoot) { mutableStateOf<List<AddonManager.Addon>>(emptyList()) }
    var updating by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<AddonManager.Progress?>(null) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(gameRoot) {
        updates = withContext(Dispatchers.IO) { checkForAddonUpdates(context, gameRoot) }
    }

    if (updates.isEmpty() && message == null) return

    Column(
        modifier = modifier
            .fillMaxWidth(0.85f)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xCC0F1722))
            .border(1.dp, Color(0xFF2A3A50), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        when {
            updating -> InstallProgress("Updating add-ons", progress, startedAt)
            updates.isEmpty() -> Text(message.orEmpty(), fontSize = 12.sp, color = if (failed) WowError else WowGold)
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (updates.size == 1) "1 ADD-ON UPDATE" else "${updates.size} ADD-ON UPDATES",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = WowGold,
                    )
                    Text(
                        updates.joinToString { it.title },
                        fontSize = 12.sp,
                        color = Color(0xFFCBD5E1),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    message?.let { Text(it, fontSize = 11.sp, color = WowError, maxLines = 2) }
                }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    enabled = enabled,
                    onClick = {
                        val toUpdate = updates
                        updating = true
                        failed = false
                        message = null
                        progress = null
                        startedAt = System.currentTimeMillis()
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    AddonManager.updateAll(context, gameRoot, toUpdate) { p ->
                                        scope.launch(Dispatchers.Main) { progress = p }
                                    }
                                }
                            }
                            updating = false
                            // Whatever is still out of date (a failed download) stays on the card.
                            updates = withContext(Dispatchers.IO) {
                                toUpdate.filter { AddonManager.state(context, gameRoot, it) == AddonManager.State.UPDATE_AVAILABLE }
                            }
                            failed = result.isFailure
                            message = result.fold(
                                { if (toUpdate.size == 1) "${toUpdate[0].title} updated" else "${toUpdate.size} add-ons updated" },
                                { it.message ?: "Update failed" },
                            )
                        }
                    },
                ) { Text("UPDATE ALL", color = WowGold, fontWeight = FontWeight.Bold) }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
}

private fun checkForAddonUpdates(context: Context, gameRoot: File): List<AddonManager.Addon> {
    if (!AddonManager.isUpdateCheckDue(context)) return emptyList()
    return runCatching { AddonManager.findUpdates(context, gameRoot) }
        .onFailure { Timber.w(it, "Add-on update check failed") }
        .getOrDefault(emptyList())
        .also { if (it.isEmpty()) AddonManager.markUpdateChecked(context) }
}
