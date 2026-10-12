package com.kira3.scphx.emucore.settings

import kotlinx.serialization.Serializable

/**
 * Every emulator setting the app stores, global or per game. Adapted from ARMSX2's
 * `com.armsx2.config.Settings`: same fields, groups and defaults, minus the features v1
 * leaves out (achievements, cheats, texture packs, librashader, LSFG, DEV9 network/HDD,
 * USB devices). Those are pinned off in [CoreKeys] instead.
 *
 * Settings v1 does not show in its UI are still fields here, so their defaults are written
 * to the core explicitly and a later UI can expose them without a format change.
 *
 * Stored as JSON (see [SettingsJson]), nested by group: `{"cpu": {"eeCycleRate": 0}, ...}`.
 * Choice values are the core's enum ordinals and are persisted, so new options are appended,
 * never inserted. Each field's comment names the core key it maps to.
 */
@Serializable
data class EmulatorSettings(
    val cpu: CpuSettings = CpuSettings(),
    val frameLimit: FrameLimitSettings = FrameLimitSettings(),
    val audio: AudioSettings = AudioSettings(),
    val emuCore: EmuCoreSettings = EmuCoreSettings(),
    val display: DisplaySettings = DisplaySettings(),
    val hwFixes: HwFixesSettings = HwFixesSettings(),
    val output: OutputSettings = OutputSettings(),
    val system: SystemSettings = SystemSettings(),
    val graphics: GraphicsSettings = GraphicsSettings(),
    val osd: OsdSettings = OsdSettings(),
    val rewind: RewindSettings = RewindSettings(),
) {
    val effectiveVu1ClampMode: Int get() = if (cpu.vu1ClampMode < 0) cpu.vuClampMode else cpu.vu1ClampMode
}

/** EE/VU clamping, speed hacks and recompiler switches (EmuCore/Speedhacks, EmuCore/CPU). */
@Serializable
data class CpuSettings(
    /** EmuCore/Speedhacks/EECycleRate: -3..3 (50%..300%). */
    val eeCycleRate: Int = 0,
    /** EmuCore/Speedhacks/EECycleSkip: 0..3. */
    val eeCycleSkip: Int = 0,
    /** EE/FPU clamping: 0 None, 1 Normal, 2 Extra, 3 Full, 4 Exact. Unpacked to fpu* keys. */
    val eeClampMode: Int = 1,
    /** VU clamping: 0 None, 1 Normal, 2 Extra, 3 Extra+Sign, 4 Exact. Unpacked to vu0/vu1 keys. */
    val vuClampMode: Int = 1,
    /** VU1 only: -1 follows [vuClampMode], else 0..4 as above. */
    val vu1ClampMode: Int = -1,
    /** EmuCore/Speedhacks/vuThread (MTVU). */
    val mtvu: Boolean = true,
    /** EmuCore/Speedhacks/vu1Instant. */
    val vu1Instant: Boolean = true,
    /** EmuCore/Speedhacks/vuFlagHack. */
    val vuFlagHack: Boolean = true,
    /** EmuCore/Speedhacks/fastCDVD. */
    val fastCDVD: Boolean = false,
    /** EmuCore/Speedhacks/IntcStat. */
    val intcStat: Boolean = true,
    /** EmuCore/Speedhacks/WaitLoop. */
    val waitLoop: Boolean = true,
    /** EmuCore/Speedhacks/vuNeonFusions (ARMSX2's arm64 VU1 JIT fusions). */
    val vuNeonFusions: Boolean = true,
    /** EmuCore/Speedhacks/vuDeferredWrites. Experimental; breaks some games. */
    val vuDeferredWrites: Boolean = false,
    /** EmuCore/Speedhacks/vuSkipStallSim. Aggressive; breaks timing-sensitive games. */
    val vuSkipStallSim: Boolean = false,

    /** EmuCore/CPU/Recompiler/EnableEE. */
    val recEE: Boolean = true,
    /** EmuCore/CPU/Recompiler/EnableIOP. */
    val recIOP: Boolean = true,
    /** EmuCore/CPU/Recompiler/EnableVU0. */
    val recVU0: Boolean = true,
    /** EmuCore/CPU/Recompiler/EnableVU1. */
    val recVU1: Boolean = true,
    /** EmuCore/CPU/Recompiler/EnableFastmem. */
    val enableFastmem: Boolean = true,

    // Experimental VU1 JIT stall folding (EmuCore/CPU/Recompiler/Vu1*). ARMSX2 has no UI for them.
    val vu1InlineFmacStall: Boolean = false,
    val vu1CrossBlockPState: Boolean = false,
    val vu1InlineDrainTestPipes: Boolean = false,
    val vu1FmacInstanceRouting: Boolean = false,
)

