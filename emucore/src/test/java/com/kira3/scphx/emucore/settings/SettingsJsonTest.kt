package com.kira3.scphx.emucore.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsJsonTest {
    @Test
    fun roundTrips() {
        val settings = EmulatorSettings(
            cpu = CpuSettings(eeCycleRate = 2, mtvu = false),
            output = OutputSettings(renderer = "vulkan", upscaleFloat = 2.5f),
        )
        assertEquals(settings, SettingsJson.decode(SettingsJson.parse(SettingsJson.format(SettingsJson.encode(settings)))))
    }

    @Test
    fun badValuesFallBackToDefaults() {
        val stored = SettingsJson.parse(
            """{"cpu": {"eeCycleRate": 1.5, "mtvu": "no", "eeCycleSkip": 2}, "output": {"upscaleFloat": 3}, "gone": {"x": 1}}""",
        )
        val settings = SettingsJson.decode(stored)
        assertEquals(0, settings.cpu.eeCycleRate)
        assertEquals(true, settings.cpu.mtvu)
        assertEquals(2, settings.cpu.eeCycleSkip)
        assertEquals(3f, settings.output.upscaleFloat)
    }

    @Test
    fun unreadableFileIsDefaults() {
        assertEquals(EmulatorSettings(), SettingsJson.decode(SettingsJson.parse("not json")))
    }

    @Test
    fun overridesRecordEveryEditedValue() {
        val global = EmulatorSettings()
        val edited = global.copy(graphics = global.graphics.copy(fxaa = true))
        val overrides = SettingsOverrides().record(global, edited)
        assertEquals(setOf("graphics.fxaa"), overrides.paths)
        assertEquals(edited, SettingsJson.merge(global, overrides))

        // An override equal to the global value stays: the player chose it for this game.
        val back = overrides.record(edited, global)
        assertEquals(setOf("graphics.fxaa"), back.paths)

        // A later global change reaches only the values the game does not override.
        val newGlobal = global.copy(graphics = global.graphics.copy(fxaa = true, casMode = 1))
        val merged = SettingsJson.merge(newGlobal, back)
        assertEquals(false, merged.graphics.fxaa)
        assertEquals(1, merged.graphics.casMode)

        assertTrue(back.without(listOf("graphics.fxaa")).isEmpty())
    }

    @Test
    fun keysCarryTheirFields() {
        val keys = CoreKeys.of(EmulatorSettings()).associateBy { it.id }
        assertEquals(setOf("output.renderer"), keys.getValue("EmuCore/GS/Renderer").fields)
        assertEquals("-1", keys.getValue("EmuCore/GS/Renderer").value)
        assertEquals(setOf("cpu.vuClampMode", "cpu.vu1ClampMode"), keys.getValue("EmuCore/CPU/Recompiler/vu1Overflow").fields)
        assertEquals("Auto 4:3/3:2", keys.getValue("EmuCore/GS/AspectRatio").value)
        assertEquals("false", keys.getValue("EmuCore/GS/UserHacks").value)
        // No key is written twice.
        assertEquals(keys.size, CoreKeys.of(EmulatorSettings()).size)
    }

    @Test
    fun displayZoomCropsEvenly() {
        val keys = CoreKeys.of(EmulatorSettings(hwFixes = HwFixesSettings(displayZoom = 125, cropLeft = 9))).associateBy { it.id }
        assertEquals("63", keys.getValue("EmuCore/GS/CropLeft").value)
        assertEquals("63", keys.getValue("EmuCore/GS/CropRight").value)
        assertEquals("44", keys.getValue("EmuCore/GS/CropTop").value)
    }

    @Test
    fun copyPathsKeepsOnlyThosePaths() {
        val boot = EmulatorSettings(output = OutputSettings(renderer = "opengl"))
        val now = EmulatorSettings(output = OutputSettings(renderer = "vulkan", upscaleFloat = 2f))
        val held = SettingsJson.copyPaths(now, boot, SettingsEffects.appliesOnNextBoot)
        assertEquals("opengl", held.output.renderer)
        assertEquals(2f, held.output.upscaleFloat)
    }

    @Test
    fun nextBootPathsExist() {
        val all = leafPaths(SettingsJson.encode(EmulatorSettings()))
        for (path in SettingsEffects.appliesOnNextBoot) assertTrue(path, path in all)
    }

    private fun leafPaths(obj: kotlinx.serialization.json.JsonObject, prefix: String = ""): Set<String> = buildSet {
        for ((key, value) in obj) {
            if (value is kotlinx.serialization.json.JsonObject) addAll(leafPaths(value, "$prefix$key."))
            else add(prefix + key)
        }
    }
}
