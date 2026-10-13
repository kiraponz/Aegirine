package com.kira3.scphx.data.macro

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * One recorded input: at frame [t] from the start, either [button] goes down ([pressed], at
 * [value] of full pressure) or up, or [stick] moves to ([x], [y]). The names are the
 * [com.kira3.scphx.emucore.PadButton] and [com.kira3.scphx.emucore.PadStick] entries.
 */
@Serializable
data class MacroEvent(
    val t: Long,
    val button: String? = null,
    val pressed: Boolean = false,
    val value: Float = 1f,
    val stick: String? = null,
    val x: Float = 0f,
    val y: Float = 0f,
)

/**
 * A recording of the pad's input, replayed on the same controls. Time is counted in emulated
 * frames, so it follows the game when it is fast-forwarded or slow. [length] includes the wait
 * after the last input. Macros saved in milliseconds ([frames] false) are converted when read.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Macro(
    val id: String,
    val name: String,
    @JsonNames("durationMs") val length: Long,
    val events: List<MacroEvent>,
    /** Plays again from the start when it ends, until stopped. */
    val loop: Boolean = false,
    val frames: Boolean = false,
)

/** Nominal frames per second, to convert the milliseconds of older macros. */
private const val LEGACY_FPS = 60

/** This macro with its times in frames. */
fun Macro.inFrames(): Macro = if (frames) this else copy(
    length = length * LEGACY_FPS / 1000,
    events = events.map { it.copy(t = it.t * LEGACY_FPS / 1000) },
    frames = true,
)
