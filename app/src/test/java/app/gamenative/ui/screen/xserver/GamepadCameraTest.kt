package app.gamenative.ui.screen.xserver

import com.winlator.xserver.Pointer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GamepadCameraTest {
    private class FakeSink : GamepadCamera.Sink {
        override val width = 1000
        override val height = 600
        val log = mutableListOf<String>()
        override fun press(button: Pointer.Button) { log += "press $button" }
        override fun release(button: Pointer.Button) { log += "release $button" }
        override fun moveBy(dx: Int, dy: Int) { log += "by $dx,$dy" }
        override fun moveTo(x: Int, y: Int) { log += "to $x,$y" }
    }

    private val sink = FakeSink()
    private val camera = GamepadCamera(sink, autoTick = false)

    private fun take(): List<String> = sink.log.toList().also { sink.log.clear() }

    @Test
    fun lookingWhileStillOrbitsWithTheLeftButtonFromTheReticle() {
        camera.setLook(GamepadCamera.Look.RIGHT, 0.5f)
        assertEquals(listOf("to 500,270", "press BUTTON_LEFT", "by 2,0", "by -2,0"), take())

        camera.tick()
        assertEquals(listOf("by 5,0"), take())

        camera.setLook(GamepadCamera.Look.RIGHT, 0f)
        assertEquals(listOf("release BUTTON_LEFT"), take())
    }

    @Test
    fun movingSteersWithTheRightButtonSoTurnKeysStrafe() {
        camera.setLook(GamepadCamera.Look.LEFT, 1f)
        take()
        camera.setSteer("left stick up", true)
        assertEquals(listOf("release BUTTON_LEFT", "to 500,270", "press BUTTON_RIGHT", "by 2,0", "by -2,0"), take())

        // A second stick direction doesn't press again.
        camera.setSteer("left stick right", true)
        camera.setLook(GamepadCamera.Look.LEFT, 0f)
        assertEquals(emptyList<String>(), take())

        camera.setSteer("left stick up", false)
        camera.setSteer("left stick right", false)
        assertEquals(listOf("release BUTTON_RIGHT"), take())
    }

    @Test
    fun pointerModeMovesThePointerWithoutButtons() {
        camera.manualPointer = true
        camera.setLook(GamepadCamera.Look.DOWN, 1f)
        camera.setSteer("stick", true)
        camera.tick()
        assertEquals(listOf("by 0,10"), take())

        camera.togglePointer()
        assertEquals(false, camera.pointerMode)
        assertEquals("press BUTTON_RIGHT", take()[1])
    }

    @Test
    fun togglingLeavesAddonPointerModeToo() {
        camera.addonPointer = true
        camera.togglePointer()
        assertEquals(false, camera.pointerMode)
        camera.togglePointer()
        assertTrue(camera.manualPointer)
    }

    @Test
    fun bumperChordZoomsWithTheWheel() {
        camera.zoomChord = true
        camera.setLook(GamepadCamera.Look.UP, 1f)
        assertEquals(emptyList<String>(), take())
        repeat(4) { camera.tick() }
        assertEquals(listOf("press BUTTON_SCROLL_UP", "release BUTTON_SCROLL_UP"), take())
    }

    @Test
    fun interactRightClicksTheReticleAndResumesSteering() {
        camera.setSteer("stick", true)
        take()
        camera.interactClick()
        assertEquals(
            listOf(
                "release BUTTON_RIGHT", "to 500,270", "press BUTTON_RIGHT", "release BUTTON_RIGHT",
                "to 500,270", "press BUTTON_RIGHT", "by 2,0", "by -2,0",
            ),
            take(),
        )
    }

    @Test
    fun interactDoesNothingWhileTheAddonHasTheButtonsOrThePointerIsOut() {
        camera.addonCapture = true
        camera.interactClick()
        camera.addonCapture = false
        camera.addonPointer = true
        camera.interactClick()
        assertEquals(emptyList<String>(), take())
    }

    @Test
    fun resetReleasesEverything() {
        camera.setLook(GamepadCamera.Look.UP, 1f)
        take()
        camera.reset(pointer = true)
        assertEquals(listOf("release BUTTON_LEFT"), take())
        assertTrue(camera.manualPointer)
    }
}
