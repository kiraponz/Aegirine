package com.kira3.scphx.emucore.settings

import kotlin.reflect.KProperty0

/** One value in the core's INI-style settings (the same section/key names as PCSX2). */
internal data class CoreKey(
    val section: String,
    val key: String,
    val type: String,
    val value: String,
    /** Dotted paths of the [EmulatorSettings] fields this key is computed from. */
    val fields: Set<String>,
) {
    val id: String get() = "$section/$key"
}

/**
 * Maps [EmulatorSettings] to the core's settings keys. The single place that knows key
 * names; the field-to-key mapping is ARMSX2's (`Settings.applyTo` / `writeGsToNative`).
 */
internal object CoreKeys {
    fun of(s: EmulatorSettings): List<CoreKey> = KeyWriter().apply { write(s) }.keys

    /**
     * Keys for the features v1 leaves out, written off on every apply so a value left in the
     * core's own INI (or a first-run default, such as achievements) cannot switch them on.
     */
    val pinned: List<CoreKey> = KeyWriter().apply {
        put("Achievements", "Enabled", "bool", "false")
        put("EmuCore", "EnableCheats", "bool", "false")
        put("EmuCore/GS", "LoadTextureReplacements", "bool", "false")
        put("EmuCore/GS", "DumpReplaceableTextures", "bool", "false")
        put("EmuCore/GS", "OsdShowTextureReplacements", "bool", "false")
        put("EmuCore/GS", "TextureUpscale", "int", "0")
        put("EmuCore/GS", "ShaderChainEnabled", "bool", "false")
        put("EmuCore/GS", "LsfgEnabled", "bool", "false")
        put("DEV9/Eth", "EthEnable", "bool", "false")
        put("DEV9/Hdd", "HddEnable", "bool", "false")
        put("USB1", "Type", "string", "None")
        put("USB2", "Type", "string", "None")
        // Legacy ARM64 backend selectors; the core only has the one backend now.
        for (unit in listOf("EE", "IOP", "VU0", "VU1")) put("EmuCore/CPU/Recompiler", "UseMac$unit", "bool", "true")
    }.keys

    /** GSRendererType values. */
    fun rendererId(renderer: String): Int = when (renderer) {
        "opengl" -> 12
        "software" -> 13
        "vulkan" -> 14
        else -> -1
    }

    private val aspectRatioNames = listOf("Stretch", "Auto 4:3/3:2", "4:3", "16:9", "10:7", "21:9", "20:9", "19.5:9", "Custom")

    private class KeyWriter {
        val keys = ArrayList<CoreKey>()

        fun put(section: String, key: String, type: String, value: String, fields: Set<String> = emptySet()) {
            keys += CoreKey(section, key, type, value, fields)
        }

        fun group(name: String) = Group(name)

        inner class Group(private val name: String) {
            fun path(property: KProperty0<*>) = "$name.${property.name}"

            fun bool(property: KProperty0<Boolean>, section: String, key: String) =
                put(section, key, "bool", property.get().toString(), setOf(path(property)))

            fun int(property: KProperty0<Int>, section: String, key: String, range: IntRange? = null) {
                val value = property.get().let { if (range != null) it.coerceIn(range) else it }
                put(section, key, "int", value.toString(), setOf(path(property)))
            }

            fun float(property: KProperty0<Float>, section: String, key: String, range: ClosedFloatingPointRange<Float>? = null) {
                val value = property.get().let { if (range != null) it.coerceIn(range) else it }
                put(section, key, "float", value.toString(), setOf(path(property)))
            }

            fun string(property: KProperty0<String>, section: String, key: String) =
                put(section, key, "string", property.get(), setOf(path(property)))
        }

        fun write(s: EmulatorSettings) {
            writeCpu(s)
            writeFrameLimit(s.frameLimit)
            writeRewind(s.rewind)
            writeAudio(s.audio)
            writeEmuCore(s.emuCore)
            writeSystem(s.system)
            writeOutput(s.output)
            writeGraphics(s.graphics)
            writeOsd(s.osd)
            writeDisplay(s.display)
            writeHwFixes(s)
        }

