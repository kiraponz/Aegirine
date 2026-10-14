package com.kira3.ametrine.emucore.settings

import kotlin.reflect.KProperty0

/** One value in the core's INI-style settings (the same section/key names as DuckStation). */
internal data class CoreKey(
    val section: String,
    val key: String,
    val value: String,
    /** Dotted paths of the [EmulatorSettings] fields this key is computed from. */
    val fields: Set<String>,
) {
    val id: String get() = "$section/$key"
}

/**
 * Maps [EmulatorSettings] to the core's settings keys. The single place that knows key names;
 * docs/CORE_SETTINGS.md lists them.
 */
internal object CoreKeys {
    fun of(s: EmulatorSettings): List<CoreKey> = KeyWriter().apply { write(s) }.keys

    /**
     * Keys the app fixes whatever the settings say: features it leaves out, and behavior it
     * handles itself. Written on every apply.
     */
    val pinned: List<CoreKey> = KeyWriter().apply {
        put("Cheevos", "Enabled", "false")
        put("Main", "EnableDiscordPresence", "false")
        // Per-game settings are the app's overrides; the core's own game INIs are not used.
        put("Main", "ApplyGameSettings", "false")
        // The GameDB's fixes for each game.
        put("Main", "ApplyCompatibilitySettings", "true")
        // The activity pauses, saves and exits the game itself.
        put("Main", "PauseOnFocusLoss", "false")
        put("Main", "StartPaused", "false")
        put("Main", "SaveStateOnExit", "false")
        put("Main", "ConfirmPowerOff", "false")
        put("PINE", "Enabled", "false")
        put("Debug", "EnableGDBServer", "false")
        put("TextureReplacements", "EnableVRAMWriteReplacements", "false")
        put("TextureReplacements", "DumpVRAMWrites", "false")
        put("Audio", "Backend", "AAudio")
        // Cheats are the app's (EmulatorCore.setCheats), not the core's own files.
        put("Console", "EnableCheats", "false")
        // The app picks the picture's alignment from the screen's shape (android_host.cpp).
        put("Display", "Alignment", "Center")
    }.keys

    /** The core's GPU/Renderer for the app's renderer value. */
    fun rendererName(renderer: String): String = when (renderer) {
        "opengl" -> "OpenGL"
        "vulkan" -> "Vulkan"
        "software" -> "Software"
        else -> "Automatic"
    }

    private class KeyWriter {
        val keys = ArrayList<CoreKey>()

        fun put(section: String, key: String, value: String, fields: Set<String> = emptySet()) {
            keys += CoreKey(section, key, value, fields)
        }

        fun group(name: String) = Group(name)

        inner class Group(private val name: String) {
            fun path(property: KProperty0<*>) = "$name.${property.name}"

            fun bool(property: KProperty0<Boolean>, section: String, key: String) =
                put(section, key, property.get().toString(), setOf(path(property)))

            fun int(property: KProperty0<Int>, section: String, key: String, range: IntRange? = null) {
                val value = property.get().let { if (range != null) it.coerceIn(range) else it }
                put(section, key, value.toString(), setOf(path(property)))
            }

            fun string(property: KProperty0<String>, section: String, key: String) =
                put(section, key, property.get(), setOf(path(property)))
        }

        fun write(s: EmulatorSettings) {
            writeConsole(s.console)
            writeCpu(s.cpu)
            writeGpu(s.gpu)
            writeDisplay(s.display)
            writeFrameLimit(s.frameLimit)
            writeAudio(s.audio)
            writeOsd(s.osd)
            writeSystem(s.system)
            writeControls(s.controls)
            writeRewind(s.rewind)
        }

        private fun writeConsole(c: ConsoleSettings) = with(c) {
            val g = group(EmulatorSettings::console.name)
            g.string(::region, "Console", "Region")
            g.bool(::enable8MbRam, "Console", "Enable8MBRAM")
            g.bool(::fastBoot, "BIOS", "PatchFastBoot")
            g.int(::cdromReadSpeedup, "CDROM", "ReadSpeedup", 0..10)
            g.int(::cdromSeekSpeedup, "CDROM", "SeekSpeedup", 0..10)
            g.bool(::loadImageToRam, "CDROM", "LoadImageToRAM")
        }

