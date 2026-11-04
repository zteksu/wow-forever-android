package app.gamenative.ui.screen.wow

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import app.gamenative.BuildConfig
import app.gamenative.ui.theme.WowBronze
import app.gamenative.ui.theme.WowGold
import app.gamenative.ui.theme.WowMuted
import app.gamenative.utils.StorageUtils
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Install the game from GOG's offline installer files, then use the result as the game folder. */
@Composable
fun GogInstallDialog(marker: String, onInstalled: (File) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val state by GogInstaller.state.collectAsState()
    var exe by remember { mutableStateOf<File?>(null) }
    var parts by remember { mutableStateOf<List<File>>(emptyList()) }
    var destination by remember { mutableStateOf<File?>(null) }
    var pickError by remember { mutableStateOf<String?>(null) }
    var deleted by remember { mutableStateOf(false) }

    // Unpacking a big game takes a while: keep the screen on so Android doesn't put the app to sleep.
    DisposableEffect(state.running) {
        view.keepScreenOn = state.running
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(state.installed) {
        state.installed?.let(onInstalled)
    }

    val exePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = StorageUtils.getPathFromDocumentUri(context, uri)
        if (path == null || !path.endsWith(".exe", ignoreCase = true)) {
            pickError = "Pick the setup .exe through your device's storage (Odin storage or the SD card), not a shortcut like Recent."
            return@rememberLauncherForActivityResult
        }
        pickError = null
        val file = File(path)
        exe = file
        parts = GogInstaller.parts(file)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val path = StorageUtils.getPathFromTreeUri(context, uri) ?: return@rememberLauncherForActivityResult
        destination = GogInstaller.destination(File(path), BuildConfig.DEFAULT_GAME_FOLDER)
    }

    AlertDialog(
        onDismissRequest = { if (!state.running) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.92f),
        title = { Text("Install from GOG installer", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = WowGold) },
        text = {
            Column {
                Text(
                    "Use the offline installer from your GOG library (setup_….exe with its .bin files in the same folder). " +
                        "It's unpacked directly on the device, without running the Windows installer.",
                    fontSize = 12.sp,
                    color = Color(0xFFCBD5E1),
                )
                Spacer(Modifier.height(10.dp))

                val file = exe
                Row {
                    Column(Modifier.weight(1f)) {
                        Text("Installer", fontSize = 11.sp, color = WowMuted)
                        Text(
                            file?.let { "${it.name} + ${parts.size - 1} .bin · ${"%.1f".format(parts.sumOf { p -> p.length() } / 1_073_741_824.0)} GB" }
                                ?: "Not picked",
                            fontSize = 13.sp,
                            color = Color.White,
                        )
                    }
                    TextButton(onClick = { exePicker.launch(arrayOf("*/*")) }, enabled = !state.running) { Text("PICK", color = WowGold) }
                }
                Row {
                    Column(Modifier.weight(1f)) {
                        Text("Install to", fontSize = 11.sp, color = WowMuted)
                        Text(destination?.path ?: "Not picked", fontSize = 13.sp, color = Color.White)
                    }
                    TextButton(onClick = { folderPicker.launch(null) }, enabled = !state.running) { Text("PICK", color = WowGold) }
                }
                if (file != null) {
                    Text(
                        "Needs about ${"%.1f".format(GogInstaller.requiredBytes(parts) / 1_073_741_824.0)} GB free where it installs.",
                        fontSize = 11.sp,
                        color = WowMuted,
                    )
                }

                pickError?.let { Text(it, fontSize = 12.sp, color = Color(0xFFF87171), modifier = Modifier.padding(top = 8.dp)) }
                if (state.running || state.status.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    if (state.running) {
                        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth(), color = WowGold)
                        Spacer(Modifier.height(6.dp))
                    }
                    Text(state.status, fontSize = 12.sp, color = Color.White)
                    if (state.running) {
                        Text("Keep the app open; this can take a while for a big game.", fontSize = 11.sp, color = WowMuted)
                    }
                }
                state.error?.let { Text(it, fontSize = 12.sp, color = Color(0xFFF87171), modifier = Modifier.padding(top = 8.dp)) }
                if (state.installed != null && file != null && !deleted) {
                    TextButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { parts.forEach { it.delete() } }
                            deleted = true
                        }
                    }) { Text("DELETE THE INSTALLER FILES (frees ${"%.1f".format(parts.sumOf { it.length() } / 1_073_741_824.0)} GB)", color = WowGold) }
                }
                if (deleted) Text("Installer files deleted.", fontSize = 12.sp, color = WowMuted)
            }
        },
        confirmButton = {
            when {
                state.running -> TextButton(onClick = { GogInstaller.cancel() }) { Text("CANCEL", color = WowGold) }
                state.installed != null -> Button(
                    onClick = {
                        GogInstaller.reset()
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WowBronze, contentColor = Color.White),
                    shape = RoundedCornerShape(10.dp),
                ) { Text("DONE", fontWeight = FontWeight.Bold) }
                else -> Button(
                    onClick = {
                        val chosen = destination ?: return@Button
                        val installer = exe ?: return@Button
                        GogInstaller.install(context, installer, chosen, BuildConfig.DEFAULT_GAME_FOLDER, marker)
                    },
                    enabled = exe != null && destination != null,
                    colors = ButtonDefaults.buttonColors(containerColor = WowBronze, contentColor = Color.White),
                    shape = RoundedCornerShape(10.dp),
                ) { Text("INSTALL", fontWeight = FontWeight.Bold) }
            }
        },
        dismissButton = {
            if (!state.running && state.installed == null) {
                TextButton(onClick = {
                    GogInstaller.reset()
                    onDismiss()
                }) { Text("CLOSE", color = WowMuted) }
            }
        },
        containerColor = Color(0xFF1B2838),
        shape = RoundedCornerShape(16.dp),
    )
}
