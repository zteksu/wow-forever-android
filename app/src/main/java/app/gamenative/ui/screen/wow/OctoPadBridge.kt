package app.gamenative.ui.screen.wow

import android.os.Handler
import android.os.Looper
import app.gamenative.ui.screen.xserver.GamepadCamera
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import timber.log.Timber

/**
 * Follows the state the OctoPad addon writes with VanillaHelpers' WriteFile, the only way a 1.12 addon can
 * reach outside the game: whether a menu wants the pointer, keyboard requests and the camera speed.
 *
 * Until the addon reports in (the login and character screens, or a client without VanillaHelpers) the
 * camera stays in hand-picked pointer mode, so A and B click.
 */
class OctoPadBridge(
    private val file: File,
    private val camera: GamepadCamera,
    private val onShowKeyboard: () -> Unit,
) {
    /** pointer: 0 the camera, 1 a menu wants the pointer, 2 the glue screens (pointer with A/B clicks). */
    data class State(val pointer: Int, val capture: Boolean, val keyboard: Int, val speed: Float)

    companion object {
        const val FILE_NAME = "OctoPad.txt"
        private const val POLL_MS = 50L

        fun stateFile(gameRoot: File) = File(gameRoot, "VanillaHelpersData/$FILE_NAME")

        internal fun parse(text: String): State? {
            val values = text.lineSequence()
                .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 } }
                .associate { (key, value) -> key.trim() to value.trim() }
            val pointer = values["pointer"]?.toIntOrNull() ?: return null
            return State(
                pointer = pointer,
                capture = values["capture"] == "1",
                keyboard = values["keyboard"]?.toIntOrNull() ?: 0,
                speed = values["speed"]?.toFloatOrNull()?.coerceIn(0.2f, 4f) ?: 1f,
            )
        }
    }

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var executor: ScheduledExecutorService? = null
    private var lastText: String? = null
    private var applied: State? = null

    fun start() {
        // A file left from the last session would put the camera in the wrong mode before the addon loads.
        runCatching { file.delete() }
        camera.reset(pointer = true)
        executor = Executors.newSingleThreadScheduledExecutor().apply {
            scheduleWithFixedDelay(::poll, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS)
        }
    }

    fun stop() {
        executor?.shutdownNow()
        executor = null
    }

    private fun poll() {
        val text = runCatching { if (file.isFile) file.readText() else null }.getOrNull() ?: return
        if (text == lastText) return
        lastText = text
        val state = parse(text) ?: return
        main.post { apply(state) }
    }

    internal fun apply(state: State) {
        val previous = applied
        applied = state
        camera.addonCapture = state.capture || state.pointer != 0
        camera.lookSpeed = state.speed
        if (previous?.pointer != state.pointer) {
            when (state.pointer) {
                0 -> { camera.manualPointer = false; camera.addonPointer = false }
                1 -> { camera.manualPointer = false; camera.addonPointer = true }
                else -> { camera.addonPointer = false; camera.manualPointer = true }
            }
        }
        if (previous != null && state.keyboard != previous.keyboard) {
            Timber.d("OctoPad asked for the keyboard")
            onShowKeyboard()
        }
    }
}