/** Rewind: the core's autosaves, kept as a short history the player can go back to. */
@Serializable
data class RewindSettings(
    /** Off by default: each autosave stalls the game for a moment. EmuCore/AutosaveIntervalMinutes is 0 when off. */
    val enabled: Boolean = false,
    /** EmuCore/AutosaveIntervalMinutes: minutes of play between saves (1..10). */
    val intervalMinutes: Int = 1,
    /** EmuCore/AutosaveKeep: how many saves to keep (1..5). */
    val keep: Int = 5,
)

/** Frame limiter and speed target. */
@Serializable
data class FrameLimitSettings(
    /** EmuCore/GS/FrameLimitEnable. Off runs unthrottled. */
    val frameLimitEnable: Boolean = true,
    /** Framerate/NominalScalar as a percent of full speed (10..1000). */
    val nominalSpeedPercent: Int = 100,
    /** Caps the presented frame rate without slowing the game. 0 = off. Runtime only. */
    val fpsLimit: Int = 0,
    /** Fast-forward speed as a multiple of full speed (2..10), or 0 for as fast as possible. App side. */
    val fastForwardSpeed: Int = 0,
    /** Show the fast-forward button over the game. App side. */
    val fastForwardButton: Boolean = true,
    /** Show a screenshot button over the game. Off by default. App side. */
    val screenshotButton: Boolean = false,
)

/** Audio output (SPU2). */
@Serializable
data class AudioSettings(
    /** SPU2/Output/StandardVolume: 0..200 percent. */
    val audioVolume: Int = 100,
    /** SPU2/Output/OutputMuted. */
    val audioMuted: Boolean = false,
    /** SPU2/Output/SwapChannels. */
    val audioSwapChannels: Boolean = false,
    /** SPU2/Output/SyncMode: TimeStretch when on, Disabled when off. */
    val audioTimeStretch: Boolean = true,
    /** SPU2/Output/BufferMS. */
    val audioBufferMs: Int = 50,
    /** SPU2/Output/OutputLatencyMS. */
    val audioOutputLatencyMs: Int = 20,
    /** SPU2/Output/FastForwardVolume. */
    val audioFastForwardVolume: Int = 100,
    /** SPU2/NeonReverbSIMD. Applies on the next boot. */
    val spu2NeonReverb: Boolean = false,
    /** SPU2/Output/AndroidOpenSLES: OpenSL ES instead of AAudio. */
    val audioOpenSLES: Boolean = false,
    /** SPU2/Output/LightweightMode: skips reverb. */
    val spu2LightweightMix: Boolean = false,
)

