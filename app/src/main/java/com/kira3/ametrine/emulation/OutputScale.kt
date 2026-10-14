package com.kira3.ametrine.emulation

/**
 * Output-surface sizing for [hwScaler] and [screenResOverride], both app-side
 * (no core INI key) and per-game scoped. Mirrors ARMSX2's EmulationSurface.
 *
 * The view's laid-out size stays as is; only the SurfaceHolder's buffer size is
 * pinned, and the compositor upscales it to the screen.
 */

/** Parses a "WIDTHxHEIGHT" override (e.g. "1920x1080"), or null for "auto"/unset/bad. */
internal fun parseResOverride(value: String?): Pair<Int, Int>? {
    if (value.isNullOrBlank() || value == "auto") return null
    val m = Regex("^\\s*(\\d{2,5})\\s*[xX]\\s*(\\d{2,5})\\s*$").find(value) ?: return null
    val w = m.groupValues[1].toIntOrNull() ?: return null
    val h = m.groupValues[2].toIntOrNull() ?: return null
    return if (w in 64..8192 && h in 64..8192) w to h else null
}

/**
 * The buffer size to pin with `setFixedSize`, or null to track the layout
 * (`setSizeFromLayout`, the original behavior).
 */
internal fun outputBufferSize(
    viewWidth: Int,
    viewHeight: Int,
    hwScaler: Int,
    screenResOverride: String?,
): Pair<Int, Int>? {
    if (viewWidth <= 0 || viewHeight <= 0) return null
    val override = parseResOverride(screenResOverride)
    // The override is a landscape size: on a screen held upright it sets only the
    // resolution, keeping the view's own shape at the override's short side.
    val (baseW, baseH) = when {
        override == null -> viewWidth to viewHeight
        viewHeight <= viewWidth -> override
        else -> {
            val short = minOf(override.first, override.second)
            short to (short.toLong() * viewHeight / viewWidth).toInt()
        }
    }

    // hwScaler downscales the short side to 480 (twice the PS1's 240 lines) * multiplier for weak GPUs.
    val shortSide = minOf(baseW, baseH)
    val targetShortSide = if (hwScaler > 0) 480 * hwScaler else shortSide
    val scale = if (targetShortSide in 1 until shortSide) targetShortSide.toFloat() / shortSide else 1f

    if (override == null && scale == 1f) return null
    return (baseW * scale).toInt().coerceAtLeast(1) to (baseH * scale).toInt().coerceAtLeast(1)
}

/**
 * Converts a cutout inset from view pixels to surface (buffer) pixels. The two only
 * differ while hwScaler/screenResOverride pins a smaller buffer, which the compositor
 * then upscales: shifting by unscaled view pixels would overshoot.
 */
internal fun scaleInsetToBuffer(insetViewPx: Int, viewHeight: Int, bufferHeight: Int): Int {
    if (insetViewPx <= 0 || viewHeight <= 0 || bufferHeight <= 0) return 0
    return (insetViewPx.toLong() * bufferHeight / viewHeight).toInt()
}
