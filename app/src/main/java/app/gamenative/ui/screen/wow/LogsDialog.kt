package app.gamenative.ui.screen.wow

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import app.gamenative.ui.theme.WowBronze
import app.gamenative.ui.theme.WowGold
import app.gamenative.ui.theme.WowMuted
import app.gamenative.utils.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Rendering the whole report in a Text is slow. The report puts exit reasons and the last crash
// first, so the head is the useful part to preview.
private const val PREVIEW_CHARS = 40_000

@Composable
fun LogsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        report = withContext(Dispatchers.IO) {
            runCatching { DiagnosticLog.buildReport(context) }.getOrElse { "Failed to build log: $it" }
        }
    }

    val preview = report?.let {
        if (it.length > PREVIEW_CHARS) it.take(PREVIEW_CHARS) + "\n\n(preview truncated; Save and Share include everything)" else it
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.92f),
        title = {
            Column {
                Text(text = "Logs", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = WowGold)
                saved?.let { Text(text = it, fontSize = 11.sp, color = WowMuted) }
            }
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .background(Color(0xFF0F1722), RoundedCornerShape(8.dp))
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(10.dp),
            ) {
                Text(
                    text = preview ?: "Collecting logs...",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    color = Color(0xFFCBD5E1),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    report?.let {
                        scope.launch {
                            val file = withContext(Dispatchers.IO) { DiagnosticLog.saveToDownloads(it) }
                            saved = file?.let { f -> "Saved to Download/${f.name}" } ?: "Couldn't save to Download/"
                        }
                    }
                },
                enabled = report != null,
                colors = ButtonDefaults.buttonColors(containerColor = WowBronze, contentColor = Color.White),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("SAVE .TXT", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = { report?.let { DiagnosticLog.share(context, it) } },
                enabled = report != null,
            ) {
                Text("SHARE", color = WowMuted)
            }
            TextButton(
                onClick = {
                    report?.let {
                        DiagnosticLog.copyToClipboard(context, it)
                        copied = true
                    }
                },
                enabled = report != null,
            ) {
                Text(if (copied) "COPIED" else "COPY", color = WowMuted)
            }
            TextButton(onClick = onDismiss) {
                Text("CLOSE", color = WowMuted)
            }
        },
        containerColor = Color(0xFF1B2838),
        shape = RoundedCornerShape(16.dp),
    )
}
