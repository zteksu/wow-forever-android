package app.azerothsurvivors

import android.view.KeyEvent

/**
 * A controller as the web Gamepad API's "standard" layout describes it: 17 buttons and 4 axes. Android's
 * WebView has no Gamepad API of its own, so [GameActivity] fills this from the controller's key and
 * motion events and the page reads it through `navigator.getGamepads()` (see [BRIDGE_SCRIPT]).
 */
class PadState {
    val buttons = FloatArray(17)
    val axes = FloatArray(4)
    var name = "Controller"
    var connected = false
    private var timestamp = 0L

    /** Applies a button press or release; returns false for keys that aren't controller buttons. */
    fun onKey(keyCode: Int, down: Boolean, now: Long): Boolean {
        val index = buttonIndex(keyCode) ?: return false
        buttons[index] = if (down) 1f else 0f
        touch(now)
        return true
    }

    /**
     * Applies a motion event's axis values: sticks (dead zone [flat] around the centre), analogue
     * triggers and a hat-style d-pad.
     */
    fun onMotion(lx: Float, ly: Float, rx: Float, ry: Float, lt: Float, rt: Float, hatX: Float, hatY: Float, flat: Float, now: Long) {
        axes[0] = deadZone(lx, flat)
        axes[1] = deadZone(ly, flat)
        axes[2] = deadZone(rx, flat)
        axes[3] = deadZone(ry, flat)
        buttons[LT] = lt.coerceIn(0f, 1f)
        buttons[RT] = rt.coerceIn(0f, 1f)
        buttons[UP] = if (hatY < -0.5f) 1f else 0f
        buttons[DOWN] = if (hatY > 0.5f) 1f else 0f
        buttons[LEFT] = if (hatX < -0.5f) 1f else 0f
        buttons[RIGHT] = if (hatX > 0.5f) 1f else 0f
        touch(now)
    }

    private fun touch(now: Long) {
        connected = true
        timestamp = now
    }

    /** The state for the page's bridge script, or "" before any controller input. */
    fun toJson(): String {
        if (!connected) return ""
        val b = buttons.joinToString(",") { format(it) }
        val a = axes.joinToString(",") { format(it) }
        val id = name.replace("\\", "\\\\").replace("\"", "\\\"")
        return """{"id":"$id","t":$timestamp,"b":[$b],"a":[$a]}"""
    }

    companion object {
        const val A = 0
        const val B = 1
        const val X = 2
        const val Y = 3
        const val LB = 4
        const val RB = 5
        const val LT = 6
        const val RT = 7
        const val SELECT = 8
        const val START = 9
        const val L3 = 10
        const val R3 = 11
        const val UP = 12
        const val DOWN = 13
        const val LEFT = 14
        const val RIGHT = 15
        const val HOME = 16

        fun buttonIndex(keyCode: Int): Int? = when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> A
            KeyEvent.KEYCODE_BUTTON_B -> B
            KeyEvent.KEYCODE_BUTTON_X -> X
            KeyEvent.KEYCODE_BUTTON_Y -> Y
            KeyEvent.KEYCODE_BUTTON_L1 -> LB
            KeyEvent.KEYCODE_BUTTON_R1 -> RB
            KeyEvent.KEYCODE_BUTTON_L2 -> LT
            KeyEvent.KEYCODE_BUTTON_R2 -> RT
            KeyEvent.KEYCODE_BUTTON_SELECT -> SELECT
            KeyEvent.KEYCODE_BUTTON_START -> START
            KeyEvent.KEYCODE_BUTTON_THUMBL -> L3
            KeyEvent.KEYCODE_BUTTON_THUMBR -> R3
            KeyEvent.KEYCODE_DPAD_UP -> UP
            KeyEvent.KEYCODE_DPAD_DOWN -> DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> RIGHT
            KeyEvent.KEYCODE_BUTTON_MODE -> HOME
            else -> null
        }

        internal fun deadZone(value: Float, flat: Float): Float {
            val zone = flat.coerceIn(0.05f, 0.5f)
            if (kotlin.math.abs(value) < zone) return 0f
            // Rescale so movement starts from 0 just outside the dead zone.
            return (kotlin.math.sign(value) * (kotlin.math.abs(value) - zone) / (1f - zone)).coerceIn(-1f, 1f)
        }

        private fun format(v: Float) = if (v == 0f) "0" else "%.4f".format(java.util.Locale.ROOT, v)

        /**
         * Runs before the page's own scripts. When the browser has no controllers of its own, it answers
         * `navigator.getGamepads()` with the app's [PadState], shaped like the Gamepad API's objects,
         * and fires `gamepadconnected` the first time one appears.
         */
        const val BRIDGE_SCRIPT = """
(() => {
  if (!window.AndroidPad) return;
  const own = navigator.getGamepads ? navigator.getGamepads.bind(navigator) : () => [];
  let announced = false;
  let pad = null;
  const read = () => {
    const json = AndroidPad.state();
    if (!json) return null;
    const s = JSON.parse(json);
    if (!pad) {
      pad = { id: s.id, index: 0, connected: true, mapping: 'standard', timestamp: 0, axes: [], buttons: [], vibrationActuator: null };
    }
    pad.id = s.id;
    pad.timestamp = s.t;
    pad.axes = s.a;
    pad.buttons = s.b.map((v) => ({ pressed: v > 0.5, touched: v > 0, value: v }));
    return pad;
  };
  navigator.getGamepads = function () {
    let real = [];
    try { real = Array.from(own() || []); } catch (e) {}
    if (real.some((p) => p)) return real;
    const p = read();
    if (p && !announced) {
      announced = true;
      const ev = new Event('gamepadconnected');
      ev.gamepad = p;
      setTimeout(() => window.dispatchEvent(ev), 0);
    }
    return [p, null, null, null];
  };
})();
"""
    }
}