        private fun writeCpu(s: EmulatorSettings) = with(s.cpu) {
            val g = group(EmulatorSettings::cpu.name)
            g.int(::eeCycleRate, "EmuCore/Speedhacks", "EECycleRate", -3..3)
            g.int(::eeCycleSkip, "EmuCore/Speedhacks", "EECycleSkip", 0..3)
            g.bool(::mtvu, "EmuCore/Speedhacks", "vuThread")
            g.bool(::vu1Instant, "EmuCore/Speedhacks", "vu1Instant")
            g.bool(::vuFlagHack, "EmuCore/Speedhacks", "vuFlagHack")
            g.bool(::fastCDVD, "EmuCore/Speedhacks", "fastCDVD")
            g.bool(::intcStat, "EmuCore/Speedhacks", "IntcStat")
            g.bool(::waitLoop, "EmuCore/Speedhacks", "WaitLoop")
            g.bool(::vuNeonFusions, "EmuCore/Speedhacks", "vuNeonFusions")
            g.bool(::vuDeferredWrites, "EmuCore/Speedhacks", "vuDeferredWrites")
            g.bool(::vuSkipStallSim, "EmuCore/Speedhacks", "vuSkipStallSim")

            // Clamp modes unpack into cumulative flags. The core resets an inconsistent set to
            // its defaults on load, so each ladder is always written whole.
            val ee = setOf(g.path(::eeClampMode))
            put("EmuCore/CPU/Recompiler", "fpuOverflow", "bool", (eeClampMode >= 1).toString(), ee)
            put("EmuCore/CPU/Recompiler", "fpuExtraOverflow", "bool", (eeClampMode >= 2).toString(), ee)
            put("EmuCore/CPU/Recompiler", "fpuFullMode", "bool", (eeClampMode >= 3).toString(), ee)
            put("EmuCore/CPU/Recompiler", "fpuExactMode", "bool", (eeClampMode >= 4).toString(), ee)
            val vu0 = setOf(g.path(::vuClampMode))
            val vu1 = setOf(g.path(::vuClampMode), g.path(::vu1ClampMode))
            for ((vu, mode, fields) in listOf(Triple("vu0", vuClampMode, vu0), Triple("vu1", s.effectiveVu1ClampMode, vu1))) {
                put("EmuCore/CPU/Recompiler", "${vu}Overflow", "bool", (mode >= 1).toString(), fields)
                put("EmuCore/CPU/Recompiler", "${vu}ExtraOverflow", "bool", (mode >= 2).toString(), fields)
                put("EmuCore/CPU/Recompiler", "${vu}SignOverflow", "bool", (mode >= 3).toString(), fields)
                put("EmuCore/CPU/Recompiler", "${vu}ExactMode", "bool", (mode >= 4).toString(), fields)
            }

            g.bool(::recEE, "EmuCore/CPU/Recompiler", "EnableEE")
            g.bool(::recIOP, "EmuCore/CPU/Recompiler", "EnableIOP")
            g.bool(::recVU0, "EmuCore/CPU/Recompiler", "EnableVU0")
            g.bool(::recVU1, "EmuCore/CPU/Recompiler", "EnableVU1")
            g.bool(::enableFastmem, "EmuCore/CPU/Recompiler", "EnableFastmem")
            g.bool(::vu1InlineFmacStall, "EmuCore/CPU/Recompiler", "Vu1InlineFmacStall")
            g.bool(::vu1CrossBlockPState, "EmuCore/CPU/Recompiler", "Vu1CrossBlockPState")
            g.bool(::vu1InlineDrainTestPipes, "EmuCore/CPU/Recompiler", "Vu1InlineDrainTestPipes")
            g.bool(::vu1FmacInstanceRouting, "EmuCore/CPU/Recompiler", "Vu1FmacInstanceRouting")
        }

        private fun writeFrameLimit(f: FrameLimitSettings) = with(f) {
            val g = group(EmulatorSettings::frameLimit.name)
            g.bool(::frameLimitEnable, "EmuCore/GS", "FrameLimitEnable")
            put("Framerate", "NominalScalar", "float", (nominalSpeedPercent.coerceIn(10, 1000) / 100f).toString(),
                setOf(g.path(::nominalSpeedPercent)))
        }

