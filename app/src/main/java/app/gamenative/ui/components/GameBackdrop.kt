package app.gamenative.ui.components

import android.annotation.SuppressLint
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import app.gamenative.ui.theme.WowBackgroundGradient

/**
 * The launcher and loading-screen background: a `game_backdrop` drawable when the build has one, under
 * a dark scrim so text stays readable, otherwise the theme gradient. Builds don't ship one by default;
 * game art can be dropped into `res/drawable` for a personal build.
 */
@SuppressLint("DiscouragedApi")
@Composable
fun GameBackdrop(modifier: Modifier = Modifier, scrim: Float = 0.55f, content: @Composable BoxScope.() -> Unit) {
    val context = LocalContext.current
    val backdrop = remember { context.resources.getIdentifier("game_backdrop", "drawable", context.packageName) }
    Box(modifier = modifier.fillMaxSize().background(Brush.verticalGradient(WowBackgroundGradient))) {
        if (backdrop != 0) {
            Image(
                painter = painterResource(backdrop),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = scrim * 0.6f), Color.Black.copy(alpha = scrim)))),
            )
        }
        content()
    }
}