/** Patches, boot options and game fixes (EmuCore, EmuCore/Gamefixes). */
@Serializable
data class EmuCoreSettings(
    /** EmuCore/EnablePatches: the built-in compatibility patches. */
    val enablePatches: Boolean = true,
    /** EmuCore/EnableWideScreenPatches. */
    val enableWideScreenPatches: Boolean = false,
    /** EmuCore/EnableNoInterlacingPatches. */
    val enableNoInterlacingPatches: Boolean = false,
    /** EmuCore/EnableFastBoot: skips the BIOS intro. */
    val enableFastBoot: Boolean = true,
    /** EmuCore/HostFs: host: file system access, for homebrew and mods. */
    val hostFs: Boolean = false,
    /** EmuCore/EnablePINE: the IPC server debugging tools connect to (loopback only). */
    val pineEnabled: Boolean = false,
    /** EmuCore/PINESlot. */
    val pineSlot: Int = 28011,
    /** EmuCore/EnableGameFixes: lets the game database apply each game's fixes. */
    val enableGameFixes: Boolean = true,
    /** EmuCore/Gamefixes/SoftwareRendererFMVHack. */
    val gamefixSoftwareRendererFmv: Boolean = false,
    /** EmuCore/Gamefixes/SkipMPEGHack. */
    val gamefixSkipMpeg: Boolean = false,
    /** EmuCore/Gamefixes/EETimingHack. */
    val gamefixEETiming: Boolean = false,
    /** EmuCore/Gamefixes/InstantDMAHack. */
    val gamefixInstantDma: Boolean = false,
    /** EmuCore/Gamefixes/BlitInternalFPSHack. */
    val gamefixBlitInternalFps: Boolean = false,
    /** EmuCore/Gamefixes/OPHFlagHack. */
    val gamefixOphFlag: Boolean = false,
    /** EmuCore/Gamefixes/GIFFIFOHack. */
    val gamefixGifFifo: Boolean = false,
    /** EmuCore/Gamefixes/DMABusyHack. */
    val gamefixDmaBusy: Boolean = false,
    /** EmuCore/Gamefixes/VIF1StallHack. */
    val gamefixVif1Stall: Boolean = false,
    /** EmuCore/Gamefixes/IbitHack. */
    val gamefixIbit: Boolean = false,
    /** EmuCore/Gamefixes/FullVU0SyncHack. */
    val gamefixFullVu0Sync: Boolean = false,
    /** EmuCore/Gamefixes/VuAddSubHack. */
    val gamefixVuAddSub: Boolean = false,
    /** EmuCore/Gamefixes/VUOverflowHack. */
    val gamefixVuOverflow: Boolean = false,
    /** EmuCore/Gamefixes/XgKickHack. */
    val gamefixXgkick: Boolean = false,
    /** EmuCore/Gamefixes/GoemonTlbHack. Applies on the next boot. */
    val gamefixGoemonTlb: Boolean = false,
    /** EmuCore/Gamefixes/VUSyncHack. Applies on the next boot. */
    val gamefixVuSync: Boolean = false,
    /** EmuCore/GS/SkipDuplicateFrames. */
    val skipDuplicateFrames: Boolean = true,
    /** EmuCore/CPU/FPU.Roundmode: 0 Nearest, 1 Negative, 2 Positive, 3 Chop. */
    val eeFpuRoundMode: Int = 3,
    /** EmuCore/CPU/VU0.Roundmode: as above. */
    val vu0RoundMode: Int = 3,
    /** EmuCore/CPU/VU1.Roundmode: as above. */
    val vu1RoundMode: Int = 3,
)

/** Display, PCRTC and presentation options (EmuCore/GS). */
@Serializable
data class DisplaySettings(
    /** EmuCore/GS/pcrtc_offsets. */
    val screenOffsets: Boolean = false,
    /** EmuCore/GS/pcrtc_overscan. */
    val showOverscan: Boolean = false,
    /** EmuCore/GS/pcrtc_antiblur. */
    val antiBlur: Boolean = true,
    /** EmuCore/GS/disable_interlace_offset. */
    val disableInterlaceOffset: Boolean = false,
    /** EmuCore/GS/SyncToHostRefreshRate. */
    val syncToHostRefresh: Boolean = false,
    /** EmuCore/GS/DisableFramebufferFetch. Applies on the next boot. */
    val disableFramebufferFetch: Boolean = false,
    /** EmuCore/GS/HWROV: rasterizer order views (Vulkan). Off on mobile, unlike upstream. */
    val hwRov: Boolean = false,
    /** EmuCore/GS/HWAA1. Applies on the next boot. */
    val hwAa1: Boolean = false,
    /** EmuCore/GS/CoalesceRenderPasses. */
    val coalesceRenderPasses: Boolean = false,
    /** EmuCore/GS/ForceMaliFramebufferFetch. Applies on the next boot. */
    val forceMaliFbFetch: Boolean = false,
    /** EmuCore/GS/AndroidUseAngleOpenGL: OpenGL through ANGLE. Applies on the next boot. */
    val useAngleOpenGL: Boolean = false,
    /** EmuCore/GS/OverrideTextureBarriers: -1 Auto, 0 Off, 1 On. Applies on the next boot. */
    val overrideTextureBarriers: Int = -1,
    /** EmuCore/GS/GSBackThreadMode: 0 off, otherwise on. Applies on the next boot. */
    val gsBackThreadMode: Int = 0,
    /** EmuCore/GS/DisableVertexShaderExpand. Applies on the next boot. */
    val disableVertexShaderExpand: Boolean = false,
    /** EmuCore/GS/UseBlitSwapChain. Applies on the next boot. */
    val useBlitSwapChain: Boolean = false,
    /** EmuCore/GS/DisableShaderCache. Applies on the next boot. */
    val disableShaderCache: Boolean = false,
    /** EmuCore/GS/HWAccurateAlphaTest. */
    val hwAccurateAlphaTest: Boolean = false,
    /** EmuCore/GS/VsyncEnable. Applies on the next boot. */
    val vsyncEnable: Boolean = false,
)

