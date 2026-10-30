package app.gamenative.ui.screen.wow

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import app.gamenative.ui.theme.WowBronze
import app.gamenative.ui.theme.WowGold
import app.gamenative.ui.theme.WowMuted
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Skyrim's options, set before launching like in the game's own launcher. [documentsDir] is
 * the prefix's Documents folder, or null before the first launch has created the prefix; settings are
 * then kept in [SkyrimGraphics.pendingFile] until it exists.
 */
@Composable
fun SkyrimGraphicsDialog(documentsDir: File?, skseInstalled: Boolean, defaultResolution: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ini by remember { mutableStateOf<String?>(null) }
    var selection by remember { mutableStateOf(emptyMap<String, String>()) }
    var launch by remember { mutableStateOf(SkyrimGraphics.launch(context)) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val text = withContext(Dispatchers.IO) {
            SkyrimGraphics.load(documentsDir, SkyrimGraphics.pendingFile(context), launch.resolution ?: defaultResolution)
        }
        ini = text
        selection = SkyrimGraphics.read(text)
    }

    fun save() {
        val text = ini ?: return
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    SkyrimGraphics.save(documentsDir, SkyrimGraphics.pendingFile(context), SkyrimGraphics.apply(text, selection))
                }.onFailure { Timber.w(it, "Saving Skyrim settings failed") }.isSuccess
            }
            if (saved) {
                SkyrimGraphics.saveLaunch(context, launch)
                onDismiss()
            } else {
                error = "Couldn't save SkyrimPrefs.ini"
            }
        }
    }

    val preset = SkyrimGraphics.presetLabel(selection, launch, defaultResolution)

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.92f),
        title = {
            Column {
                Text(text = "Options", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = WowGold)
                Text(
                    text = error ?: "Used the next time the game starts. Lower settings run faster under emulation.",
                    fontSize = 11.sp,
                    color = if (error != null) Color(0xFFF87171) else WowMuted,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (ini == null) {
                    Text("Reading settings...", color = WowMuted, fontSize = 13.sp)
                    return@Column
                }
                Heading("Game")
                OptionRow("Skip intro video", listOf("Off", "On"), if (launch.skipIntro) "On" else "Off") {
                    launch = launch.copy(skipIntro = it == "On")
                }
                if (skseInstalled) {
                    OptionRow("Start with SKSE", listOf("Off", "On"), if (launch.skse) "On" else "Off") {
                        launch = launch.copy(skse = it == "On")
                    }
                }
                OptionRow("Quicksave when leaving the app", listOf("Off", "On"), if (launch.quicksaveOnLeave) "On" else "Off") {
                    launch = launch.copy(quicksaveOnLeave = it == "On")
                }
                OptionRow("Performance overlay", SkyrimGraphics.OVERLAYS.keys.toList(), launch.overlay) {
                    launch = launch.copy(overlay = it)
                }

                Heading("Preset")
                OptionRow(
                    "Quality",
                    listOf(SkyrimGraphics.BATTERY_SAVER) + SkyrimGraphics.PRESETS.keys + listOfNotNull("Custom".takeIf { preset == "Custom" }),
                    preset,
                ) {
                    if (it == SkyrimGraphics.BATTERY_SAVER) {
                        selection = SkyrimGraphics.PRESETS.getValue(SkyrimGraphics.BATTERY_PRESET)
                        launch = launch.copy(resolution = SkyrimGraphics.BATTERY_RESOLUTION, frameLimit = SkyrimGraphics.BATTERY_FRAME_LIMIT)
                    } else {
                        SkyrimGraphics.PRESETS[it]?.let { choices -> selection = choices }
                    }
                }

                Heading("Display")
                OptionRow(
                    "Resolution",
                    SkyrimGraphics.RESOLUTIONS.map { it.substringAfter('x') + "p" },
                    (launch.resolution ?: defaultResolution).substringAfter('x') + "p",
                ) { label -> launch = launch.copy(resolution = SkyrimGraphics.RESOLUTIONS.first { it.substringAfter('x') + "p" == label }) }
                OptionRow("Frame limit", SkyrimGraphics.FRAME_LIMITS.map { "$it FPS" }, "${launch.frameLimit} FPS") {
                    launch = launch.copy(frameLimit = it.substringBefore(' ').toInt())
                }

                for (section in listOf(SkyrimGraphics.QUALITY, SkyrimGraphics.EFFECTS, SkyrimGraphics.DISTANCE)) {
                    Heading(section)
                    for (option in SkyrimGraphics.OPTIONS.filter { it.section == section }) {
                        OptionRow(option.label, option.choices.map { it.label }, selection[option.id]) {
                            selection = selection + (option.id to it)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = ::save,
                enabled = ini != null,
                colors = ButtonDefaults.buttonColors(containerColor = WowBronze, contentColor = Color.White),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("SAVE", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    selection = SkyrimGraphics.PRESETS.getValue(SkyrimGraphics.DEFAULT_PRESET)
                    launch = launch.copy(resolution = null, frameLimit = SkyrimGraphics.DEFAULT_FRAME_LIMIT)
                },
                enabled = ini != null,
            ) {
                Text("RECOMMENDED", color = WowMuted)
            }
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = WowMuted)
            }
        },
        containerColor = Color(0xFF1B2838),
        shape = RoundedCornerShape(16.dp),
    )
}

@Composable
private fun Heading(text: String) {
    Text(
        text = text.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        color = WowGold,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun OptionRow(label: String, choices: List<String>, selected: String?, onSelect: (String) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Text(text = label, fontSize = 13.sp, color = Color(0xFFE2E8F0), modifier = Modifier.weight(0.4f))
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .weight(0.6f)
                .horizontalScroll(rememberScrollState()),
        ) {
            for (choice in choices) {
                val isSelected = choice == selected
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) WowBronze else Color(0xFF0F1722))
                        .border(1.dp, if (isSelected) WowGold else Color(0xFF2A3A50), RoundedCornerShape(8.dp))
                        .clickable { onSelect(choice) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(text = choice, fontSize = 12.sp, color = if (isSelected) Color.White else Color(0xFFCBD5E1))
                }
            }
        }
    }
}