        private fun writeRewind(r: RewindSettings) = with(r) {
            val g = group(EmulatorSettings::rewind.name)
            put("EmuCore", "AutosaveIntervalMinutes", "int", (if (enabled) intervalMinutes.coerceIn(1, 10) else 0).toString(),
                setOf(g.path(::enabled), g.path(::intervalMinutes)))
            g.int(::keep, "EmuCore", "AutosaveKeep", 1..5)
        }

        private fun writeAudio(a: AudioSettings) = with(a) {
            val g = group(EmulatorSettings::audio.name)
            g.int(::audioVolume, "SPU2/Output", "StandardVolume", 0..200)
            g.bool(::audioMuted, "SPU2/Output", "OutputMuted")
            g.bool(::audioSwapChannels, "SPU2/Output", "SwapChannels")
            put("SPU2/Output", "SyncMode", "string", if (audioTimeStretch) "TimeStretch" else "Disabled",
                setOf(g.path(::audioTimeStretch)))
            g.int(::audioBufferMs, "SPU2/Output", "BufferMS", 10..200)
            g.int(::audioOutputLatencyMs, "SPU2/Output", "OutputLatencyMS", 5..200)
            g.int(::audioFastForwardVolume, "SPU2/Output", "FastForwardVolume", 0..200)
            g.bool(::spu2NeonReverb, "SPU2", "NeonReverbSIMD")
            g.bool(::audioOpenSLES, "SPU2/Output", "AndroidOpenSLES")
            g.bool(::spu2LightweightMix, "SPU2/Output", "LightweightMode")
        }

        private fun writeEmuCore(e: EmuCoreSettings) = with(e) {
            val g = group(EmulatorSettings::emuCore.name)
            g.bool(::enablePatches, "EmuCore", "EnablePatches")
            g.bool(::enableWideScreenPatches, "EmuCore", "EnableWideScreenPatches")
            g.bool(::enableNoInterlacingPatches, "EmuCore", "EnableNoInterlacingPatches")
            g.bool(::enableFastBoot, "EmuCore", "EnableFastBoot")
            g.bool(::hostFs, "EmuCore", "HostFs")
            g.bool(::pineEnabled, "EmuCore", "EnablePINE")
            g.int(::pineSlot, "EmuCore", "PINESlot")
            g.bool(::enableGameFixes, "EmuCore", "EnableGameFixes")
            g.bool(::gamefixSoftwareRendererFmv, "EmuCore/Gamefixes", "SoftwareRendererFMVHack")
            g.bool(::gamefixSkipMpeg, "EmuCore/Gamefixes", "SkipMPEGHack")
            g.bool(::gamefixEETiming, "EmuCore/Gamefixes", "EETimingHack")
            g.bool(::gamefixInstantDma, "EmuCore/Gamefixes", "InstantDMAHack")
            g.bool(::gamefixBlitInternalFps, "EmuCore/Gamefixes", "BlitInternalFPSHack")
            g.bool(::gamefixOphFlag, "EmuCore/Gamefixes", "OPHFlagHack")
            g.bool(::gamefixGifFifo, "EmuCore/Gamefixes", "GIFFIFOHack")
            g.bool(::gamefixDmaBusy, "EmuCore/Gamefixes", "DMABusyHack")
            g.bool(::gamefixVif1Stall, "EmuCore/Gamefixes", "VIF1StallHack")
            g.bool(::gamefixIbit, "EmuCore/Gamefixes", "IbitHack")
            g.bool(::gamefixFullVu0Sync, "EmuCore/Gamefixes", "FullVU0SyncHack")
            g.bool(::gamefixVuAddSub, "EmuCore/Gamefixes", "VuAddSubHack")
            g.bool(::gamefixVuOverflow, "EmuCore/Gamefixes", "VUOverflowHack")
            g.bool(::gamefixXgkick, "EmuCore/Gamefixes", "XgKickHack")
            g.bool(::gamefixGoemonTlb, "EmuCore/Gamefixes", "GoemonTlbHack")
            g.bool(::gamefixVuSync, "EmuCore/Gamefixes", "VUSyncHack")
            g.bool(::skipDuplicateFrames, "EmuCore/GS", "SkipDuplicateFrames")
            g.int(::eeFpuRoundMode, "EmuCore/CPU", "FPU.Roundmode", 0..3)
            g.int(::vu0RoundMode, "EmuCore/CPU", "VU0.Roundmode", 0..3)
            g.int(::vu1RoundMode, "EmuCore/CPU", "VU1.Roundmode", 0..3)
        }

