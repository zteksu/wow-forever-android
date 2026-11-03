package app.azerothsurvivors

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PadStateTest {
    @Test
    fun buttonsUseTheStandardGamepadLayout() {
        assertEquals(0, PadState.buttonIndex(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(3, PadState.buttonIndex(KeyEvent.KEYCODE_BUTTON_Y))
        assertEquals(9, PadState.buttonIndex(KeyEvent.KEYCODE_BUTTON_START))
        assertEquals(12, PadState.buttonIndex(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(15, PadState.buttonIndex(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertNull(PadState.buttonIndex(KeyEvent.KEYCODE_BACK))
    }

    @Test
    fun keysAndMotionFillTheState() {
        val pad = PadState()
        assertEquals("", pad.toJson())

        assertTrue(pad.onKey(KeyEvent.KEYCODE_BUTTON_A, true, 10))
        assertFalse(pad.onKey(KeyEvent.KEYCODE_VOLUME_UP, true, 11))
        pad.onMotion(lx = 1f, ly = -0.05f, rx = 0f, ry = 0f, lt = 0.5f, rt = 0f, hatX = -1f, hatY = 0f, flat = 0.1f, now = 20)

        assertEquals(1f, pad.buttons[PadState.A])
        assertEquals(1f, pad.axes[0])
        assertEquals(0f, pad.axes[1])
        assertEquals(0.5f, pad.buttons[PadState.LT])
        assertEquals(1f, pad.buttons[PadState.LEFT])
        assertEquals(0f, pad.buttons[PadState.RIGHT])

        val json = pad.toJson()
        assertTrue(json.startsWith("""{"id":"Controller","t":20,"b":[1.0000,0,0,0,0,0,0.5000,0,"""))
        assertTrue(json.endsWith(""""a":[1.0000,0,0,0]}"""))
    }

    @Test
    fun deadZoneRescalesFromItsEdge() {
        assertEquals(0f, PadState.deadZone(0.09f, 0.1f))
        assertEquals(0.5f, PadState.deadZone(0.55f, 0.1f), 0.0001f)
        assertEquals(-1f, PadState.deadZone(-1f, 0.1f))
    }

    @Test
    fun controllerNamesAreEscapedForTheScript() {
        val pad = PadState().apply { name = "Odin \"Pad\"" }
        pad.onKey(KeyEvent.KEYCODE_BUTTON_B, true, 1)
        assertTrue(pad.toJson().startsWith("""{"id":"Odin \"Pad\"","""))
    }

    @Test
    fun webViewVersionIsReadFromItsMajorNumber() {
        assertEquals(130, webViewMajor("130.0.6723.102"))
        assertNull(webViewMajor(null))
    }
}