/** Hardware, upscaling and software renderer fixes (EmuCore/GS UserHacks_*). */
@Serializable
data class HwFixesSettings(
    /** EmuCore/GS/UserHacks_SkipDraw_Start. 0 = off. */
    val skipDrawStart: Int = 0,
    /** EmuCore/GS/UserHacks_SkipDraw_End. 0 = off. */
    val skipDrawEnd: Int = 0,
    /** EmuCore/GS/HWSpinGPUForReadbacks. */
    val spinGpuReadbacks: Boolean = false,
    /** EmuCore/GS/HWSpinCPUForReadbacks. */
    val spinCpuReadbacks: Boolean = false,
    /** EmuCore/GS/IntegerScaling. */
    val integerScaling: Boolean = false,
    /** EmuCore/GS/Crop*: overscan crop in native pixels. Ignored while [displayZoom] > 100. */
    val cropLeft: Int = 0,
    val cropTop: Int = 0,
    val cropRight: Int = 0,
    val cropBottom: Int = 0,
    /** 100..150 percent. Written as an even crop on all four edges, so the aspect is kept. */
    val displayZoom: Int = 100,
    /** EmuCore/GS/dithering_ps2: 0 Off, 1 Scaled, 2 Unscaled, 3 Force 32bit. */
    val dithering: Int = 2,
    /** EmuCore/GS/VsyncQueueSize: 0..3. 0 is the low-latency mode. */
    val vsyncQueueSize: Int = 2,
    /** EmuCore/GS/UserHacks_AutoFlushLevel: 0 Off, 1 Sprites only, 2 On. */
    val autoFlush: Int = 0,
    /** EmuCore/GS/UserHacks_HalfPixelOffset: 0 Off, 1 Normal, 2 Special, 3 Special (aggressive), 4 Native, 5 Native with texture offset. */
    val halfPixelOffset: Int = 0,
    /** EmuCore/GS/UserHacks_Limit24BitDepth: 0 Off, 1 Upper, 2 Lower. */
    val limit24BitDepth: Int = 0,
    /** EmuCore/GS/UserHacks: master switch for the manual hardware fixes. */
    val manualUserHacks: Boolean = false,
    /** EmuCore/GS/UserHacks_TextureInsideRt. */
    val textureInsideRt: Int = 0,
    /** EmuCore/GS/UserHacks_native_scaling. */
    val nativeScaling: Int = 0,
    /** EmuCore/GS/UserHacks_round_sprite_offset. */
    val roundSprite: Int = 0,
    /** EmuCore/GS/UserHacks_BilinearHack. */
    val bilinearUpscale: Int = 0,
    /** EmuCore/GS/UserHacks_GPUTargetCLUTMode. */
    val gpuTargetClut: Int = 0,
    /** EmuCore/GS/UserHacks_CPUSpriteRenderBW. */
    val cpuSpriteRenderBw: Int = 0,
    /** EmuCore/GS/UserHacks_CPUSpriteRenderLevel. */
    val cpuSpriteRenderLevel: Int = 0,
    /** EmuCore/GS/UserHacks_align_sprite_X. */
    val alignSprite: Boolean = false,
    /** EmuCore/GS/UserHacks_merge_pp_sprite. */
    val mergeSprite: Boolean = false,
    /** EmuCore/GS/UserHacks_ForceEvenSpritePosition. */
    val forceEvenSpritePosition: Boolean = false,
    /** EmuCore/GS/UserHacks_NativePaletteDraw. */
    val unscaledPaletteDraw: Boolean = false,
    /** EmuCore/GS/UserHacks_TCOffsetX: 0..10000 (thousandths of a pixel). */
    val textureOffsetX: Int = 0,
    /** EmuCore/GS/UserHacks_TCOffsetY. */
    val textureOffsetY: Int = 0,
    /** EmuCore/GS/paltex. */
    val gpuPaletteConversion: Boolean = false,
    /** EmuCore/GS/UserHacks_CPU_FB_Conversion. */
    val cpuFramebufferConversion: Boolean = false,
    /** EmuCore/GS/UserHacks_ReadTCOnClose. */
    val readTargetsWhenClosing: Boolean = false,
    /** EmuCore/GS/UserHacks_DisableDepthSupport. */
    val disableDepthEmulation: Boolean = false,
    /** EmuCore/GS/UserHacks_DisablePartialInvalidation. */
    val disablePartialInvalidation: Boolean = false,
    /** EmuCore/GS/UserHacks_Disable_Safe_Features. */
    val disableSafeFeatures: Boolean = false,
    /** EmuCore/GS/UserHacks_DisableRenderFixes. */
    val disableRenderFixes: Boolean = false,
    /** EmuCore/GS/preload_frame_with_gs_data. */
    val preloadFrameData: Boolean = false,
    /** EmuCore/GS/UserHacks_EstimateTextureRegion. */
    val estimateTextureRegion: Boolean = false,
    /** EmuCore/GS/UserHacks_DrawBuffering. */
    val drawBuffering: Boolean = false,
    /** EmuCore/GS/UserHacks_CPUCLUTRender: 0 Off, 1 Normal, 2 Aggressive. */
    val cpuClutRender: Int = 0,
    /** EmuCore/GS/TriFilter: -1 Auto, 0 Off, 1 PS2, 2 Forced. */
    val triFilter: Int = -1,
    /** EmuCore/GS/MaxAnisotropy: 0 off, else 2/4/8/16. */
    val maxAnisotropy: Int = 0,
    /** EmuCore/GS/AndroidGpuProfileOverride: 0 Auto, 1 Mali, 2 Adreno, 3 PowerVR, 4 Xclipse. Applies on the next boot. */
    val gpuProfile: Int = 0,
)

