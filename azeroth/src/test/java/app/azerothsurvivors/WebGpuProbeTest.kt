package app.azerothsurvivors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebGpuProbeTest {
    @Test
    fun anAdapterMeansReady() {
        val status = WebGpuProbe.parse(
            """{"gpu":true,"adapter":true,"compat":false,"vendor":"qualcomm","architecture":"adreno-8xx","description":"Adreno (TM) 830","maxBufferSize":2147483648,"maxStorageBuffers":10}""",
        )
        assertTrue(status.usable)
        assertEquals("Adreno (TM) 830", status.gpuName)
        assertEquals("WebGPU ready: Adreno (TM) 830.", status.summary())
        assertEquals(10, status.maxStorageBuffers)
    }

    @Test
    fun noAdapterMeansTheGpuIsNotOffered() {
        val status = WebGpuProbe.parse("""{"gpu":true,"adapter":false,"compat":false}""")
        assertFalse(status.usable)
        assertTrue(status.summary().startsWith("Android's WebView offers no WebGPU on this phone's GPU"))
    }

    @Test
    fun compatibilityOnlyIsReportedAsSuch() {
        val status = WebGpuProbe.parse("""{"gpu":true,"adapter":false,"compat":true,"vendor":"samsung","architecture":"xclipse"}""")
        assertFalse(status.usable)
        assertTrue(status.summary().contains("compatibility mode"))
        assertTrue(status.summary().contains("samsung"))
    }

    @Test
    fun aMissingApiAndASoftwareAdapterAreCalledOut() {
        assertEquals("This WebView has no WebGPU.", WebGpuProbe.parse("""{"gpu":false}""").summary())
        val software = WebGpuProbe.parse("""{"gpu":true,"adapter":true,"fallback":true,"description":"SwiftShader"}""")
        assertTrue(software.summary().contains("software"))
    }

    @Test
    fun unreadableAnswersKeepTheirText() {
        assertTrue(WebGpuProbe.parse("not json").error.startsWith("Unreadable result"))
    }

    @Test
    fun theProbePageAsksForBothKindsOfAdapter() {
        assertTrue(WebGpuProbe.PAGE.contains("requestAdapter"))
        assertTrue(WebGpuProbe.PAGE.contains("featureLevel: 'compatibility'"))
        assertTrue(WebGpuProbe.PAGE.contains("AndroidProbe.result"))
    }
}
