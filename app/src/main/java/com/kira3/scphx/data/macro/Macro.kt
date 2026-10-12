package com.kira3.scphx.data.macro

import kotlinx.serialization.Serializable

/**
 * One recorded input: at [t] ms from the start, either [button] goes down ([pressed], at
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

/** A recording of the pad's input, replayed on the same controls. [durationMs] includes the wait after the last input. */
@Serializable
data class Macro(
    val id: String,
    val name: String,
    val durationMs: Long,
    val events: List<MacroEvent>,
    /** Plays again from the start when it ends, until stopped. */
    val loop: Boolean = false,
)