/** Renderer, resolution, aspect and the app-side display layout. */
@Serializable
data class OutputSettings(
    /** Output surface size: 0 = screen, else n x 448 lines on the short side. App side. */
    val hwScaler: Int = 0,
    /** "auto", "2560x1440", "1920x1080" or "1280x720". App side. */
    val screenResOverride: String = "auto",
    /** EmuCore/GS/autoflush_sw. */
    val autoFlushSw: Boolean = true,
    /** EmuCore/GS/mipmap (software renderer). */
    val mipmapSw: Boolean = true,
    /** EmuCore/GS/extrathreads: 0..10. */
    val swThreads: Int = 4,
    /** EmuCore/GS/extrathreads_height: 0..8. Applies on the next boot. */
    val swThreadsHeight: Int = 4,
    /** EmuCore/GS/AspectRatio: 0 Stretch, 1 Auto 4:3/3:2, 2 4:3, 3 16:9, 4 10:7, 5 21:9, 6 20:9, 7 19.5:9, 8 Custom. */
    val aspectRatio: Int = 1,
    /** EmuCore/GS/FMVAspectRatioSwitch: 0 Off, then as [aspectRatio]. */
    val fmvAspectRatio: Int = 0,
    /** EmuCore/GS/CustomAspectRatio: width / height, used when [aspectRatio] is Custom. */
    val customAspectRatio: Float = 16f / 9f,
    /** EmuCore/GS/Renderer: "auto", "opengl", "vulkan" or "software". Applies on the next boot. */
    val renderer: String = "auto",
    /** EmuCore/GS/upscale_multiplier: 0.25..5.0, 1.0 = native. */
    val upscaleFloat: Float = 1.0f,
    /** Installed custom Vulkan driver (e.g. Turnip) to load, or "" for the system driver. Applies on the next boot. */
    val customDriverId: String = "",
    /** Turnip's UBWC flag hint (FD_DEV_FEATURES), for glitches on Adreno 740. App side. Applies on the next boot. */
    val turnipUbwcHint: Boolean = false,
    /** Play screen orientation: 0 device setting, 1 landscape, 2 portrait, 3 auto-rotate. App side. */
    val orientation: Int = 0,
    /** In portrait, align the picture to the top (true) instead of centering it. */
    val portraitRenderTop: Boolean = true,
    /** In landscape, align the picture to the top instead of centering it. */
    val landscapeRenderTop: Boolean = false,
    /** Hold Triangle+Cross during boot, the console combo some games check to offer 480p. App side. */
    val autoProgressiveScan: Boolean = false,
    /** Emulation thread placement: 0 off, 1-6 EE/VU/GS orders, 7 performance cores. Applies on the next boot. */
    val affinityMode: Int = 7,
    /** Report each frame's CPU work to Android (ADPF, Android 13+) so it clocks the emulation threads up. Runtime only. */
    val performanceHint: Boolean = false,
    /** EmuCore/GS/FramerateNTSC. */
    val framerateNtsc: Float = 59.94f,
    /** EmuCore/GS/FrameratePAL. */
    val frameratePal: Float = 50.00f,
    /** EmuCore/GS/deinterlace_mode: 0 Auto, 1 Off, 2/3 Weave, 4/5 Bob, 6/7 Blend, 8/9 Adaptive. */
    val deinterlaceMode: Int = 0,
)

