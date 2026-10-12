package com.kira3.scphx.ui.settings

import com.kira3.scphx.R
import com.kira3.scphx.ui.settings.Label.Companion.res
import com.kira3.scphx.ui.settings.Label.Companion.text

/**
 * What each settings category shows. Items follow ARMSX2's settings tabs (see
 * docs/ARMSX2_SETTINGS.md); option values are the stored values, so their order matches
 * the core's enums.
 */
object SettingsCatalog {
    private val byCategory: Map<SettingsCategory, List<SettingsSection>> by lazy {
        mapOf(
            SettingsCategory.Graphics to graphics(),
            SettingsCategory.Performance to performance(),
            SettingsCategory.Audio to audio(),
            SettingsCategory.Controls to controls(),
            SettingsCategory.System to system(),
            SettingsCategory.Advanced to advanced(),
        )
    }

    fun sections(category: SettingsCategory): List<SettingsSection> = byCategory.getValue(category)

    private fun options(vararg labels: Label): List<Option> = labels.mapIndexed { index, label -> Option(index, label) }

    private val off = res(R.string.settings_opt_off)
    private val on = res(R.string.settings_opt_on)
    private val auto = res(R.string.settings_opt_auto)
    private val normal = res(R.string.settings_opt_normal)
    private val aggressive = res(R.string.settings_opt_aggressive)

    private val aspectRatios = listOf("4:3", "16:9", "10:7", "21:9", "20:9", "19.5:9").map(::text)

    private val roundModes = options(
        res(R.string.settings_opt_nearest), res(R.string.settings_opt_negative),
        res(R.string.settings_opt_positive), res(R.string.settings_opt_chop),
    )

    private val vuClampModes = listOf(
        off, normal, res(R.string.settings_opt_extra), res(R.string.settings_opt_extra_sign), res(R.string.settings_opt_exact),
    )

