package app.gamenative.ui.screen.wow

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import app.gamenative.ui.theme.WowBronze
import app.gamenative.ui.theme.WowGold
import app.gamenative.ui.theme.WowMuted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * The Graphics driver part of Options: the recommended (bundled) Turnip and any installed ones, with
 * a downloader and install-from-file. [selected] is null for the recommended driver.
 */
@Composable
fun DriverSection(selected: String?, recommended: String, onSelect: (String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dir = remember { TurnipDrivers.driversDir(context) }
    var refresh by remember { mutableIntStateOf(0) }
    var drivers by remember { mutableStateOf<List<TurnipDrivers.Installed>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var showDownloads by remember { mutableStateOf(false) }

    LaunchedEffect(refresh) {
        drivers = withContext(Dispatchers.IO) { TurnipDrivers.installed(dir, BUNDLED_DRIVERS) }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        message = "Installing..."
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { TurnipDrivers.install(it, dir, BUNDLED_DRIVERS) }
                        ?: error("Can't open the file")
                }.onFailure { Timber.w(it, "Installing driver from file failed") }
            }
            message = result.fold({ "Installed $it" }, { it.message ?: "Install failed" })
            result.getOrNull()?.let(onSelect)
            refresh++
        }
    }

    Text(
        "Turnip is the open-source Vulkan driver for Adreno. Newer builds can be faster or fix glitches, " +
            "but can also break things; the recommended one is what this app is tested with.",
        fontSize = 11.sp,
        color = WowMuted,
        modifier = Modifier.padding(bottom = 4.dp),
    )
    DriverRow(
        title = "Recommended",
        subtitle = recommended,
        isSelected = selected == null || drivers.none { it.name == selected },
        onClick = { onSelect(null) },
    )
    for (driver in drivers.filter { it.name != recommended }) {
        DriverRow(
            title = driver.name,
            subtitle = listOf(driver.driverVersion, if (driver.bundled) "bundled" else "").filter { it.isNotBlank() }.joinToString(" · "),
            isSelected = selected == driver.name,
            onClick = { onSelect(driver.name) },
            onRemove = if (driver.bundled) {
                null
            } else {
                {
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { TurnipDrivers.remove(dir, driver.name, BUNDLED_DRIVERS) } }
                        if (selected == driver.name) onSelect(null)
                        message = "Removed ${driver.name}"
                        refresh++
                    }
                }
            },
        )
    }
    message?.let { Text(it, fontSize = 11.sp, color = WowGold, modifier = Modifier.padding(top = 4.dp)) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { showDownloads = true }) { Text("DOWNLOAD DRIVERS", color = WowGold) }
        TextButton(onClick = { picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }) {
            Text("INSTALL FROM FILE", color = WowGold)
        }
    }

    if (showDownloads) {
        DriverDownloadDialog(
            onDismiss = { showDownloads = false },
            onInstalled = { name ->
                message = "Installed $name"
                onSelect(name)
                refresh++
            },
        )
    }
}

@Composable
private fun DriverRow(title: String, subtitle: String, isSelected: Boolean, onClick: () -> Unit, onRemove: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) WowBronze.copy(alpha = 0.35f) else Color(0xFF0F1722))
            .border(1.dp, if (isSelected) WowGold else Color(0xFF2A3A50), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(subtitle, fontSize = 11.sp, color = WowMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (onRemove != null) {
            TextButton(onClick = onRemove) { Text("REMOVE", fontSize = 11.sp, color = WowMuted) }
        }
    }
}

/** Driver releases from [TurnipDrivers.SOURCES], one source at a time. */
@Composable
private fun DriverDownloadDialog(onDismiss: () -> Unit, onInstalled: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf(TurnipDrivers.SOURCES.first()) }
    var releases by remember { mutableStateOf<List<TurnipDrivers.Release>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var installing by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(source) {
        releases = null
        error = null
        withContext(Dispatchers.IO) {
            runCatching { TurnipDrivers.fetchReleases(source) }
                .onSuccess { releases = it }
                .onFailure { error = it.message ?: "Couldn't reach GitHub" }
        }
    }

    AlertDialog(
        onDismissRequest = { if (installing == null) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.92f),
        title = {
            Column {
                Text("Download drivers", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = WowGold)
                Text(
                    installing?.let { "Downloading $it..." } ?: "From the projects' GitHub releases. Adreno 830 needs a gen8 / 8xx build.",
                    fontSize = 11.sp,
                    color = WowMuted,
                )
            }
        },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    for (s in TurnipDrivers.SOURCES) {
                        val isSelected = s == source
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) WowBronze else Color(0xFF0F1722))
                                .border(1.dp, if (isSelected) WowGold else Color(0xFF2A3A50), RoundedCornerShape(8.dp))
                                .clickable(enabled = installing == null) { source = s }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text(s.label, fontSize = 12.sp, color = if (isSelected) Color.White else Color(0xFFCBD5E1))
                        }
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .padding(top = 8.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    val list = releases
                    when {
                        error != null -> Text(error!!, fontSize = 13.sp, color = Color(0xFFF87171))
                        list == null -> Text("Loading releases...", fontSize = 13.sp, color = WowMuted)
                        list.isEmpty() -> Text("No driver zips in this project's recent releases.", fontSize = 13.sp, color = WowMuted)
                        else -> for (release in list) {
                            Text(
                                release.title + if (release.prerelease) "  (pre-release)" else "",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            Text("${release.tag} · ${release.publishedAt}", fontSize = 11.sp, color = WowMuted)
                            val notes = release.notes.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(3).joinToString(" ")
                            if (notes.isNotEmpty()) {
                                Text(notes, fontSize = 11.sp, color = Color(0xFFCBD5E1), maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                            for (asset in release.assets) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        "${asset.name} · ${asset.size / (1024 * 1024)} MB",
                                        fontSize = 12.sp,
                                        color = Color(0xFFE2E8F0),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(
                                        enabled = installing == null,
                                        onClick = {
                                            installing = asset.name
                                            scope.launch {
                                                val result = withContext(Dispatchers.IO) {
                                                    runCatching {
                                                        TurnipDrivers.download(asset.url, TurnipDrivers.driversDir(context), BUNDLED_DRIVERS)
                                                    }.onFailure { Timber.w(it, "Driver download failed") }
                                                }
                                                installing = null
                                                result.onSuccess {
                                                    onInstalled(it)
                                                    onDismiss()
                                                }.onFailure { error = it.message ?: "Download failed" }
                                            }
                                        },
                                    ) { Text("INSTALL", color = WowGold) }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = installing == null) { Text("CLOSE", color = WowMuted) }
        },
        containerColor = Color(0xFF1B2838),
        shape = RoundedCornerShape(16.dp),
    )
}
