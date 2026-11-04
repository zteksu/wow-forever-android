package com.winlator.container

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ContainerWinComponentsTest {
    @Test
    fun checkObsoleteOrMissingProperties_keepsComponentsTheDefaultsDontList() {
        val data = JSONObject().put("wincomponents", "direct3d=0,wmdecoder=0,xaudio=1")

        Container.checkObsoleteOrMissingProperties(data)

        val components = data.getString("wincomponents").split(",").associate { it.substringBefore("=") to it.substringAfter("=") }
        assertEquals("1", components["xaudio"])
        assertEquals("0", components["direct3d"])
        assertEquals("0", components["wmdecoder"])
        // Missing defaults are still filled in.
        assertEquals("1", components["vcrun2010"])
    }
}