    private fun graphics() = listOf(
        SettingsSection(
            R.string.settings_section_rendering,
            listOf(
                Choice(
                    "output.renderer", R.string.settings_renderer, R.string.settings_renderer_summary,
                    listOf(
                        Option("auto", auto), Option("opengl", text("OpenGL")),
                        Option("vulkan", text("Vulkan")), Option("software", res(R.string.settings_opt_software)),
                    ),
                    inline = true,
                ),
                GpuDriverSetting,
                Choice(
                    "output.upscaleFloat", R.string.settings_resolution, R.string.settings_resolution_summary,
                    listOf(
                        Option(0.5f, text("0.5×")), Option(0.75f, text("0.75×")),
                        Option(1.0f, res(R.string.settings_opt_native)),
                        Option(1.25f, text("1.25×")), Option(1.5f, text("1.5×")),
                        Option(2.0f, text("2× (~720p)")), Option(2.5f, text("2.5×")),
                        Option(3.0f, text("3× (~1080p)")), Option(4.0f, text("4× (~1440p)")),
                        Option(5.0f, text("5× (~1800p)")),
                    ),
                ),
                Choice(
                    "output.aspectRatio", R.string.settings_aspect_ratio,
                    options = options(
                        res(R.string.settings_opt_stretch), res(R.string.settings_opt_aspect_auto),
                        *aspectRatios.toTypedArray(), res(R.string.settings_opt_custom),
                    ),
                ),
                NumberInput(
                    "output.customAspectRatio", R.string.settings_custom_aspect_ratio, R.string.settings_custom_aspect_ratio_summary,
                    min = 0.5, max = 4.0, decimal = true,
                    visibleWhen = { it.int("output.aspectRatio") == 8 },
                ),
                Toggle("emuCore.enableWideScreenPatches", R.string.settings_widescreen_patches, R.string.settings_widescreen_patches_summary),
                Choice(
                    "output.orientation", R.string.settings_orientation, R.string.settings_orientation_summary,
                    options(
                        res(R.string.settings_opt_device), res(R.string.settings_opt_landscape),
                        res(R.string.settings_opt_portrait), res(R.string.settings_opt_auto_rotate),
                    ),
                    inline = true,
                ),
            ),
        ),
        SettingsSection(
            R.string.settings_section_filtering,
            listOf(
                Choice(
                    "graphics.textureFiltering", R.string.settings_texture_filtering,
                    options = options(
                        res(R.string.settings_opt_nearest), res(R.string.settings_opt_bilinear_forced),
                        res(R.string.settings_opt_bilinear_ps2), res(R.string.settings_opt_bilinear_no_sprites),
                    ),
                ),
                Choice(
                    "graphics.accurateBlendingUnit", R.string.settings_blending, R.string.settings_blending_summary,
                    options(
                        res(R.string.settings_opt_minimum), res(R.string.settings_opt_basic), res(R.string.settings_opt_medium),
                        res(R.string.settings_opt_high), res(R.string.settings_opt_full), res(R.string.settings_opt_maximum),
                    ),
                ),
                Choice(
                    "graphics.displayBilinear", R.string.settings_display_filter, R.string.settings_display_filter_summary,
                    options(off, res(R.string.settings_opt_smooth), res(R.string.settings_opt_sharp)),
                    inline = true,
                ),
            ),
        ),
        SettingsSection(
            R.string.settings_section_display,
            listOf(
                Choice(
                    "output.hwScaler", R.string.settings_display_resolution, R.string.settings_display_resolution_summary,
                    listOf(
                        Option(0, res(R.string.settings_opt_screen)), Option(3, text("3x PS2")),
                        Option(2, text("2x PS2")), Option(1, text("1x PS2")),
                    ),
                    inline = true,
                ),
                Choice(
                    "output.screenResOverride", R.string.settings_screen_res_override, R.string.settings_screen_res_override_summary,
                    listOf(
                        Option("auto", res(R.string.settings_opt_auto)), Option("2560x1440", text("1440p")),
                        Option("1920x1080", text("1080p")), Option("1280x720", text("720p")),
                    ),
                    inline = true,
                ),
                Choice(
                    "output.fmvAspectRatio", R.string.settings_fmv_aspect_ratio, R.string.settings_fmv_aspect_ratio_summary,
                    options(off, res(R.string.settings_opt_aspect_auto), *aspectRatios.toTypedArray(), res(R.string.settings_opt_custom)),
                ),
                Choice(
                    "output.deinterlaceMode", R.string.settings_deinterlacing,
                    options = options(
                        auto, off, text("Weave (top field first)"), text("Weave (bottom field first)"),
                        text("Bob (top field first)"), text("Bob (bottom field first)"),
                        text("Blend (top field first)"), text("Blend (bottom field first)"),
                        text("Adaptive (top field first)"), text("Adaptive (bottom field first)"),
                    ),
                ),
                Slider(
                    "hwFixes.displayZoom", R.string.settings_display_zoom, R.string.settings_display_zoom_summary,
                    range = 100..150, step = 5, unit = R.string.settings_unit_percent,
                ),
                Toggle("output.portraitRenderTop", R.string.settings_portrait_top),
                Toggle("output.landscapeRenderTop", R.string.settings_landscape_top),
            ),
            advanced = true,
        ),
        SettingsSection(
            R.string.settings_section_renderer_options,
            listOf(
                Choice(
                    "graphics.texturePreloading", R.string.settings_texture_preloading, R.string.settings_texture_preloading_summary,
                    options(off, res(R.string.settings_opt_partial), res(R.string.settings_opt_full)),
                    inline = true,
                ),
                Choice(
                    "graphics.hardwareDownloadMode", R.string.settings_download_mode, R.string.settings_download_mode_summary,
                    options(
                        res(R.string.settings_opt_accurate), res(R.string.settings_opt_force_full),
                        res(R.string.settings_opt_no_readbacks), res(R.string.settings_opt_unsynchronized),
                        res(R.string.settings_opt_disabled), res(R.string.settings_opt_asynchronous),
                    ),
                ),
                Toggle("graphics.hwMipmap", R.string.settings_mipmapping),
                Choice(
                    "hwFixes.triFilter", R.string.settings_trilinear,
                    options = listOf(
                        Option(-1, auto), Option(0, off), Option(1, text("PS2")), Option(2, res(R.string.settings_opt_forced)),
                    ),
                    inline = true,
                ),
                Choice(
                    "hwFixes.maxAnisotropy", R.string.settings_anisotropic, R.string.settings_anisotropic_summary,
                    listOf(Option(0, off)) + listOf(2, 4, 8, 16).map { Option(it, text("$it×")) },
                ),
                Toggle("display.gsBackThreadMode", R.string.settings_gs_thread, R.string.settings_gs_thread_summary, on = 1, off = 0),
                Choice(
                    "hwFixes.gpuProfile", R.string.settings_gpu_profile, R.string.settings_gpu_profile_summary,
                    options(auto, text("Mali"), text("Adreno"), text("PowerVR"), text("Xclipse")),
                ),
                Toggle(
                    "output.turnipUbwcHint", R.string.settings_turnip_ubwc, R.string.settings_turnip_ubwc_summary,
                    visibleWhen = { it.string("output.customDriverId").isNotEmpty() },
                ),
                Toggle("display.useAngleOpenGL", R.string.settings_angle, R.string.settings_angle_summary),
                Toggle("display.vsyncEnable", R.string.settings_vsync),
                Toggle("display.hwRov", R.string.settings_rov, R.string.settings_rov_summary),
                Toggle("display.hwAccurateAlphaTest", R.string.settings_accurate_alpha_test),
                Toggle("display.hwAa1", R.string.settings_hw_aa1),
                Toggle("display.coalesceRenderPasses", R.string.settings_coalesce_render_passes),
                Toggle("display.forceMaliFbFetch", R.string.settings_force_fb_fetch),
            ),
            advanced = true,
        ),
        SettingsSection(
            R.string.settings_section_post_processing,
            listOf(
                Toggle("graphics.fxaa", R.string.settings_fxaa),
                Choice(
                    "graphics.casMode", R.string.settings_cas, R.string.settings_cas_summary,
                    options(off, res(R.string.settings_opt_sharpen), res(R.string.settings_opt_sharpen_resize)),
                ),
                Slider(
                    "graphics.casSharpness", R.string.settings_cas_sharpness, range = 0..100,
                    unit = R.string.settings_unit_percent, enabledWhen = { it.int("graphics.casMode") != 0 },
                ),
                Choice(
                    "graphics.upscaler", R.string.settings_upscaler, R.string.settings_upscaler_summary,
                    listOf(
                        Option(0, off), Option(2, text("FSR 1")), Option(3, text("SGSR")),
                        Option(4, res(R.string.settings_opt_sgsr_edge)),
                    ),
                ),
                Slider(
                    "graphics.fsrSharpness", R.string.settings_upscaler_sharpness, range = 0..100,
                    unit = R.string.settings_unit_percent, visibleWhen = { it.int("graphics.upscaler") == 2 },
                ),
                Slider(
                    "graphics.sgsrSharpness", R.string.settings_upscaler_sharpness, range = 0..200, step = 5,
                    unit = R.string.settings_unit_percent, visibleWhen = { it.int("graphics.upscaler") in 3..4 },
                ),
                Choice(
                    "graphics.tvShader", R.string.settings_tv_shader,
                    options = options(
                        off, res(R.string.settings_opt_scanlines), res(R.string.settings_opt_diagonal),
                        res(R.string.settings_opt_triangular), res(R.string.settings_opt_wave),
                        text("Lottes CRT"), text("4xRGSS"), text("NxAGSS"),
                    ),
                ),
                Toggle("graphics.shadeBoost", R.string.settings_shade_boost, R.string.settings_shade_boost_summary),
                shadeBoostSlider("graphics.shadeBoostBrightness", R.string.settings_brightness),
                shadeBoostSlider("graphics.shadeBoostContrast", R.string.settings_contrast),
                shadeBoostSlider("graphics.shadeBoostSaturation", R.string.settings_saturation),
                shadeBoostSlider("graphics.shadeBoostGamma", R.string.settings_gamma),
            ),
            advanced = true,
        ),
        SettingsSection(
            R.string.settings_section_overlay,
            listOf(
                Toggle("osd.osdShowFps", R.string.settings_osd_fps),
                Toggle("osd.osdShowSpeed", R.string.settings_osd_speed),
                Toggle("osd.osdShowVps", R.string.settings_osd_vps),
                Toggle("osd.osdShowResolution", R.string.settings_osd_resolution),
                Toggle("osd.osdShowCpu", R.string.settings_osd_cpu),
                Toggle("osd.osdShowGpu", R.string.settings_osd_gpu),
                Toggle("osd.osdShowFrameTimes", R.string.settings_osd_frame_times),
                Toggle("frameLimit.screenshotButton", R.string.settings_screenshot_button, R.string.settings_screenshot_button_summary),
                Toggle("osd.osdShowMessages", R.string.settings_osd_messages, R.string.settings_osd_messages_summary),
                Slider("osd.osdScale", R.string.settings_osd_scale, range = 25..200, step = 5, unit = R.string.settings_unit_percent),
                Choice(
                    "osd.osdPosition", R.string.settings_osd_position,
                    options = listOf(
                        Option(1, res(R.string.settings_opt_top_left)), Option(3, res(R.string.settings_opt_top_right)),
                        Option(7, res(R.string.settings_opt_bottom_left)), Option(9, res(R.string.settings_opt_bottom_right)),
                    ),
                ),
            ),
            advanced = true,
        ),
    )

