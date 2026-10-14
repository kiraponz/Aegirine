package com.kira3.ametrine.emucore.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsJsonTest {
    @Test
    fun roundTrips() {
        val settings = EmulatorSettings(
            cpu = CpuSettings(overclockPercent = 200, recompilerICache = true),
            gpu = GpuSettings(renderer = "vulkan", resolutionScale = 3),
        )
        assertEquals(settings, SettingsJson.decode(SettingsJson.parse(SettingsJson.format(SettingsJson.encode(settings)))))
    }

    @Test
    fun badValuesFallBackToDefaults() {
        val stored = SettingsJson.parse(
            """{"gpu": {"resolutionScale": 1.5, "pgxpEnable": "no", "multisamples": 4}, "cpu": {"executionMode": 3}, "gone": {"x": 1}}""",
        )
        val settings = SettingsJson.decode(stored)
        assertEquals(1, settings.gpu.resolutionScale)
        assertEquals(false, settings.gpu.pgxpEnable)
        assertEquals(4, settings.gpu.multisamples)
        assertEquals("Recompiler", settings.cpu.executionMode)
    }

    @Test
    fun unreadableFileIsDefaults() {
        assertEquals(EmulatorSettings(), SettingsJson.decode(SettingsJson.parse("not json")))
    }

    @Test
    fun overridesRecordEveryEditedValue() {
        val global = EmulatorSettings()
        val edited = global.copy(gpu = global.gpu.copy(pgxpEnable = true))
        val overrides = SettingsOverrides().record(global, edited)
        assertEquals(setOf("gpu.pgxpEnable"), overrides.paths)
        assertEquals(edited, SettingsJson.merge(global, overrides))

        // An override equal to the global value stays: the player chose it for this game.
        val back = overrides.record(edited, global)
        assertEquals(setOf("gpu.pgxpEnable"), back.paths)

        // A later global change reaches only the values the game does not override.
        val newGlobal = global.copy(gpu = global.gpu.copy(pgxpEnable = true, resolutionScale = 4))
        val merged = SettingsJson.merge(newGlobal, back)
        assertEquals(false, merged.gpu.pgxpEnable)
        assertEquals(4, merged.gpu.resolutionScale)

        assertTrue(back.without(listOf("gpu.pgxpEnable")).isEmpty())
    }

    @Test
    fun keysCarryTheirFields() {
        val keys = CoreKeys.of(EmulatorSettings()).associateBy { it.id }
        assertEquals(setOf("gpu.renderer"), keys.getValue("GPU/Renderer").fields)
        assertEquals("Automatic", keys.getValue("GPU/Renderer").value)
        assertEquals("Auto (Game Native)", keys.getValue("Display/AspectRatio").value)
        assertEquals("1.0", keys.getValue("Main/EmulationSpeed").value)
        assertEquals("PerGameTitle", keys.getValue("MemoryCards/Card1Type").value)
        // No key is written twice, and none clashes with a pinned one.
        assertEquals(keys.size, CoreKeys.of(EmulatorSettings()).size)
        assertTrue(CoreKeys.pinned.none { it.id in keys })
    }

    @Test
    fun speedAndOverclockAreComputed() {
        val keys = CoreKeys.of(
            EmulatorSettings(
                frameLimit = FrameLimitSettings(frameLimitEnable = false),
                cpu = CpuSettings(overclockPercent = 150),
            ),
        ).associateBy { it.id }
        assertEquals("0.0", keys.getValue("Main/EmulationSpeed").value)
        assertEquals("true", keys.getValue("CPU/OverclockEnable").value)
        assertEquals("150", keys.getValue("CPU/OverclockNumerator").value)
    }

    @Test
    fun gameBiosGoesToEveryRegion() {
        val keys = CoreKeys.of(EmulatorSettings(system = SystemSettings(biosFilename = "scph7000.bin"))).associateBy { it.id }
        for (key in listOf("BIOS/PathNTSCJ", "BIOS/PathNTSCU", "BIOS/PathPAL")) assertEquals("scph7000.bin", keys.getValue(key).value)
    }

    @Test
    fun copyPathsKeepsOnlyThosePaths() {
        val boot = EmulatorSettings(gpu = GpuSettings(renderer = "opengl"))
        val now = EmulatorSettings(gpu = GpuSettings(renderer = "vulkan", resolutionScale = 2))
        val held = SettingsJson.copyPaths(now, boot, SettingsEffects.appliesOnNextBoot)
        assertEquals("opengl", held.gpu.renderer)
        assertEquals(2, held.gpu.resolutionScale)
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