        private fun writeCpu(c: CpuSettings) = with(c) {
            val g = group(EmulatorSettings::cpu.name)
            g.string(::executionMode, "CPU", "ExecutionMode")
            val percent = overclockPercent.coerceIn(10, 1000)
            val overclock = setOf(g.path(::overclockPercent))
            put("CPU", "OverclockEnable", (percent != 100).toString(), overclock)
            put("CPU", "OverclockNumerator", percent.toString(), overclock)
            put("CPU", "OverclockDenominator", "100", overclock)
            g.bool(::recompilerICache, "CPU", "RecompilerICache")
            g.string(::fastmemMode, "CPU", "FastmemMode")
        }

        private fun writeGpu(gpu: GpuSettings) = with(gpu) {
            val g = group(EmulatorSettings::gpu.name)
            put("GPU", "Renderer", rendererName(renderer), setOf(g.path(::renderer)))
            g.int(::resolutionScale, "GPU", "ResolutionScale", 0..8)
            g.int(::multisamples, "GPU", "Multisamples", 1..8)
            g.bool(::perSampleShading, "GPU", "PerSampleShading")
            g.bool(::trueColor, "GPU", "TrueColor")
            g.bool(::scaledDithering, "GPU", "ScaledDithering")
            g.bool(::debanding, "GPU", "Debanding")
            g.string(::textureFilter, "GPU", "TextureFilter")
            // Sprites follow the texture filter.
            put("GPU", "SpriteTextureFilter", textureFilter, setOf(g.path(::textureFilter)))
            g.string(::lineDetectMode, "GPU", "LineDetectMode")
            g.string(::downsampleMode, "GPU", "DownsampleMode")
            g.bool(::disableInterlacing, "GPU", "DisableInterlacing")
            g.bool(::forceNtscTimings, "GPU", "ForceNTSCTimings")
            g.bool(::widescreenHack, "GPU", "WidescreenHack")
            g.bool(::chromaSmoothing24Bit, "GPU", "ChromaSmoothing24Bit")
            g.bool(::accurateBlending, "GPU", "AccurateBlending")
            g.bool(::pgxpEnable, "GPU", "PGXPEnable")
            g.bool(::pgxpCulling, "GPU", "PGXPCulling")
            g.bool(::pgxpTextureCorrection, "GPU", "PGXPTextureCorrection")
            g.bool(::pgxpColorCorrection, "GPU", "PGXPColorCorrection")
            g.bool(::pgxpVertexCache, "GPU", "PGXPVertexCache")
            g.bool(::pgxpCpu, "GPU", "PGXPCPU")
            g.bool(::pgxpPreserveProjFp, "GPU", "PGXPPreserveProjFP")
            g.bool(::pgxpDepthBuffer, "GPU", "PGXPDepthBuffer")
            g.bool(::useThread, "GPU", "UseThread")
            g.bool(::threadedPresentation, "GPU", "ThreadedPresentation")
        }

        private fun writeDisplay(d: DisplaySettings) = with(d) {
            val g = group(EmulatorSettings::display.name)
            g.string(::aspectRatio, "Display", "AspectRatio")
            g.int(::customAspectNumerator, "Display", "CustomAspectRatioNumerator", 1..1000)
            g.int(::customAspectDenominator, "Display", "CustomAspectRatioDenominator", 1..1000)
            g.string(::cropMode, "Display", "CropMode")
            g.string(::deinterlacingMode, "Display", "DeinterlacingMode")
            g.string(::scaling, "Display", "Scaling")
            g.bool(::force43For24Bit, "Display", "Force4_3For24Bit")
            g.bool(::vsync, "Display", "VSync")
            g.bool(::optimalFramePacing, "Display", "OptimalFramePacing")
            g.bool(::syncToHostRefreshRate, "Main", "SyncToHostRefreshRate")
            g.bool(::skipDuplicateFrames, "Display", "SkipPresentingDuplicateFrames")
        }