    private fun shadeBoostSlider(path: String, title: Int) = Slider(
        path, title, range = 1..100, enabledWhen = { it.boolean("graphics.shadeBoost") },
    )

    private fun performance() = listOf(
        SettingsSection(
            R.string.settings_section_speed,
            listOf(
                PresetSetting,
                Slider(
                    "frameLimit.nominalSpeedPercent", R.string.settings_speed_limit, R.string.settings_speed_limit_summary,
                    range = 10..500, step = 10, unit = R.string.settings_unit_percent,
                ),
                Slider(
                    "cpu.eeCycleRate", R.string.settings_ee_cycle_rate, R.string.settings_ee_cycle_rate_summary,
                    range = -3..3,
                    labels = listOf("50%", "60%", "75%", "100%", "130%", "180%", "300%")
                        .mapIndexed { index, label -> index - 3 to text(label) }.toMap(),
                ),
                Slider(
                    "cpu.eeCycleSkip", R.string.settings_ee_cycle_skip, R.string.settings_ee_cycle_skip_summary,
                    range = 0..3, labels = mapOf(0 to off),
                ),
                Toggle("cpu.mtvu", R.string.settings_mtvu, R.string.settings_mtvu_summary),
            ),
        ),
        SettingsSection(
            R.string.settings_section_frame_pacing,
            listOf(
                Toggle("hwFixes.vsyncQueueSize", R.string.settings_low_latency, R.string.settings_low_latency_summary, on = 0, off = 2),
                Toggle("frameLimit.frameLimitEnable", R.string.settings_frame_limiter, R.string.settings_frame_limiter_summary),
                Slider(
                    "frameLimit.fpsLimit", R.string.settings_fps_cap, R.string.settings_fps_cap_summary,
                    range = 0..60, step = 5, unit = R.string.settings_unit_fps, labels = mapOf(0 to off),
                ),
                NumberInput("output.framerateNtsc", R.string.settings_framerate_ntsc, min = 10.0, max = 120.0, decimal = true),
                NumberInput("output.frameratePal", R.string.settings_framerate_pal, min = 10.0, max = 120.0, decimal = true),
                Toggle("emuCore.skipDuplicateFrames", R.string.settings_skip_duplicate_frames, R.string.settings_skip_duplicate_frames_summary),
            ),
            advanced = true,
        ),
        SettingsSection(
            R.string.settings_section_speedhacks,
            listOf(
                Toggle("cpu.vu1Instant", R.string.settings_vu1_instant),
                Toggle("cpu.vuFlagHack", R.string.settings_vu_flag_hack),
                Toggle("cpu.intcStat", R.string.settings_intc_stat),
                Toggle("cpu.waitLoop", R.string.settings_wait_loop),
                Toggle("cpu.fastCDVD", R.string.settings_fast_cdvd, R.string.settings_fast_cdvd_summary),
                Toggle("cpu.vuNeonFusions", R.string.settings_vu_neon_fusions),
                Toggle("cpu.vuDeferredWrites", R.string.settings_vu_deferred_writes, R.string.settings_experimental_summary),
                Toggle("cpu.vuSkipStallSim", R.string.settings_vu_skip_stall_sim, R.string.settings_experimental_summary),
            ),
            advanced = true,
        ),
        SettingsSection(
            R.string.settings_section_cpu_accuracy,
            listOf(
                Choice(
                    "cpu.eeClampMode", R.string.settings_ee_clamping,
                    options = options(off, normal, res(R.string.settings_opt_extra), res(R.string.settings_opt_full), res(R.string.settings_opt_exact)),
                ),
                Choice("cpu.vuClampMode", R.string.settings_vu_clamping, options = options(*vuClampModes.toTypedArray())),
                Choice(
                    "cpu.vu1ClampMode", R.string.settings_vu1_clamping,
                    options = listOf(Option(-1, res(R.string.settings_opt_same_as_vu))) +
                        vuClampModes.mapIndexed { index, label -> Option(index, label) },
                ),
                Choice("emuCore.eeFpuRoundMode", R.string.settings_ee_round_mode, options = roundModes),
                Choice("emuCore.vu0RoundMode", R.string.settings_vu0_round_mode, options = roundModes),
                Choice("emuCore.vu1RoundMode", R.string.settings_vu1_round_mode, options = roundModes),
            ),
            advanced = true,
        ),
        SettingsSection(
            R.string.settings_section_device,
            listOf(
                Toggle("output.performanceHint", R.string.settings_adpf, R.string.settings_adpf_summary),
                Choice(
                    "output.affinityMode", R.string.settings_affinity, R.string.settings_affinity_summary,
                    options(
                        res(R.string.settings_opt_disabled), text("EE › VU › GS"), text("EE › GS › VU"), text("VU › EE › GS"),
                        text("VU › GS › EE"), text("GS › EE › VU"), text("GS › VU › EE"), res(R.string.settings_opt_performance_cores),
                    ),
                ),
            ),
            advanced = true,
        ),
    )

