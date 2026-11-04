package app.azerothsurvivors

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.webkit.WebViewCompat

private val Gold = Color(0xFFE5C158)
private val Muted = Color(0xFF94A3B8)
private val Bronze = Color(0xFF8C5A2B)

/** WebGPU reached Android's WebView in Chrome 121 (Android 12+, Adreno and Mali GPUs). */
internal const val MIN_WEBVIEW_MAJOR = 121

internal fun webViewMajor(version: String?): Int? = version?.substringBefore('.')?.toIntOrNull()

class LauncherActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val webView = WebViewCompat.getCurrentWebViewPackage(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Launcher(
                    activity = this,
                    webViewVersion = webView?.versionName,
                    onPlay = { GameActivity.open(this) },
                    onUpdateWebView = { openStore(webView?.packageName ?: "com.google.android.webview") },
                    onDiscord = { open("https://discord.gg/azerothsurvivors") },
                )
            }
        }
    }

    private fun open(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private fun openStore(packageName: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))) }
            .onFailure { open("https://play.google.com/store/apps/details?id=$packageName") }
    }
}

@SuppressLint("DiscouragedApi")
@Composable
private fun Launcher(activity: ComponentActivity, webViewVersion: String?, onPlay: () -> Unit, onUpdateWebView: () -> Unit, onDiscord: () -> Unit) {
    val context = LocalContext.current
    val backdrop = remember { context.resources.getIdentifier("game_backdrop", "drawable", context.packageName) }
    val major = webViewMajor(webViewVersion)
    val webViewOk = major != null && major >= MIN_WEBVIEW_MAJOR
    // The version number only says WebGPU exists in this WebView; the probe asks whether it gives this phone's GPU an adapter.
    var gpu by remember { mutableStateOf<WebGpuStatus?>(null) }
    var gpuSaved by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { WebGpuProbe.run(activity) { gpu = it } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0A0E17), Color(0xFF10192A), Color(0xFF080C14)))),
    ) {
        if (backdrop != 0) {
            Image(painterResource(backdrop), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
        }
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "AZEROTH SURVIVORS",
                style = TextStyle(
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 34.sp,
                    letterSpacing = 4.sp,
                    shadow = Shadow(Color.Black.copy(alpha = 0.8f), blurRadius = 12f),
                    brush = Brush.horizontalGradient(listOf(Color(0xFFF5E296), Gold, Color(0xFFC7AF8E))),
                ),
                textAlign = TextAlign.Center,
            )
            Text("FAN-MADE ROGUELIKE · NATIVE WEBGPU", fontSize = 12.sp, letterSpacing = 2.sp, color = Muted)
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = onPlay,
                modifier = Modifier.fillMaxWidth(0.5f).height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Bronze, contentColor = Color.White),
            ) {
                Text("PLAY", fontWeight = FontWeight.Bold, fontSize = 16.sp, letterSpacing = 2.sp)
            }
            Spacer(Modifier.height(14.dp))
            val gpuStatus = gpu
            Text(
                when {
                    webViewVersion == null -> "Android System WebView not found: the game needs it."
                    !webViewOk -> "Android System WebView $webViewVersion is too old for WebGPU; update it to $MIN_WEBVIEW_MAJOR or newer."
                    gpuStatus == null -> "Android System WebView $webViewVersion · checking WebGPU..."
                    else -> "Android System WebView $webViewVersion · ${gpuStatus.summary()}"
                },
                fontSize = 11.sp,
                color = if (webViewOk && (gpuStatus == null || gpuStatus.usable)) Muted else Color(0xFFF87171),
                textAlign = TextAlign.Center,
            )
            gpuStatus?.takeIf { !it.usable }?.let { status ->
                TextButton(onClick = {
                    gpuSaved = runCatching {
                        "Saved Download/${DownloadsFolder.NAME}/" +
                            DownloadsFolder.write(activity, "WebGPU check.txt", status.report().toByteArray(), "text/plain")
                    }.getOrElse { "Couldn't save: ${it.message}" }
                }) { Text("SAVE WEBGPU DETAILS", color = Gold) }
                gpuSaved?.let { Text(it, fontSize = 10.sp, color = Muted, textAlign = TextAlign.Center) }
            }
            Text(
                "Plays from wow.aethus.pl, keeping a copy of what it loads; saves stay in this app (uninstalling deletes them). Controller, touch or keyboard.",
                fontSize = 11.sp,
                color = Muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(12.dp))
            OfflineCard()
            Row(horizontalArrangement = Arrangement.Center) {
                if (!webViewOk) {
                    TextButton(onClick = onUpdateWebView) { Text("UPDATE WEBVIEW", color = Gold) }
                    Spacer(Modifier.width(8.dp))
                }
                TextButton(onClick = onDiscord) { Text("DISCORD", color = Gold) }
            }
            Text("Unofficial fan game; not affiliated with Blizzard Entertainment.", fontSize = 10.sp, color = Muted.copy(alpha = 0.7f))
        }
    }
}


