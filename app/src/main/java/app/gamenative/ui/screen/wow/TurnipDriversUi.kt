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
import com.winlator.core.GPUInformation
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
fun DriverSection(
    selected: String?,
    recommended: String,
    downloadSource: TurnipDrivers.Source = TurnipDrivers.SOURCES.first(),
    refreshKey: Int = 0,
    onSelect: (String?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dir = remember { TurnipDrivers.driversDir(context) }
    var refresh by remember { mutableIntStateOf(0) }
    var drivers by remember { mutableStateOf<List<TurnipDrivers.Installed>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var showDownloads by remember { mutableStateOf(false) }

    LaunchedEffect(refresh, refreshKey) {
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
            initialSource = downloadSource,
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
internal fun DriverDownloadDialog(
    onDismiss: () -> Unit,
    onInstalled: (String) -> Unit,
    initialSource: TurnipDrivers.Source = TurnipDrivers.SOURCES.first(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf(initialSource) }
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
                                                        TurnipDrivers.download(release, asset, TurnipDrivers.driversDir(context), BUNDLED_DRIVERS)
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

/**
 * The WoW apps' Drivers screen: which Turnip driver the game runs with, newer builds of downloaded drivers, and the
 * same picker, downloader and install-from-file as Skyrim's Options. The choice applies from the next launch.
 */
@Composable
fun DriversDialog(recommended: String, gpuLabel: String, adreno8xx: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dir = remember { TurnipDrivers.driversDir(context) }
    val renderer = remember { runCatching { GPUInformation.getRenderer(context) }.getOrNull().orEmpty() }
    var selected by remember { mutableStateOf(DriverChoice.get(context)) }
    var drivers by remember { mutableStateOf<List<TurnipDrivers.Installed>>(emptyList()) }
    var updates by remember { mutableStateOf<List<TurnipDrivers.Update>>(emptyList()) }
    var newest by remember { mutableStateOf<TurnipDrivers.Release?>(null) }
    var checking by remember { mutableStateOf(false) }
    var checkError by remember { mutableStateOf<String?>(null) }
    var updating by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var showDownloads by remember { mutableStateOf(false) }
    val source = remember(adreno8xx) { TurnipDrivers.recommendedSource(adreno8xx) }

    fun select(name: String?) {
        selected = name
        DriverChoice.set(context, name)
    }

    LaunchedEffect(refresh) {
        drivers = withContext(Dispatchers.IO) { TurnipDrivers.installed(dir, BUNDLED_DRIVERS) }
    }

    // Looks for newer builds of downloaded drivers, and the newest build from the project suited to this GPU.
    fun checkForUpdates() {
        checking = true
        checkError = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val installed = TurnipDrivers.installed(dir, BUNDLED_DRIVERS)
                    val repos = (installed.mapNotNull { it.origin?.repo } + source.repo).toSet()
                    val releases = repos.flatMap { repo ->
                        TurnipDrivers.fetchReleases(TurnipDrivers.SOURCES.firstOrNull { it.repo == repo } ?: TurnipDrivers.Source(repo, repo))
                    }
                    TurnipDrivers.updates(installed, releases) to
                        releases.filter { it.repo == source.repo && !it.prerelease }.maxByOrNull { it.publishedAt }
                }.onFailure { Timber.w(it, "Driver update check failed") }
            }
            checking = false
            result.onSuccess { (found, latest) ->
                updates = found
                newest = latest
            }.onFailure { checkError = "Couldn't check for updates: ${it.message}" }
        }
    }
    LaunchedEffect(Unit) { checkForUpdates() }

    val inUse = drivers.firstOrNull { it.name == selected } ?: drivers.firstOrNull { it.name == recommended }
    val usingRecommended = selected == null || drivers.none { it.name == selected }

    AlertDialog(
        onDismissRequest = { if (updating == null) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.92f),
        title = {
            Column {
                Text("Graphics drivers", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = WowGold)
                Text("Changes apply the next time you press Play.", fontSize = 11.sp, color = WowMuted)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                SectionLabel("IN USE")
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF0F1722))
                        .border(1.dp, WowGold, RoundedCornerShape(10.dp))
                        .padding(10.dp),
                ) {
                    Text(inUse?.name ?: recommended, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    val details = listOfNotNull(
                        inUse?.driverVersion?.takeIf { it.isNotBlank() },
                        inUse?.origin?.let { "${it.tag} from ${it.repo.substringBefore('/')}" },
                        if (usingRecommended) "recommended for $gpuLabel" else "your pick",
                    )
                    Text(details.joinToString(" · "), fontSize = 12.sp, color = Color(0xFFCBD5E1))
                    inUse?.description?.takeIf { it.isNotBlank() }?.let {
                        Text(it, fontSize = 11.sp, color = WowMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (renderer.isNotBlank()) Text("GPU: $renderer", fontSize = 11.sp, color = WowMuted)
                }

                SectionLabel("UPDATES")
                when {
                    checking -> Text("Checking GitHub for newer builds...", fontSize = 12.sp, color = WowMuted)
                    checkError != null -> Text(checkError!!, fontSize = 12.sp, color = Color(0xFFF87171))
                    else -> {
                        for (update in updates) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "${update.driver.name}: ${update.release.tag} available",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                    )
                                    Text(
                                        "You have ${update.driver.origin?.tag} · new build from ${update.release.publishedAt}",
                                        fontSize = 11.sp,
                                        color = WowMuted,
                                    )
                                }
                                TextButton(
                                    enabled = updating == null,
                                    onClick = {
                                        updating = update.driver.name
                                        scope.launch {
                                            val result = withContext(Dispatchers.IO) {
                                                runCatching { TurnipDrivers.download(update.release, update.asset, dir, BUNDLED_DRIVERS) }
                                                    .onFailure { Timber.w(it, "Driver update failed") }
                                            }
                                            updating = null
                                            result.onSuccess { name ->
                                                // Keeps using the driver the player picked, now in its newer build.
                                                if (selected == update.driver.name) select(name)
                                                message = "Updated to $name (${update.release.tag})"
                                                updates = updates - update
                                                refresh++
                                            }.onFailure { message = "Update failed: ${it.message}" }
                                        }
                                    },
                                ) { Text(if (updating == update.driver.name) "UPDATING..." else "UPDATE", color = WowGold) }
                            }
                        }
                        if (updates.isEmpty()) {
                            Text("Your downloaded drivers are up to date.", fontSize = 12.sp, color = WowMuted)
                        }
                        newest?.let { latest ->
                            val have = drivers.any { it.origin?.repo == latest.repo && it.origin.tag == latest.tag }
                            if (!have) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        "Newest from ${source.label}: ${latest.title} (${latest.publishedAt})",
                                        fontSize = 12.sp,
                                        color = Color(0xFFCBD5E1),
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = { showDownloads = true }, enabled = updating == null) {
                                        Text("GET", color = WowGold)
                                    }
                                }
                            }
                        }
                    }
                }
                message?.let { Text(it, fontSize = 11.sp, color = WowGold, modifier = Modifier.padding(top = 4.dp)) }
                TextButton(onClick = { checkForUpdates() }, enabled = !checking && updating == null) {
                    Text("CHECK AGAIN", color = WowGold, fontSize = 12.sp)
                }

                SectionLabel("ALL DRIVERS")
                DriverSection(
                    selected = selected,
                    recommended = recommended,
                    onSelect = {
                        select(it)
                        refresh++
                    },
                    downloadSource = source,
                    refreshKey = refresh,
                )
                Text(
                    "If the game crashes, flickers or runs worse after switching, pick Recommended to go back.",
                    fontSize = 11.sp,
                    color = WowMuted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = updating == null) { Text("CLOSE", color = WowMuted) }
        },
        containerColor = Color(0xFF1B2838),
        shape = RoundedCornerShape(16.dp),
    )

    if (showDownloads) {
        DriverDownloadDialog(
            initialSource = source,
            onDismiss = { showDownloads = false },
            onInstalled = { name ->
                select(name)
                message = "Installed and selected $name"
                refresh++
            },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        color = WowGold,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}