    private fun audio() = listOf(
        SettingsSection(
            R.string.settings_section_volume,
            listOf(
                Slider("audio.audioVolume", R.string.settings_volume, range = 0..200, step = 5, unit = R.string.settings_unit_percent),
                Toggle("audio.audioMuted", R.string.settings_mute),
            ),
        ),
        SettingsSection(
            R.string.settings_section_audio_output,
            listOf(
                Toggle("audio.audioTimeStretch", R.string.settings_time_stretch, R.string.settings_time_stretch_summary),
                Slider("audio.audioBufferMs", R.string.settings_audio_buffer, range = 20..200, step = 5, unit = R.string.settings_unit_ms),
                Slider(
                    "audio.audioOutputLatencyMs", R.string.settings_output_latency, R.string.settings_output_latency_summary,
                    range = 5..100, step = 5, unit = R.string.settings_unit_ms,
                ),
                Toggle("audio.audioSwapChannels", R.string.settings_swap_channels),
                Toggle("audio.spu2LightweightMix", R.string.settings_lightweight_audio, R.string.settings_lightweight_audio_summary),
                Toggle("audio.spu2NeonReverb", R.string.settings_neon_reverb, R.string.settings_experimental_summary),
                Toggle("audio.audioOpenSLES", R.string.settings_opensles, R.string.settings_opensles_summary),
            ),
            advanced = true,
        ),
    )

