package app.gamenative.ui.screen.wow

import android.graphics.Bitmap
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import app.gamenative.ui.theme.WowBronze
import app.gamenative.ui.theme.WowGold
import app.gamenative.ui.theme.WowMuted
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

private fun SkyrimSaves.Save.thumbnail(): ImageBitmap? = runCatching {
    argb()?.let { Bitmap.createBitmap(it, shotWidth, shotHeight, Bitmap.Config.ARGB_8888).asImageBitmap() }
}.getOrNull()

private fun relativeTime(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

/** The newest save above the Play button: the main menu's Continue loads it. */
@Composable
fun ContinueCard(save: SkyrimSaves.Save, onClick: () -> Unit) {
    val thumbnail = remember(save.file, save.savedAt) { save.thumbnail() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth(0.85f)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xCC0F1722))
            .border(1.dp, Color(0xFF2A3A50), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        Thumbnail(thumbnail, width = 128)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("CONTINUE", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = WowGold)
            Text(
                "${save.name} · Level ${save.level}",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(save.location, fontSize = 12.sp, color = Color(0xFFCBD5E1), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${save.kind} · ${relativeTime(save.savedAt)}", fontSize = 11.sp, color = WowMuted)
        }
    }
}

@Composable
private fun Thumbnail(image: ImageBitmap?, width: Int) {
    val modifier = Modifier
        .size(width = width.dp, height = (width * 9 / 16).dp)
        .clip(RoundedCornerShape(8.dp))
        .background(Color(0xFF0B111A))
    if (image != null) {
        Image(bitmap = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
    } else {
        Box(modifier)
    }
}

/** Recent saves and their backups, with Back up now and Restore. */
@Composable
fun SkyrimSavesDialog(gameRoot: File, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var saves by remember { mutableStateOf<List<SkyrimSaves.Save>?>(null) }
    var backups by remember { mutableStateOf<List<SkyrimSaves.Backup>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmRestore by remember { mutableStateOf<SkyrimSaves.Backup?>(null) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(refresh) {
        withContext(Dispatchers.IO) {
            saves = SkyrimSaves.list(gameRoot, limit = 30)
            backups = SkyrimSaves.backups(gameRoot)
        }
    }

    fun run(action: String, done: String, block: () -> Unit) {
        busy = true
        message = action
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching(block).onFailure { Timber.w(it, action) }
            }
            busy = false
            message = result.fold({ done }, { "Failed: ${it.message}" })
            refresh++
            onChanged()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.92f),
        title = {
            Column {
                Text("Saves", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = WowGold)
                Text(
                    message ?: "In ${File(gameRoot, SkyrimSaves.SAVES).path}. Backed up before each launch.",
                    fontSize = 11.sp,
                    color = WowMuted,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val list = saves
                when {
                    list == null -> Text("Reading saves...", fontSize = 13.sp, color = WowMuted)
                    list.isEmpty() -> Text("No saves yet.", fontSize = 13.sp, color = WowMuted)
                    else -> list.forEach { SaveRow(it) }
                }

                Text(
                    "BACKUPS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = WowGold,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    "Each keeps your ${SkyrimSaves.SAVES_PER_BACKUP} newest saves; the last ${SkyrimSaves.BACKUPS_KEPT} are kept in ${SkyrimSaves.BACKUPS}/.",
                    fontSize = 11.sp,
                    color = WowMuted,
                )
                if (backups.isEmpty()) Text("No backups yet.", fontSize = 13.sp, color = WowMuted)
                for (backup in backups) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(backup.createdAt)),
                                fontSize = 13.sp,
                                color = Color(0xFFE2E8F0),
                            )
                            Text(
                                "${backup.saves.size} saves · ${backup.file.length() / (1024 * 1024)} MB",
                                fontSize = 11.sp,
                                color = WowMuted,
                            )
                        }
                        TextButton(onClick = { confirmRestore = backup }, enabled = !busy) {
                            Text("RESTORE", color = WowGold)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { run("Backing up...", "Backed up") { SkyrimSaves.backUp(gameRoot, force = true) } },
                enabled = !busy && !saves.isNullOrEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = WowBronze, contentColor = Color.White),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("BACK UP NOW", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CLOSE", color = WowMuted) }
        },
        containerColor = Color(0xFF1B2838),
        shape = RoundedCornerShape(16.dp),
    )

    confirmRestore?.let { backup ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text("Restore this backup?", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = WowGold) },
            text = {
                Text(
                    "Its ${backup.saves.size} saves go back into Saves/, replacing saves with the same name. " +
                        "Your current newest saves are backed up first, so you can undo this.",
                    fontSize = 13.sp,
                    color = Color(0xFFE2E8F0),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmRestore = null
                        run("Restoring...", "Restored. Load it from the main menu.") { SkyrimSaves.restore(gameRoot, backup.file) }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WowBronze, contentColor = Color.White),
                ) { Text("RESTORE") }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = null }) { Text("CANCEL", color = WowMuted) } },
            containerColor = Color(0xFF1B2838),
        )
    }
}

@Composable
private fun SaveRow(save: SkyrimSaves.Save) {
    val thumbnail = remember(save.file, save.savedAt) { save.thumbnail() }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Thumbnail(thumbnail, width = 96)
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "${save.name} · Level ${save.level}",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(save.location, fontSize = 12.sp, color = Color(0xFFCBD5E1), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${save.kind} · ${relativeTime(save.savedAt)} · ${save.gameDate}",
                fontSize = 11.sp,
                color = WowMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
