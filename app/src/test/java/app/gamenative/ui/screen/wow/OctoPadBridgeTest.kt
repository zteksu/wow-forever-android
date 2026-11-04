package app.gamenative.ui.screen.wow

import app.gamenative.ui.screen.xserver.GamepadCamera
import com.winlator.xserver.Pointer
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OctoPadBridgeTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val sink = object : GamepadCamera.Sink {
        override val width = 100
        override val height = 100
        override fun press(button: Pointer.Button) {}
        override fun release(button: Pointer.Button) {}
        override fun moveBy(dx: Int, dy: Int) {}
        override fun moveTo(x: Int, y: Int) {}
    }

    @Test
    fun parse_readsTheAddonsState() {
        val state = OctoPadBridge.parse("pointer=1\ncapture=1\nkeyboard=3\nspeed=1.50\n")!!
        assertEquals(OctoPadBridge.State(pointer = 1, capture = true, keyboard = 3, speed = 1.5f), state)
        assertNull(OctoPadBridge.parse("half a wri"))
        assertEquals(4f, OctoPadBridge.parse("pointer=0\nspeed=99")!!.speed)
    }

    @Test
    fun apply_switchesCameraModesAndAsksForTheKeyboardOnChange() {
        val camera = GamepadCamera(sink, autoTick = false)
        var keyboards = 0
        val bridge = OctoPadBridge(File(tempFolder.root, "OctoPad.txt"), camera) { keyboards++ }

        bridge.apply(OctoPadBridge.State(pointer = 0, capture = false, keyboard = 5, speed = 1f))
        assertFalse(camera.pointerMode)
        assertEquals(0, keyboards)

        bridge.apply(OctoPadBridge.State(pointer = 1, capture = false, keyboard = 6, speed = 2f))
        assertTrue(camera.addonPointer)
        assertFalse(camera.manualPointer)
        assertTrue(camera.addonCapture)
        assertEquals(2f, camera.lookSpeed)
        assertEquals(1, keyboards)

        bridge.apply(OctoPadBridge.State(pointer = 2, capture = true, keyboard = 6, speed = 2f))
        assertTrue(camera.manualPointer)
        assertFalse(camera.addonPointer)

        // A hand toggle sticks until the addon's state changes again.
        camera.togglePointer()
        bridge.apply(OctoPadBridge.State(pointer = 2, capture = true, keyboard = 6, speed = 2f))
        assertFalse(camera.pointerMode)
    }

    @Test
    fun stateFile_isWhereVanillaHelpersWrites() {
        assertEquals(File("/g/VanillaHelpersData/OctoPad.txt"), OctoPadBridge.stateFile(File("/g")))
    }
}

class MpqClientVanillaHelpersTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun enableVanillaHelpers_appendsTheDllOnce() {
        val root = tempFolder.root
        File(root, "VanillaHelpers.dll").writeText("")
        val list = File(root, "dlls.txt").apply { writeText("nampower.dll\nUnitXP_SP3.dll") }

        MpqClient.enableVanillaHelpers(root)
        MpqClient.enableVanillaHelpers(root)

        assertEquals("nampower.dll\nUnitXP_SP3.dll\nVanillaHelpers.dll\n", list.readText())
    }

    @Test
    fun enableVanillaHelpers_leavesClientsWithoutTheDllAlone() {
        val list = File(tempFolder.root, "dlls.txt").apply { writeText("nampower.dll\n") }
        MpqClient.enableVanillaHelpers(tempFolder.root)
        assertEquals("nampower.dll\n", list.readText())
    }
}