    private fun controls() = listOf(
        SettingsSection(
            R.string.settings_section_virtual_pad,
            listOf(
                Choice(
                    "controls.stickMode", R.string.settings_stick_mode, R.string.settings_stick_mode_summary,
                    options = options(res(R.string.settings_opt_stick_fixed), res(R.string.settings_opt_stick_floating)),
                ),
                Slider(
                    "controls.padOpacity", R.string.settings_pad_opacity,
                    range = 20..100, step = 5, unit = R.string.settings_unit_percent,
                ),
                Toggle("controls.padHaptics", R.string.settings_pad_haptics),
            ),
        ),
        SettingsSection(
            R.string.settings_section_controllers,
            listOf(ControllersSetting),
        ),
    )

    private fun system() = listOf(
        SettingsSection(
            R.string.settings_section_boot,
            listOf(
                Toggle("emuCore.enableFastBoot", R.string.settings_fast_boot, R.string.settings_fast_boot_summary),
                Toggle("output.autoProgressiveScan", R.string.settings_auto_progressive, R.string.settings_auto_progressive_summary),
                BiosManageSetting,
                GameBiosSetting,
            ),
        ),
        SettingsSection(
            R.string.settings_section_rewind,
            listOf(
                Toggle("rewind.enabled", R.string.settings_rewind, R.string.settings_rewind_summary),
                Slider(
                    "rewind.intervalMinutes", R.string.settings_rewind_interval,
                    range = 1..10, unit = R.string.settings_unit_minutes,
                    enabledWhen = { it.boolean("rewind.enabled") },
                ),
                Slider(
                    "rewind.keep", R.string.settings_rewind_keep,
                    range = 1..5, enabledWhen = { it.boolean("rewind.enabled") },
                ),
            ),
        ),
        SettingsSection(
            R.string.settings_section_patches,
            listOf(
                Toggle("emuCore.enablePatches", R.string.settings_compat_patches, R.string.settings_compat_patches_summary),
                Toggle("emuCore.enableNoInterlacingPatches", R.string.settings_no_interlacing_patches, R.string.settings_no_interlacing_patches_summary),
            ),
        ),
    )