        private fun writeFrameLimit(f: FrameLimitSettings) = with(f) {
            val g = group(EmulatorSettings::frameLimit.name)
            val speed = if (frameLimitEnable) nominalSpeedPercent.coerceIn(10, 1000) / 100f else 0f
            put("Main", "EmulationSpeed", speed.toString(), setOf(g.path(::frameLimitEnable), g.path(::nominalSpeedPercent)))
            put("Main", "FastForwardSpeed", fastForwardSpeed.coerceIn(0, 10).toFloat().toString(), setOf(g.path(::fastForwardSpeed)))
        }

        private fun writeAudio(a: AudioSettings) = with(a) {
            val g = group(EmulatorSettings::audio.name)
            g.int(::volume, "Audio", "OutputVolume", 0..200)
            g.int(::fastForwardVolume, "Audio", "FastForwardVolume", 0..200)
            g.bool(::muted, "Audio", "OutputMuted")
            g.string(::stretchMode, "Audio", "StretchMode")
            g.int(::bufferMs, "Audio", "BufferMS", 10..500)
            g.int(::outputLatencyMs, "Audio", "OutputLatencyMS", 1..500)
            g.bool(::outputLatencyMinimal, "Audio", "OutputLatencyMinimal")
            g.bool(::muteCdAudio, "CDROM", "MuteCDAudio")
        }

        private fun writeOsd(o: OsdSettings) = with(o) {
            val g = group(EmulatorSettings::osd.name)
            g.bool(::showFps, "Display", "ShowFPS")
            g.bool(::showSpeed, "Display", "ShowSpeed")
            g.bool(::showResolution, "Display", "ShowResolution")
            g.bool(::showCpu, "Display", "ShowCPU")
            g.bool(::showGpu, "Display", "ShowGPU")
            g.bool(::showFrameTimes, "Display", "ShowFrameTimes")
            g.bool(::showGpuStats, "Display", "ShowGPUStatistics")
            g.bool(::showInputs, "Display", "ShowInputs")
            g.bool(::showEnhancements, "Display", "ShowEnhancements")
            g.bool(::showMessages, "Display", "ShowOSDMessages")
            g.bool(::showStatusIndicators, "Display", "ShowStatusIndicators")
            put("Display", "OSDScale", scale.coerceIn(25, 500).toFloat().toString(), setOf(g.path(::scale)))
        }

        private fun writeSystem(s: SystemSettings) = with(s) {
            val g = group(EmulatorSettings::system.name)
            // The game's own BIOS for every region; otherwise the core picks one for the region from the BIOS folder.
            for (key in listOf("PathNTSCJ", "PathNTSCU", "PathPAL")) put("BIOS", key, biosFilename, setOf(g.path(::biosFilename)))
            g.string(::memoryCard1Type, "MemoryCards", "Card1Type")
            g.string(::memoryCard1File, "MemoryCards", "Card1Path")
            g.string(::memoryCard2Type, "MemoryCards", "Card2Type")
            g.string(::memoryCard2File, "MemoryCards", "Card2Path")
        }

        private fun writeRewind(r: RewindSettings) = with(r) {
            val g = group(EmulatorSettings::rewind.name)
            g.bool(::enabled, "Main", "RewindEnable")
            put("Main", "RewindFrequency", intervalSeconds.coerceIn(1, 60).toFloat().toString(), setOf(g.path(::intervalSeconds)))
            g.int(::keep, "Main", "RewindSaveSlots", 1..20)
        }

        private fun writeControls(c: ControlsSettings) = with(c) {
            val g = group(EmulatorSettings::controls.name)
            g.string(::padType, "Pad1", "Type")
            g.bool(::forceAnalogOnReset, "Pad1", "ForceAnalogOnReset")
            g.bool(::analogDpadInDigitalMode, "Pad1", "AnalogDPadInDigitalMode")
        }
    }
}
