package com.kira3.ametrine.emulation.input

import com.kira3.ametrine.emucore.settings.DisplaySettings

/**
 * The width / height of the picture for [display]'s aspect ratio, or null when it has no fixed
 * shape (it fills the screen). The game's own ratio and square pixels are taken as 4:3, which
 * nearly every game is.
 */
fun gameAspectOf(display: DisplaySettings): Float? = when (display.aspectRatio) {
    "Auto (Match Window)" -> null
    "16:9" -> 16f / 9f
    "19:9" -> 19f / 9f
    "20:9" -> 20f / 9f
    "Custom" -> (display.customAspectNumerator.toFloat() / display.customAspectDenominator).takeIf { it > 0f }
    else -> 4f / 3f
}
