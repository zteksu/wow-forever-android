package app.gamenative.ui.screen.xserver

import android.view.KeyEvent
import com.winlator.inputcontrols.Binding
import com.winlator.inputcontrols.BindingCombo
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.ExternalController
import com.winlator.inputcontrols.ExternalControllerBinding
import com.winlator.inputcontrols.GamepadState
import com.winlator.xserver.Pointer
import com.winlator.xserver.XKeycode
import com.winlator.xserver.XServer
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PhysicalControllerHandlerCameraTest {
    private val deviceId = 7

    private fun binding(keyCode: Int, vararg bindings: Binding) = ExternalControllerBinding().apply {
        setKeyCode(keyCode)
        if (bindings.size == 1) setBinding(bindings[0]) else setBindingCombo(BindingCombo.fromBindings(bindings.toList()))
    }

    private fun key(keyCode: Int, action: Int): KeyEvent {
        val event = mock<KeyEvent>()
        whenever(event.repeatCount).thenReturn(0)
        whenever(event.deviceId).thenReturn(deviceId)
        whenever(event.keyCode).thenReturn(keyCode)
        whenever(event.action).thenReturn(action)
        return event
    }

    private fun press(handler: PhysicalControllerHandler, keyCode: Int) {
        handler.onKeyEvent(key(keyCode, KeyEvent.ACTION_DOWN))
        handler.onKeyEvent(key(keyCode, KeyEvent.ACTION_UP))
    }

    @Test
    fun `hand-picked pointer mode turns A into a left click and keeps X off the world`() {
        val controller = mock<ExternalController>()
        val bindings = mapOf(
            KeyEvent.KEYCODE_BUTTON_A to binding(KeyEvent.KEYCODE_BUTTON_A, Binding.KEY_F1),
            KeyEvent.KEYCODE_BUTTON_X to binding(KeyEvent.KEYCODE_BUTTON_X, Binding.INTERACT_CLICK, Binding.KEY_F3),
            KeyEvent.KEYCODE_BUTTON_SELECT to binding(KeyEvent.KEYCODE_BUTTON_SELECT, Binding.POINTER_MODE),
        )
        bindings.forEach { (code, b) -> whenever(controller.getControllerBinding(code)).thenReturn(b) }
        val profile = mock<ControlsProfile>()
        whenever(profile.getController(deviceId)).thenReturn(controller)
        whenever(profile.gamepadState).thenReturn(GamepadState())
        val xServer = mock<XServer>()
        val handler = PhysicalControllerHandler(profile, xServer, gamepadStateSender = {})

        try {
            press(handler, KeyEvent.KEYCODE_BUTTON_SELECT)
            assertTrue(handler.camera!!.manualPointer)

            press(handler, KeyEvent.KEYCODE_BUTTON_A)
            verify(xServer).injectPointerButtonPress(Pointer.Button.BUTTON_LEFT)
            verify(xServer).injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT)
            verify(xServer, never()).injectKeyPress(XKeycode.KEY_F1)

            press(handler, KeyEvent.KEYCODE_BUTTON_X)
            verify(xServer).injectKeyPress(XKeycode.KEY_F3)
            verify(xServer, never()).injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT)
            verify(xServer, never()).injectPointerMove(any(), any())
        } finally {
            handler.cleanup()
        }
    }
}