        private fun writeSystem(s: SystemSettings) = with(s) {
            val g = group(EmulatorSettings::system.name)
            g.bool(::memoryCardSlot1Enabled, "MemoryCards", "Slot1_Enable")
            put("MemoryCards", "Slot1_Filename", "string", memoryCardSlot1Filename.ifEmpty { "mcd001.ps2" },
                setOf(g.path(::memoryCardSlot1Filename)))
            g.bool(::memoryCardSlot2Enabled, "MemoryCards", "Slot2_Enable")
            put("MemoryCards", "Slot2_Filename", "string", memoryCardSlot2Filename.ifEmpty { "mcd002.ps2" },
                setOf(g.path(::memoryCardSlot2Filename)))
            // biosFilename is resolved by the app and written with the global BIOS choice.
        }

        private fun writeOutput(o: OutputSettings) = with(o) {
            val g = group(EmulatorSettings::output.name)
            put("EmuCore/GS", "Renderer", "int", rendererId(renderer).toString(), setOf(g.path(::renderer)))
            g.float(::upscaleFloat, "EmuCore/GS", "upscale_multiplier", 0.25f..5f)
            put("EmuCore/GS", "AspectRatio", "string", aspectRatioNames[aspectRatio.coerceIn(aspectRatioNames.indices)],
                setOf(g.path(::aspectRatio)))
            val fmv = fmvAspectRatio.coerceIn(aspectRatioNames.indices)
            put("EmuCore/GS", "FMVAspectRatioSwitch", "string", if (fmv == 0) "Off" else aspectRatioNames[fmv],
                setOf(g.path(::fmvAspectRatio)))
            g.float(::customAspectRatio, "EmuCore/GS", "CustomAspectRatio", 0.5f..5f)
            g.int(::deinterlaceMode, "EmuCore/GS", "deinterlace_mode", 0..9)
            g.float(::framerateNtsc, "EmuCore/GS", "FramerateNTSC")
            g.float(::frameratePal, "EmuCore/GS", "FrameratePAL")
            g.bool(::autoFlushSw, "EmuCore/GS", "autoflush_sw")
            g.bool(::mipmapSw, "EmuCore/GS", "mipmap")
            g.int(::swThreads, "EmuCore/GS", "extrathreads", 0..10)
            g.int(::swThreadsHeight, "EmuCore/GS", "extrathreads_height", 0..8)
        }

        private fun writeGraphics(gr: GraphicsSettings) = with(gr) {
            val g = group(EmulatorSettings::graphics.name)
            g.bool(::hwMipmap, "EmuCore/GS", "hw_mipmap")
            g.int(::accurateBlendingUnit, "EmuCore/GS", "accurate_blending_unit", 0..5)
            g.int(::textureFiltering, "EmuCore/GS", "filter", 0..3)
            g.int(::displayBilinear, "EmuCore/GS", "linear_present_mode", 0..2)
            g.int(::texturePreloading, "EmuCore/GS", "texture_preloading", 0..2)
            g.int(::hardwareDownloadMode, "EmuCore/GS", "HWDownloadMode", 0..5)
            g.int(::tvShader, "EmuCore/GS", "TVShader", 0..7)
            g.bool(::shadeBoost, "EmuCore/GS", "ShadeBoost")
            g.int(::shadeBoostBrightness, "EmuCore/GS", "ShadeBoost_Brightness", 1..100)
            g.int(::shadeBoostContrast, "EmuCore/GS", "ShadeBoost_Contrast", 1..100)
            g.int(::shadeBoostSaturation, "EmuCore/GS", "ShadeBoost_Saturation", 1..100)
            g.int(::shadeBoostGamma, "EmuCore/GS", "ShadeBoost_Gamma", 1..100)
            g.bool(::fxaa, "EmuCore/GS", "fxaa")
            g.int(::casMode, "EmuCore/GS", "CASMode", 0..2)
            g.int(::casSharpness, "EmuCore/GS", "CASSharpness", 0..100)
            g.int(::upscaler, "EmuCore/GS", "Upscaler", 0..4)
            g.int(::fsrSharpness, "EmuCore/GS", "FSRSharpness", 0..100)
            g.int(::sgsrSharpness, "EmuCore/GS", "SGSRSharpness", 0..200)
        }

