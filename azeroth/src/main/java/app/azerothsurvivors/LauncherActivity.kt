package app.azerothsurvivors

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
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
private fun Launcher(webViewVersion: String?, onPlay: () -> Unit, onUpdateWebView: () -> Unit, onDiscord: () -> Unit) {
    val context = LocalContext.current
    val backdrop = remember { context.resources.getIdentifier("game_backdrop", "drawable", context.packageName) }
    val major = webViewMajor(webViewVersion)
    val webViewOk = major != null && major >= MIN_WEBVIEW_MAJOR

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
            modifier = Modifier.fillMaxSize().padding(24.dp),
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
            Text(
                when {
                    webViewOk -> "Android System WebView $webViewVersion · WebGPU supported"
                    webViewVersion == null -> "Android System WebView not found: the game needs it."
                    else -> "Android System WebView $webViewVersion is too old for WebGPU; update it to $MIN_WEBVIEW_MAJOR or newer."
                },
                fontSize = 11.sp,
                color = if (webViewOk) Muted else Color(0xFFF87171),
                textAlign = TextAlign.Center,
            )
            Text(
                "Plays online from wow.aethus.pl; saves stay in this app (uninstalling deletes them). Controller, touch or keyboard.",
                fontSize = 11.sp,
                color = Muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
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