private fun displayName(context: android.content.Context, uri: Uri): String =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: uri.lastPathSegment.orEmpty()

private fun gb(bytes: Long) = "%.1f GB".format(bytes / 1_073_741_824.0)

/**
 * Offline play: the game's data from its Windows download's MPQ archives, so nothing has to come from
 * the site. Imports the .rar itself, or the .MPQ files of one unpacked with a file manager.
 */
@Composable
private fun OfflineCard() {
    val context = LocalContext.current
    val offline = remember { OfflineData.get(context) }
    val state by OfflineImport.state.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    val ready = remember(refresh, state.running) { offline.archiveDir() != null }
    val archiveSize = remember(refresh, state.running) { offline.archiveBytes() }
    val savedSize = remember(refresh, state.running) { offline.savedBytes() }

    val rarPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) OfflineImport.fromRar(context, offline, uri)
    }
    val mpqPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) OfflineImport.fromFiles(context, offline, uris.map { displayName(context, it) to it })
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth(0.8f)
            .background(Color(0xCC0F1722), RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Text("OFFLINE PLAY", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = Gold)
        Text(
            when {
                ready && !offline.matchesSite -> "Game data: ${gb(archiveSize)} from the Windows download (a different version from the site's; used when offline)."
                ready -> "Game data: ${gb(archiveSize)} from the Windows download. Plays offline once it has started online once."
                else -> "Download the game's Windows build (0.75 GB, about 1.6 GB free space while it unpacks) to play without the internet, or import the .rar if you already have it."
            },
            fontSize = 11.sp,
            color = Color(0xFFCBD5E1),
            textAlign = TextAlign.Center,
        )
        if (savedSize > 0) Text("Saved from the site: ${gb(savedSize)}", fontSize = 10.sp, color = Muted)
        if (state.running && state.progress >= 0f) {
            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), color = Gold)
        }
        (state.error ?: state.status.takeIf { it.isNotEmpty() })?.let {
            Text(it, fontSize = 11.sp, color = if (state.error != null) Color(0xFFF87171) else Color.White, textAlign = TextAlign.Center)
        }
        Row(horizontalArrangement = Arrangement.Center) {
            if (state.running) {
                TextButton(onClick = { OfflineImport.cancel() }) { Text("CANCEL", color = Gold) }
            } else {
                TextButton(onClick = { OfflineImport.fromSite(context, offline) }) {
                    Text(if (ready) "UPDATE FROM SITE" else "DOWNLOAD FROM SITE", color = Gold, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = { rarPicker.launch(arrayOf("*/*")) }) { Text("IMPORT .RAR", color = Gold) }
                TextButton(onClick = { mpqPicker.launch(arrayOf("*/*")) }) { Text("IMPORT .MPQ FILES", color = Gold) }
                if (ready) {
                    TextButton(onClick = { OfflineImport.remove(offline); refresh++ }) { Text("REMOVE", color = Muted) }
                }
                if (savedSize > 0) {
                    TextButton(onClick = { offline.clearSaved(); refresh++ }) { Text("CLEAR SAVED", color = Muted) }
                }
            }
        }
    }
}
