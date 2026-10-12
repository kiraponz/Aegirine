package com.kira3.scphx.emucore.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsValuesTest {
    @Test
    fun readsEachType() {
        val values = SettingsValues(EmulatorSettings())
        assertEquals(false, values["graphics.fxaa"])
        assertEquals(1, values["graphics.accurateBlendingUnit"])
        assertEquals(1.0f, values["output.upscaleFloat"])
        assertEquals("auto", values["output.renderer"])
    }

    @Test
    fun writesByPath() {
        val values = SettingsValues(EmulatorSettings())
        assertEquals(true, values.with("graphics.fxaa", true).graphics.fxaa)
        assertEquals(3, values.with("cpu.eeCycleSkip", 3).cpu.eeCycleSkip)
        assertEquals(2.5f, values.with("output.upscaleFloat", 2.5f).output.upscaleFloat)
        assertEquals("vulkan", values.with("output.renderer", "vulkan").output.renderer)
    }

    @Test
    fun wholeFloatReadsBack() {
        val values = SettingsValues(SettingsValues(EmulatorSettings()).with("output.upscaleFloat", 2f))
        assertEquals(2f, values.float("output.upscaleFloat"))
    }

    @Test
    fun wrongTypeIsIgnored() {
        assertEquals(EmulatorSettings(), SettingsValues(EmulatorSettings()).with("cpu.eeCycleSkip", "fast"))
    }

    @Test(expected = Exception::class)
    fun unknownPathThrows() {
        SettingsValues(EmulatorSettings())["graphics.nope"]
    }

    @Test
    fun defaultsMatchOptimal() {
        assertEquals(PerformancePreset.Optimal, PerformancePreset.matching(EmulatorSettings(), cores = 8))
    }

    @Test
    fun presetsRoundTrip() {
        for (preset in PerformancePreset.entries) {
            assertEquals(preset, PerformancePreset.matching(preset.applyTo(EmulatorSettings(), cores = 8), cores = 8))
        }
        val tuned = PerformancePreset.Fast.applyTo(EmulatorSettings(), cores = 8).let { it.copy(cpu = it.cpu.copy(eeCycleSkip = 3)) }
        assertNull(PerformancePreset.matching(tuned, cores = 8))
    }
}
