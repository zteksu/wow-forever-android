package app.gamenative.ui.screen.xserver

import com.winlator.xserver.Pointer
import java.util.Timer
import java.util.TimerTask
import kotlin.math.abs

/**
 * WoW Forever's gamepad camera for a client that only understands a mouse (the 1.12 OctoWoW client).
 *
 * - The right stick turns the camera and the character with it (a right-button drag), so the left stick,
 *   which only presses movement keys, always moves the way the camera faces. A CAMERA_STEER binding holds
 *   the same drag without turning.
 * - LB+RB with the right stick zooms (mouse wheel).
 * - Pointer mode turns the right stick into a plain pointer: on when the OctoPad addon reports an open menu,
 *   or toggled by hand, when A and B also become left and right click.
 * - Interact right-clicks the reticle, the point the pointer rests on, which is what X does in Forever:
 *   talk to the NPC, loot the corpse or attack the enemy in front of you.
 *
 * Every drag starts from the reticle with a tiny jiggle, so WoW never reads a button press as a click.
 */
class GamepadCamera(
    private val sink: Sink,
    private val speed: () -> Float = { 1f },
    private val autoTick: Boolean = true,
) {
    interface Sink {
        val width: Int
        val height: Int
        fun press(button: Pointer.Button)
        fun release(button: Pointer.Button)
        fun moveBy(dx: Int, dy: Int)
        fun moveTo(x: Int, y: Int)
    }

    enum class Look { UP, RIGHT, DOWN, LEFT }

    companion object {
        const val RETICLE_X = 0.5f
        const val RETICLE_Y = 0.45f
        private const val JIGGLE = 2
        private const val PIXELS_PER_TICK = 10f
        private const val ZOOM_STEPS_PER_TICK = 0.25f
        private const val TICK_MS = 1000L / 60
    }

    private val look = FloatArray(4)
    private val steerSources = mutableSetOf<Any>()
    private var held: Pointer.Button? = null
    private var timer: Timer? = null
    private var zoomAccumulator = 0f

    /** Set when the addon has a menu open: the right stick drives the pointer, A and B stay with the addon. */
    var addonPointer = false
        set(value) { field = value; update() }

    /** Pointer mode chosen by hand (and the glue screens): A and B click. */
    var manualPointer = false
        set(value) { field = value; update() }

    /** The addon has captured the face buttons (a flyout, say), so interact must not click the world. */
    var addonCapture = false

    /** LB+RB held: the right stick zooms instead of turning. */
    @Volatile var zoomChord = false
        set(value) { field = value; update() }

    @Volatile var lookSpeed = 1f

    val pointerMode get() = manualPointer || addonPointer

    private val lookX get() = look[Look.RIGHT.ordinal] - look[Look.LEFT.ordinal]
    private val lookY get() = look[Look.DOWN.ordinal] - look[Look.UP.ordinal]
    private val looking get() = look.any { it != 0f }

    /** [value] is the stick deflection (0..1, any sign); zero releases that direction. */
    fun setLook(direction: Look, value: Float) {
        look[direction.ordinal] = abs(value)
        update()
    }

    fun setSteer(source: Any, active: Boolean) {
        val changed = if (active) steerSources.add(source) else steerSources.remove(source)
        if (changed) update()
    }

    /** The pointer-mode button: leaves pointer mode however it was entered, or enters it by hand. */
    fun togglePointer() {
        if (pointerMode) {
            manualPointer = false
            addonPointer = false
        } else {
            manualPointer = true
        }
    }

    fun interactClick() {
        if (pointerMode || addonCapture) return
        val resume = held
        resume?.let { sink.release(it) }
        held = null
        moveToReticle()
        sink.press(Pointer.Button.BUTTON_RIGHT)
        sink.release(Pointer.Button.BUTTON_RIGHT)
        resume?.let { startDrag(it) }
    }

    fun reset(pointer: Boolean = false) {
        look.fill(0f)
        steerSources.clear()
        zoomChord = false
        addonCapture = false
        addonPointer = false
        manualPointer = pointer
        update()
    }

    private fun desiredButton(): Pointer.Button? = when {
        pointerMode -> null
        steerSources.isNotEmpty() || (looking && !zoomChord) -> Pointer.Button.BUTTON_RIGHT
        else -> null
    }

    private fun update() {
        val desired = desiredButton()
        if (held != desired) {
            held?.let { sink.release(it) }
            held = null
            desired?.let { startDrag(it) }
        }
        if (looking) startTicker() else stopTicker()
    }

    private fun startDrag(button: Pointer.Button) {
        moveToReticle()
        sink.press(button)
        sink.moveBy(JIGGLE, 0)
        sink.moveBy(-JIGGLE, 0)
        held = button
    }

    private fun moveToReticle() = sink.moveTo((sink.width * RETICLE_X).toInt(), (sink.height * RETICLE_Y).toInt())

    /** One frame of stick input: turn, move the pointer, or zoom. */
    fun tick() {
        val x = lookX
        val y = lookY
        if (zoomChord && !pointerMode) {
            zoomAccumulator += y * ZOOM_STEPS_PER_TICK
            while (abs(zoomAccumulator) >= 1f) {
                val button = if (zoomAccumulator < 0) Pointer.Button.BUTTON_SCROLL_UP else Pointer.Button.BUTTON_SCROLL_DOWN
                sink.press(button)
                sink.release(button)
                zoomAccumulator -= if (zoomAccumulator < 0) -1f else 1f
            }
            return
        }
        zoomAccumulator = 0f
        val scale = PIXELS_PER_TICK * speed() * lookSpeed
        val dx = (x * scale).toInt()
        val dy = (y * scale).toInt()
        if (dx != 0 || dy != 0) sink.moveBy(dx, dy)
    }

    private fun startTicker() {
        if (!autoTick || timer != null) return
        timer = Timer("GamepadCamera", true).apply {
            schedule(object : TimerTask() {
                override fun run() = tick()
            }, 0, TICK_MS)
        }
    }

    private fun stopTicker() {
        timer?.cancel()
        timer = null
        zoomAccumulator = 0f
    }
}
