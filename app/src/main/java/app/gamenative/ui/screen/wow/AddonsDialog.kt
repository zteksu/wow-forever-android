package app.gamenative.ui.screen.wow

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.ui.theme.WowBronze
import app.gamenative.ui.theme.WowError
import app.gamenative.ui.theme.WowGold
import app.gamenative.ui.theme.WowMuted
import app.gamenative.ui.theme.WowSubtle
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Browse, install, update and remove addons from OctoWoW's catalog, and pick the controller addon. */
@Composable
fun AddonsDialog(gameRoot: File, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var catalog by remember { mutableStateOf<List<AddonManager.Addon>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var installedOnly by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var layout by remember { mutableStateOf(MpqClient.controllerLayout(context)) }
    // Bumped after every change so item states are read again from disk.
    var revision by remember { mutableIntStateOf(0) }

    fun load() {
        loadError = null
        catalog = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { AddonManager.fetchCatalog() } }
                .onSuccess { catalog = it }
                .onFailure {
                    Timber.w(it, "Addon catalog failed to load")
                    loadError = "Couldn't load the OctoWoW addon list: ${it.message}"
                }
        }
    }
    LaunchedEffect(Unit) { load() }

    fun run(label: String, action: suspend ((String) -> Unit) -> Unit) {
        if (busy != null) return
        busy = label
        status = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { action { msg -> scope.launch(Dispatchers.Main) { status = msg } } }
                status = null
            } catch (e: Exception) {
                Timber.e(e, "Addon action failed: $label")
                status = "$label failed: ${e.message}"
            } finally {
                busy = null
                revision++
            }
        }
    }

    Dialog(onDismissRequest = { if (busy == null) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0D1420))
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ADD-ONS", color = WowGold, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss, enabled = busy == null) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = WowMuted)
                }
            }

            Text("Controller layout", color = WowMuted, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                MpqClient.ControllerLayout.entries.forEach { option ->
                    FilterChip(
                        selected = layout == option,
                        enabled = busy == null,
                        onClick = {
                            if (layout != option) {
                                run("Switching to ${option.title}") { onStatus ->
                                    AddonManager.applyControllerLayout(context, gameRoot, option, onStatus)
                                    layout = option
                                }
                            }
                        },
                        label = { Text(option.title) },
                    )
                }
            }
            Text(
                text = when (layout) {
                    MpqClient.ControllerLayout.SHAGU -> "Steam Deck-style bars. Sticks move and aim, triggers click, face buttons and D-pad are actions."
                    MpqClient.ControllerLayout.CONSOLE_EXPERIENCE ->
                        "ConsolePort-style UI (alpha): hold LT/RT for more bars, Start opens the radial menu, D-pad navigates menus. Type /ce in game to configure."
                    MpqClient.ControllerLayout.OCTOPAD ->
                        "WoW Forever's gamepad mode: LT/RT layers, bumpers target, LB+RB shortcuts, class flyouts, Start radial menu, " +
                            "D-pad menus and a free camera. Select toggles the pointer. Type /octopad in game for options."
                },
                color = WowSubtle,
                fontSize = 11.sp,
            )

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Search add-ons") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(selected = installedOnly, onClick = { installedOnly = !installedOnly }, label = { Text("Installed") })
            }

            (busy ?: status)?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = if (busy == null) WowError else WowGold, fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))

            val list = catalog
            when {
                loadError != null -> Column {
                    Text(loadError!!, color = WowError, fontSize = 13.sp)
                    TextButton(onClick = ::load) { Text("Retry") }
                }
                list == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = WowGold)
                }
                else -> {
                    revision // read so the list recomposes after installs
                    val shown = list
                        .filter { query.isBlank() || it.title.contains(query, true) || it.name.contains(query, true) || it.notes.contains(query, true) }
                        .map { it to AddonManager.state(context, gameRoot, it) }
                        .filter { !installedOnly || it.second != AddonManager.State.NOT_INSTALLED }
                        .sortedWith(compareByDescending<Pair<AddonManager.Addon, AddonManager.State>> { it.first.stars }.thenBy { it.first.title.lowercase() })
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(shown, key = { it.first.name }) { (addon, state) ->
                            AddonRow(
                                addon = addon,
                                state = state,
                                enabled = busy == null,
                                onInstall = { run("Installing ${addon.title}") { AddonManager.install(context, gameRoot, addon, list, it) } },
                                onRemove = { run("Removing ${addon.title}") { AddonManager.remove(context, gameRoot, addon) } },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddonRow(
    addon: AddonManager.Addon,
    state: AddonManager.State,
    enabled: Boolean,
    onInstall: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF131D2E)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(addon.title, color = Color(0xFFF0E6D2), fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (addon.stars > 0) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Default.Star, contentDescription = null, tint = WowGold, modifier = Modifier.size(14.dp))
                        Text("${addon.stars}", color = WowGold, fontSize = 11.sp)
                    }
                }
                if (addon.notes.isNotBlank()) {
                    Text(addon.notes, color = WowMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                val meta = listOfNotNull(
                    addon.author.takeIf { it.isNotBlank() }?.let { "by $it" },
                    addon.version.takeIf { it.isNotBlank() }?.let { "v$it" },
                    addon.dependencies.takeIf { it.isNotEmpty() }?.let { "needs ${it.joinToString()}" },
                ).joinToString(" · ")
                if (meta.isNotBlank()) Text(meta, color = WowSubtle, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (state) {
                    AddonManager.State.NOT_INSTALLED -> ActionButton("Install", enabled, onInstall)
                    AddonManager.State.UPDATE_AVAILABLE -> ActionButton("Update", enabled, onInstall)
                    AddonManager.State.INSTALLED_BY_PLAYER -> ActionButton("Reinstall", enabled, onInstall)
                    AddonManager.State.INSTALLED -> Text("Installed", color = Color(0xFF48BB78), fontSize = 12.sp)
                }
                if (state != AddonManager.State.NOT_INSTALLED) {
                    OutlinedButton(onClick = onRemove, enabled = enabled) { Text("Remove", fontSize = 12.sp) }
                }
            }
        }
    }
}

@Composable
private fun ActionButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = WowBronze, contentColor = Color.White),
    ) { Text(text, fontSize = 12.sp) }
}
