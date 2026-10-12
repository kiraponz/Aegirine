package com.kira3.scphx.emulation.input

/**
 * The width / height of the picture for a DisplaySettings.aspectRatio value, or null when it
 * has no fixed shape (Stretch fills the screen).
 */
fun gameAspectOf(aspectRatio: Int, customAspectRatio: Float): Float? = when (aspectRatio) {
    0 -> null
    3 -> 16f / 9f
    4 -> 10f / 7f
    5 -> 21f / 9f
    6 -> 20f / 9f
    7 -> 19.5f / 9f
    8 -> customAspectRatio.takeIf { it > 0f }
    else -> 4f / 3f
}