        private fun writeOsd(o: OsdSettings) = with(o) {
            val g = group(EmulatorSettings::osd.name)
            g.bool(::osdShowFps, "EmuCore/GS", "OsdShowFPS")
            g.int(::osdScale, "EmuCore/GS", "OsdScale", 25..500)
            put("EmuCore/GS", "OsdColor", "int", (osdColor and 0xFFFFFF).toString(), setOf(g.path(::osdColor)))
            g.int(::osdPosition, "EmuCore/GS", "OsdPerformancePos", 0..9)
            g.bool(::osdShowVps, "EmuCore/GS", "OsdShowVPS")
            g.bool(::osdShowSpeed, "EmuCore/GS", "OsdShowSpeed")
            g.bool(::osdShowCpu, "EmuCore/GS", "OsdShowCPU")
            g.bool(::osdShowGpu, "EmuCore/GS", "OsdShowGPU")
            g.bool(::osdShowResolution, "EmuCore/GS", "OsdShowResolution")
            g.bool(::osdShowGsStats, "EmuCore/GS", "OsdShowGSStats")
            g.bool(::osdShowFrameTimes, "EmuCore/GS", "OsdShowFrameTimes")
            g.bool(::osdShowHardwareInfo, "EmuCore/GS", "OsdShowHardwareInfo")
            // 1 = top left, 0 = hidden.
            put("EmuCore/GS", "OsdMessagesPos", "int", if (osdShowMessages) "1" else "0", setOf(g.path(::osdShowMessages)))
            g.bool(::osdShowGpuStats, "EmuCore/GS", "OsdShowGPUStats")
            g.bool(::osdShowVersion, "EmuCore/GS", "OsdShowVersion")
            g.bool(::osdShowSettings, "EmuCore/GS", "OsdShowSettings")
            g.bool(::osdShowInputs, "EmuCore/GS", "OsdShowInputs")
        }

        private fun writeDisplay(d: DisplaySettings) = with(d) {
            val g = group(EmulatorSettings::display.name)
            g.bool(::vsyncEnable, "EmuCore/GS", "VsyncEnable")
            g.bool(::screenOffsets, "EmuCore/GS", "pcrtc_offsets")
            g.bool(::showOverscan, "EmuCore/GS", "pcrtc_overscan")
            g.bool(::antiBlur, "EmuCore/GS", "pcrtc_antiblur")
            g.bool(::disableInterlaceOffset, "EmuCore/GS", "disable_interlace_offset")
            g.bool(::syncToHostRefresh, "EmuCore/GS", "SyncToHostRefreshRate")
            g.bool(::disableFramebufferFetch, "EmuCore/GS", "DisableFramebufferFetch")
            g.bool(::hwRov, "EmuCore/GS", "HWROV")
            g.bool(::hwAa1, "EmuCore/GS", "HWAA1")
            g.bool(::coalesceRenderPasses, "EmuCore/GS", "CoalesceRenderPasses")
            g.bool(::forceMaliFbFetch, "EmuCore/GS", "ForceMaliFramebufferFetch")
            g.bool(::useAngleOpenGL, "EmuCore/GS", "AndroidUseAngleOpenGL")
            g.int(::overrideTextureBarriers, "EmuCore/GS", "OverrideTextureBarriers", -1..1)
            put("EmuCore/GS", "GSBackThreadMode", "int", if (gsBackThreadMode != 0) "1" else "0", setOf(g.path(::gsBackThreadMode)))
            g.bool(::disableVertexShaderExpand, "EmuCore/GS", "DisableVertexShaderExpand")
            g.bool(::useBlitSwapChain, "EmuCore/GS", "UseBlitSwapChain")
            g.bool(::disableShaderCache, "EmuCore/GS", "DisableShaderCache")
            g.bool(::hwAccurateAlphaTest, "EmuCore/GS", "HWAccurateAlphaTest")
        }

