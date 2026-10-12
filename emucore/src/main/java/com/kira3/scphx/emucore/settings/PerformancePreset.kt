package com.kira3.scphx.emucore.settings

/**
 * ARMSX2's speedhack profiles. A preset has no stored value of its own: it rewrites a few
 * fields, and the settings match it while those fields still hold its values.
 */
enum class PerformancePreset {
    /** The recommended values, safe for most games. */
    Optimal,

    /** Cycle skip, fast CDVD and native resolution. May glitch some games. */
    Fast,

    /** Fast plus every cheap GPU lever. MTVU only with enough cores to run it. */
    LowEnd;

    fun applyTo(s: EmulatorSettings, cores: Int = Runtime.getRuntime().availableProcessors()): EmulatorSettings {
        val speedhacks = s.cpu.copy(
            eeCycleRate = 0,
            mtvu = this != LowEnd || cores >= 6,
            vu1Instant = true,
            vuFlagHack = true,
            intcStat = true,
            waitLoop = true,
        )
        return when (this) {
            Optimal -> s.copy(
                cpu = speedhacks.copy(eeCycleSkip = 0, fastCDVD = false),
                display = s.display.copy(hwRov = false),
                // The GPU levers the other presets lower. Resolution is left alone.
                graphics = s.graphics.copy(accurateBlendingUnit = 1, hwMipmap = true, texturePreloading = 2),
            )
            Fast -> s.copy(
                cpu = speedhacks.copy(eeCycleSkip = 2, fastCDVD = true),
                output = s.output.copy(upscaleFloat = 1.0f),
                graphics = s.graphics.copy(accurateBlendingUnit = 1),
            )
            LowEnd -> s.copy(
                cpu = speedhacks.copy(eeCycleSkip = 1, fastCDVD = true),
                display = s.display.copy(hwRov = false),
                hwFixes = s.hwFixes.copy(gpuPaletteConversion = false),
                output = s.output.copy(upscaleFloat = 1.0f),
                graphics = s.graphics.copy(accurateBlendingUnit = 0, hwMipmap = false, texturePreloading = 1),
            )
        }
    }

    companion object {
        /** The preset [s] matches, or null if it has been tuned by hand. */
        fun matching(s: EmulatorSettings, cores: Int = Runtime.getRuntime().availableProcessors()): PerformancePreset? =
            entries.firstOrNull { it.applyTo(s, cores) == s }
    }
}
