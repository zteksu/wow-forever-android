package app.gamenative.ui.screen.wow

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
 * No Man's Sky's options. Quality comes from the game's own TKGRAPHICSSETTINGS.MXML, which it writes on
 * its first run, so those options appear once the game has been started.
 */
@Composable
fun NmsOptionsDialog(gameRoot: File, recommendedDriver: String, defaultResolution: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var xml by remember { mutableStateOf<String?>(null) }
    var launch by remember { mutableStateOf(SkyrimGraphics.launch(context)) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        xml = withContext(Dispatchers.IO) { NmsConfig.settingsFile(gameRoot).takeIf { it.isFile }?.readText() }
        loaded = true
    }

    fun save() {
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    xml?.let {
                        NmsConfig.settingsFile(gameRoot).writeText(it)
                        NmsConfig.markTuned(gameRoot)
                    }
                }.onFailure { Timber.w(it, "Saving No Man's Sky settings failed") }.isSuccess
            }
            if (saved) {
                SkyrimGraphics.saveLaunch(context, launch)
                onDismiss()
            } else {
                error = "Couldn't save TKGRAPHICSSETTINGS.MXML"
            }
        }
    }

    val quality = xml?.let { NmsConfig.quality(it) }.orEmpty()
    val level = xml?.let { NmsConfig.preset(it) }
    val resolution = launch.resolution ?: defaultResolution
    val preset = when {
        quality.isEmpty() -> null
        level == SkyrimGraphics.BATTERY_PRESET && resolution == SkyrimGraphics.BATTERY_RESOLUTION &&
            launch.frameLimit == SkyrimGraphics.BATTERY_FRAME_LIMIT -> SkyrimGraphics.BATTERY_SAVER
        else -> level ?: "Custom"
    }

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
                if (!loaded) {
                    Text("Reading settings...", color = WowMuted, fontSize = 13.sp)
                    return@Column
                }
                Heading("Game")
                OptionRow("Performance overlay", listOf("Off", "FPS"), if (launch.overlay == "FPS") "FPS" else "Off") {
                    launch = launch.copy(overlay = it)
                }

                if (preset != null) {
                    Heading("Preset")
                    OptionRow(
                        "Quality",
                        listOf(SkyrimGraphics.BATTERY_SAVER) + NmsConfig.LEVELS + listOfNotNull("Custom".takeIf { preset == "Custom" }),
                        preset,
                    ) {
                        val text = xml ?: return@OptionRow
                        if (it == SkyrimGraphics.BATTERY_SAVER) {
                            xml = NmsConfig.setAllQuality(text, SkyrimGraphics.BATTERY_PRESET)
                            launch = launch.copy(resolution = SkyrimGraphics.BATTERY_RESOLUTION, frameLimit = SkyrimGraphics.BATTERY_FRAME_LIMIT)
                        } else if (it in NmsConfig.LEVELS) {
                            xml = NmsConfig.setAllQuality(text, it)
                        }
                    }
                }

                Heading("Display")
                OptionRow(
                    "Resolution",
                    SkyrimGraphics.RESOLUTIONS.map { it.substringAfter('x') + "p" },
                    resolution.substringAfter('x') + "p",
                ) { label -> launch = launch.copy(resolution = SkyrimGraphics.RESOLUTIONS.first { it.substringAfter('x') + "p" == label }) }
                OptionRow("Frame limit", SkyrimGraphics.FRAME_LIMITS.map { "$it FPS" }, "${launch.frameLimit} FPS") {
                    launch = launch.copy(frameLimit = it.substringBefore(' ').toInt())
                }

                Heading("Graphics driver")
                DriverSection(launch.driver, recommendedDriver) { launch = launch.copy(driver = it) }

                Heading("Quality")
                if (quality.isEmpty()) {
                    Text(
                        "Start the game once: it writes its settings file on the first run, and its quality options appear here after that.",
                        fontSize = 12.sp,
                        color = WowMuted,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                for ((category, current) in quality) {
                    OptionRow(NmsConfig.label(category), NmsConfig.LEVELS, current) {
                        xml = xml?.let { text -> NmsConfig.setQuality(text, category, it) }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = ::save,
                enabled = loaded,
                colors = ButtonDefaults.buttonColors(containerColor = WowBronze, contentColor = Color.White),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("SAVE", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    xml = xml?.let { NmsConfig.setAllQuality(it, NmsConfig.HANDHELD_LEVEL) }
                    launch = launch.copy(resolution = null, frameLimit = SkyrimGraphics.DEFAULT_FRAME_LIMIT)
                },
                enabled = loaded,
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