        private fun writeHwFixes(s: EmulatorSettings) = with(s.hwFixes) {
            val g = group(EmulatorSettings::hwFixes.name)
            g.bool(::drawBuffering, "EmuCore/GS", "UserHacks_DrawBuffering")
            g.bool(::spinGpuReadbacks, "EmuCore/GS", "HWSpinGPUForReadbacks")
            g.bool(::spinCpuReadbacks, "EmuCore/GS", "HWSpinCPUForReadbacks")
            g.bool(::integerScaling, "EmuCore/GS", "IntegerScaling")

            // Display zoom trims every edge by the same fraction of a nominal 640x448 frame,
            // leaving 1/zoom of the picture, and replaces the manual crops while it is above 100.
            val zoom = displayZoom.coerceIn(100, 150)
            val cropFields = setOf(g.path(::displayZoom))
            fun crop(key: String, manual: KProperty0<Int>, frame: Int) {
                val value = if (zoom > 100) (frame * (1.0 - 100.0 / zoom) / 2.0).toInt() else manual.get()
                put("EmuCore/GS", key, "int", value.coerceIn(0, 640).toString(), cropFields + g.path(manual))
            }
            crop("CropLeft", ::cropLeft, 640)
            crop("CropTop", ::cropTop, 448)
            crop("CropRight", ::cropRight, 640)
            crop("CropBottom", ::cropBottom, 448)

            g.int(::dithering, "EmuCore/GS", "dithering_ps2", 0..3)
            g.int(::vsyncQueueSize, "EmuCore/GS", "VsyncQueueSize", 0..3)
            put("EmuCore/GS", "UserHacks_SkipDraw_Start", "int", skipDrawStart.coerceAtLeast(0).toString(), setOf(g.path(::skipDrawStart)))
            put("EmuCore/GS", "UserHacks_SkipDraw_End", "int", skipDrawEnd.coerceAtLeast(0).toString(), setOf(g.path(::skipDrawEnd)))

            // The core ignores every UserHacks_* key while the master switch is off, so it turns
            // on by itself once any hack is set.
            put("EmuCore/GS", "UserHacks", "bool", anyUserHackEnabled(this).toString(), userHackFields)

            g.int(::autoFlush, "EmuCore/GS", "UserHacks_AutoFlushLevel", 0..2)
            g.int(::halfPixelOffset, "EmuCore/GS", "UserHacks_HalfPixelOffset", 0..5)
            g.int(::limit24BitDepth, "EmuCore/GS", "UserHacks_Limit24BitDepth", 0..2)
            g.int(::textureInsideRt, "EmuCore/GS", "UserHacks_TextureInsideRt", 0..2)
            g.int(::nativeScaling, "EmuCore/GS", "UserHacks_native_scaling", 0..4)
            g.int(::roundSprite, "EmuCore/GS", "UserHacks_round_sprite_offset", 0..2)
            g.int(::bilinearUpscale, "EmuCore/GS", "UserHacks_BilinearHack", 0..3)
            g.int(::gpuTargetClut, "EmuCore/GS", "UserHacks_GPUTargetCLUTMode", 0..2)
            g.int(::cpuSpriteRenderBw, "EmuCore/GS", "UserHacks_CPUSpriteRenderBW", 0..3)
            g.int(::cpuSpriteRenderLevel, "EmuCore/GS", "UserHacks_CPUSpriteRenderLevel", 0..5)
            g.int(::cpuClutRender, "EmuCore/GS", "UserHacks_CPUCLUTRender", 0..2)
            g.bool(::alignSprite, "EmuCore/GS", "UserHacks_align_sprite_X")
            g.bool(::mergeSprite, "EmuCore/GS", "UserHacks_merge_pp_sprite")
            g.bool(::forceEvenSpritePosition, "EmuCore/GS", "UserHacks_ForceEvenSpritePosition")
            g.bool(::unscaledPaletteDraw, "EmuCore/GS", "UserHacks_NativePaletteDraw")
            g.int(::textureOffsetX, "EmuCore/GS", "UserHacks_TCOffsetX", 0..10000)
            g.int(::textureOffsetY, "EmuCore/GS", "UserHacks_TCOffsetY", 0..10000)
            g.bool(::gpuPaletteConversion, "EmuCore/GS", "paltex")
            g.bool(::cpuFramebufferConversion, "EmuCore/GS", "UserHacks_CPU_FB_Conversion")
            g.bool(::readTargetsWhenClosing, "EmuCore/GS", "UserHacks_ReadTCOnClose")
            g.bool(::disableDepthEmulation, "EmuCore/GS", "UserHacks_DisableDepthSupport")
            g.bool(::disablePartialInvalidation, "EmuCore/GS", "UserHacks_DisablePartialInvalidation")
            g.bool(::disableSafeFeatures, "EmuCore/GS", "UserHacks_Disable_Safe_Features")
            g.bool(::disableRenderFixes, "EmuCore/GS", "UserHacks_DisableRenderFixes")
            g.bool(::preloadFrameData, "EmuCore/GS", "preload_frame_with_gs_data")
            g.bool(::estimateTextureRegion, "EmuCore/GS", "UserHacks_EstimateTextureRegion")
            g.int(::triFilter, "EmuCore/GS", "TriFilter", -1..2)
            g.int(::maxAnisotropy, "EmuCore/GS", "MaxAnisotropy", 0..16)
            val profile = listOf("auto", "mali", "adreno", "powervr", "xclipse")
            put("EmuCore/GS", "AndroidGpuProfileOverride", "string", profile[gpuProfile.coerceIn(profile.indices)],
                setOf(g.path(::gpuProfile)))
        }
    }