/** Memory cards and the per-game BIOS. */
@Serializable
data class SystemSettings(
    /** MemoryCards/Slot1_Enable. */
    val memoryCardSlot1Enabled: Boolean = true,
    /** MemoryCards/Slot1_Filename. */
    val memoryCardSlot1Filename: String = "mcd001.ps2",
    /** MemoryCards/Slot2_Enable. */
    val memoryCardSlot2Enabled: Boolean = true,
    /** MemoryCards/Slot2_Filename. */
    val memoryCardSlot2Filename: String = "mcd002.ps2",
    /** BIOS file (in the BIOS folder) for this game, or "" for the one chosen in BIOS settings. */
    val biosFilename: String = "",
)

/** Renderer accuracy, filtering and post-processing (EmuCore/GS). */
@Serializable
data class GraphicsSettings(
    /** EmuCore/GS/hw_mipmap. */
    val hwMipmap: Boolean = true,
    /** EmuCore/GS/accurate_blending_unit: 0 Minimum, 1 Basic, 2 Medium, 3 High, 4 Full, 5 Maximum. */
    val accurateBlendingUnit: Int = 1,
    /** EmuCore/GS/filter: 0 Nearest, 1 Bilinear (forced), 2 Bilinear (PS2), 3 Bilinear (forced, excluding sprites). */
    val textureFiltering: Int = 2,
    /** EmuCore/GS/linear_present_mode: 0 Off, 1 Smooth, 2 Sharp. */
    val displayBilinear: Int = 1,
    /** EmuCore/GS/texture_preloading: 0 Off, 1 Partial, 2 Full. */
    val texturePreloading: Int = 2,
    /** EmuCore/GS/HWDownloadMode: 0 Accurate, 1 Force full, 2 No readbacks, 3 Unsync, 4 Disabled, 5 Asynchronous. */
    val hardwareDownloadMode: Int = 0,
    /** EmuCore/GS/TVShader: 0..7. */
    val tvShader: Int = 0,
    /** EmuCore/GS/ShadeBoost and its four levels (1..100). */
    val shadeBoost: Boolean = false,
    val shadeBoostBrightness: Int = 50,
    val shadeBoostContrast: Int = 50,
    val shadeBoostSaturation: Int = 50,
    val shadeBoostGamma: Int = 50,
    /** EmuCore/GS/fxaa. */
    val fxaa: Boolean = false,
    /** EmuCore/GS/CASMode: 0 Off, 1 Sharpen only, 2 Sharpen and resize. */
    val casMode: Int = 0,
    /** EmuCore/GS/CASSharpness: 0..100. */
    val casSharpness: Int = 50,
    /** EmuCore/GS/Upscaler: 0 Off, 2 FSR1, 3 SGSR, 4 SGSR edge-direction (1 is Apple-only MetalFX). */
    val upscaler: Int = 0,
    /** EmuCore/GS/FSRSharpness: 0..100. */
    val fsrSharpness: Int = 50,
    /** EmuCore/GS/SGSRSharpness: 0..200. */
    val sgsrSharpness: Int = 100,
)

/** The performance overlay (EmuCore/GS/Osd*). */
@Serializable
data class OsdSettings(
    val osdShowFps: Boolean = false,
    /** EmuCore/GS/OsdScale: 25..500 percent. */
    val osdScale: Int = 65,
    /** EmuCore/GS/OsdColor: 0xRRGGBB, 0 = default white. */
    val osdColor: Int = 0,
    /** EmuCore/GS/OsdPerformancePos: 1 top left, 3 top right, 7 bottom left, 9 bottom right. */
    val osdPosition: Int = 3,
    val osdShowVps: Boolean = false,
    val osdShowSpeed: Boolean = false,
    val osdShowCpu: Boolean = false,
    val osdShowGpu: Boolean = false,
    val osdShowResolution: Boolean = false,
    val osdShowGsStats: Boolean = false,
    val osdShowFrameTimes: Boolean = false,
    val osdShowHardwareInfo: Boolean = false,
    /** EmuCore/GS/OsdMessagesPos: transient notifications such as "state saved". */
    val osdShowMessages: Boolean = true,
    val osdShowGpuStats: Boolean = false,
    val osdShowVersion: Boolean = false,
    val osdShowSettings: Boolean = false,
    val osdShowInputs: Boolean = false,
)
