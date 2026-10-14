package com.kira3.ametrine.emucore.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsValuesTest {
    @Test
    fun readsEachType() {
        val values = SettingsValues(EmulatorSettings())
        assertEquals(false, values["gpu.pgxpEnable"])
        assertEquals(1, values["gpu.resolutionScale"])
        assertEquals("auto", values["gpu.renderer"])
    }

    @Test
    fun writesByPath() {
        val values = SettingsValues(EmulatorSettings())
        assertEquals(true, values.with("gpu.pgxpEnable", true).gpu.pgxpEnable)
        assertEquals(3, values.with("gpu.resolutionScale", 3).gpu.resolutionScale)
        assertEquals("vulkan", values.with("gpu.renderer", "vulkan").gpu.renderer)
    }

    @Test
    fun wrongTypeIsIgnored() {
        assertEquals(EmulatorSettings(), SettingsValues(EmulatorSettings()).with("gpu.resolutionScale", "fast"))
    }

    @Test(expected = Exception::class)
    fun unknownPathThrows() {
        SettingsValues(EmulatorSettings())["gpu.nope"]
    }
}