    private fun anyUserHackEnabled(h: HwFixesSettings): Boolean = with(h) {
        manualUserHacks ||
            autoFlush != 0 || halfPixelOffset != 0 || limit24BitDepth != 0 ||
            textureInsideRt != 0 || nativeScaling != 0 || roundSprite != 0 ||
            bilinearUpscale != 0 || gpuTargetClut != 0 || cpuSpriteRenderBw != 0 ||
            cpuSpriteRenderLevel != 0 || cpuClutRender != 0 ||
            textureOffsetX != 0 || textureOffsetY != 0 ||
            alignSprite || mergeSprite || forceEvenSpritePosition || unscaledPaletteDraw ||
            gpuPaletteConversion || cpuFramebufferConversion || readTargetsWhenClosing ||
            disableDepthEmulation || disablePartialInvalidation || disableSafeFeatures ||
            disableRenderFixes || preloadFrameData || estimateTextureRegion || drawBuffering ||
            skipDrawStart != 0 || skipDrawEnd != 0
    }

    /** Every hwFixes field [anyUserHackEnabled] reads, which is all of them except the display ones. */
    private val userHackFields: Set<String> = setOf(
        "manualUserHacks", "autoFlush", "halfPixelOffset", "limit24BitDepth", "textureInsideRt",
        "nativeScaling", "roundSprite", "bilinearUpscale", "gpuTargetClut", "cpuSpriteRenderBw",
        "cpuSpriteRenderLevel", "cpuClutRender", "textureOffsetX", "textureOffsetY", "alignSprite",
        "mergeSprite", "forceEvenSpritePosition", "unscaledPaletteDraw", "gpuPaletteConversion",
        "cpuFramebufferConversion", "readTargetsWhenClosing", "disableDepthEmulation",
        "disablePartialInvalidation", "disableSafeFeatures", "disableRenderFixes", "preloadFrameData",
        "estimateTextureRegion", "drawBuffering", "skipDrawStart", "skipDrawEnd",
    ).mapTo(HashSet()) { "${EmulatorSettings::hwFixes.name}.$it" }
}