    private fun advanced() = listOf(
        SettingsSection(
            R.string.settings_section_game_fixes,
            listOf(
                Toggle("emuCore.enableGameFixes", R.string.settings_gamedb_fixes, R.string.settings_gamedb_fixes_summary),
                Toggle("emuCore.gamefixSkipMpeg", R.string.settings_fix_skip_mpeg),
                Toggle("emuCore.gamefixSoftwareRendererFmv", R.string.settings_fix_software_fmv),
                Toggle("emuCore.gamefixEETiming", R.string.settings_fix_ee_timing),
                Toggle("emuCore.gamefixInstantDma", R.string.settings_fix_instant_dma),
                Toggle("emuCore.gamefixBlitInternalFps", R.string.settings_fix_blit_fps),
                Toggle("emuCore.gamefixOphFlag", R.string.settings_fix_oph_flag),
                Toggle("emuCore.gamefixGifFifo", R.string.settings_fix_gif_fifo),
                Toggle("emuCore.gamefixDmaBusy", R.string.settings_fix_dma_busy),
                Toggle("emuCore.gamefixVif1Stall", R.string.settings_fix_vif1_stall),
                Toggle("emuCore.gamefixIbit", R.string.settings_fix_ibit),
                Toggle("emuCore.gamefixFullVu0Sync", R.string.settings_fix_full_vu0_sync),
                Toggle("emuCore.gamefixVuAddSub", R.string.settings_fix_vu_add_sub),
                Toggle("emuCore.gamefixVuOverflow", R.string.settings_fix_vu_overflow),
                Toggle("emuCore.gamefixXgkick", R.string.settings_fix_xgkick),
                Toggle("emuCore.gamefixGoemonTlb", R.string.settings_fix_goemon_tlb),
                Toggle("emuCore.gamefixVuSync", R.string.settings_fix_vu_sync),
            ),
        ),
        SettingsSection(
            R.string.settings_section_display_fixes,
            listOf(
                Toggle("display.antiBlur", R.string.settings_anti_blur),
                Toggle("display.screenOffsets", R.string.settings_screen_offsets),
                Toggle("display.showOverscan", R.string.settings_show_overscan),
                Toggle("display.disableInterlaceOffset", R.string.settings_disable_interlace_offset),
                Toggle("display.syncToHostRefresh", R.string.settings_sync_host_refresh),
                Toggle("hwFixes.integerScaling", R.string.settings_integer_scaling),
                Choice(
                    "hwFixes.dithering", R.string.settings_dithering,
                    options = options(off, res(R.string.settings_opt_scaled), res(R.string.settings_opt_unscaled), res(R.string.settings_opt_force_32bit)),
                ),
                Choice(
                    "display.overrideTextureBarriers", R.string.settings_texture_barriers,
                    options = listOf(Option(-1, auto), Option(0, off), Option(1, on)),
                    inline = true,
                ),
                Toggle("display.disableFramebufferFetch", R.string.settings_disable_fb_fetch),
                Toggle("display.disableVertexShaderExpand", R.string.settings_disable_vs_expand),
                Toggle("display.useBlitSwapChain", R.string.settings_blit_swap_chain),
                Toggle("display.disableShaderCache", R.string.settings_disable_shader_cache),
            ),
        ),
        SettingsSection(
            R.string.settings_section_crop,
            listOf("cropTop" to R.string.settings_crop_top, "cropBottom" to R.string.settings_crop_bottom,
                "cropLeft" to R.string.settings_crop_left, "cropRight" to R.string.settings_crop_right).map { (field, title) ->
                NumberInput(
                    "hwFixes.$field", title, R.string.settings_crop_summary, min = 0.0, max = 200.0,
                    enabledWhen = { it.int("hwFixes.displayZoom") <= 100 },
                )
            },
        ),
        SettingsSection(
            R.string.settings_section_upscaling_fixes,
            listOf(
                Choice(
                    "hwFixes.halfPixelOffset", R.string.settings_half_pixel_offset, R.string.settings_half_pixel_offset_summary,
                    options(
                        off, normal, res(R.string.settings_opt_special), res(R.string.settings_opt_special_aggressive),
                        res(R.string.settings_opt_native), res(R.string.settings_opt_native_texture_offset),
                    ),
                ),
                Choice(
                    "hwFixes.nativeScaling", R.string.settings_native_scaling,
                    options = options(off, normal, aggressive, res(R.string.settings_opt_normal_plus), res(R.string.settings_opt_aggressive_plus)),
                ),
                Choice(
                    "hwFixes.roundSprite", R.string.settings_round_sprite,
                    options = options(off, res(R.string.settings_opt_half), res(R.string.settings_opt_full)),
                    inline = true,
                ),
                Choice(
                    "hwFixes.bilinearUpscale", R.string.settings_bilinear_upscale,
                    options = options(off, normal, res(R.string.settings_opt_half), res(R.string.settings_opt_forced)),
                    inline = true,
                ),
                Toggle("hwFixes.alignSprite", R.string.settings_align_sprite),
                Toggle("hwFixes.mergeSprite", R.string.settings_merge_sprite),
                Toggle("hwFixes.forceEvenSpritePosition", R.string.settings_even_sprite_position),
                Toggle("hwFixes.unscaledPaletteDraw", R.string.settings_unscaled_palette_draw),
                NumberInput("hwFixes.textureOffsetX", R.string.settings_texture_offset_x, min = 0.0, max = 10000.0),
                NumberInput("hwFixes.textureOffsetY", R.string.settings_texture_offset_y, min = 0.0, max = 10000.0),
            ),
        ),
        SettingsSection(
            R.string.settings_section_hardware_fixes,
            listOf(
                Choice(
                    "hwFixes.autoFlush", R.string.settings_auto_flush,
                    options = options(off, res(R.string.settings_opt_sprites), on),
                    inline = true,
                ),
                Choice(
                    "hwFixes.textureInsideRt", R.string.settings_texture_inside_rt,
                    options = options(off, res(R.string.settings_opt_inside), res(R.string.settings_opt_merge)),
                    inline = true,
                ),
                Choice(
                    "hwFixes.gpuTargetClut", R.string.settings_gpu_target_clut,
                    options = options(off, res(R.string.settings_opt_inside), res(R.string.settings_opt_forced)),
                    inline = true,
                ),
                Choice(
                    "hwFixes.cpuSpriteRenderBw", R.string.settings_cpu_sprite_bw,
                    options = options(off, text("64"), text("128"), text("256")),
                    inline = true,
                ),
                Choice(
                    "hwFixes.cpuSpriteRenderLevel", R.string.settings_cpu_sprite_level,
                    options = options(
                        off, res(R.string.settings_opt_sprites), res(R.string.settings_opt_triangles), aggressive,
                        res(R.string.settings_opt_full), res(R.string.settings_opt_maximum),
                    ),
                ),
                Choice(
                    "hwFixes.cpuClutRender", R.string.settings_cpu_clut_render,
                    options = options(off, normal, aggressive),
                    inline = true,
                ),
                Choice(
                    "hwFixes.limit24BitDepth", R.string.settings_limit_24bit_depth,
                    options = options(off, res(R.string.settings_opt_upper), res(R.string.settings_opt_lower)),
                    inline = true,
                ),
                NumberInput("hwFixes.skipDrawStart", R.string.settings_skip_draw_start, min = 0.0, max = 10000.0),
                NumberInput("hwFixes.skipDrawEnd", R.string.settings_skip_draw_end, min = 0.0, max = 10000.0),
                Toggle("hwFixes.gpuPaletteConversion", R.string.settings_gpu_palette_conversion),
                Toggle("hwFixes.cpuFramebufferConversion", R.string.settings_cpu_fb_conversion),
                Toggle("hwFixes.readTargetsWhenClosing", R.string.settings_read_targets_closing),
                Toggle("hwFixes.preloadFrameData", R.string.settings_preload_frame_data),
                Toggle("hwFixes.estimateTextureRegion", R.string.settings_estimate_texture_region),
                Toggle("hwFixes.drawBuffering", R.string.settings_draw_buffering),
                Toggle("hwFixes.disableDepthEmulation", R.string.settings_disable_depth_emulation),
                Toggle("hwFixes.disablePartialInvalidation", R.string.settings_disable_partial_invalidation),
                Toggle("hwFixes.disableSafeFeatures", R.string.settings_disable_safe_features),
                Toggle("hwFixes.disableRenderFixes", R.string.settings_disable_render_fixes),
                Toggle("hwFixes.spinGpuReadbacks", R.string.settings_spin_gpu),
                Toggle("hwFixes.spinCpuReadbacks", R.string.settings_spin_cpu),
            ),
        ),
        SettingsSection(
            R.string.settings_section_software_renderer,
            listOf(
                Toggle("output.autoFlushSw", R.string.settings_sw_auto_flush),
                Toggle("output.mipmapSw", R.string.settings_sw_mipmapping),
                Slider("output.swThreads", R.string.settings_sw_threads, range = 0..10),
                Slider("output.swThreadsHeight", R.string.settings_sw_tile_height, range = 0..8),
            ),
        ),
    )
}
