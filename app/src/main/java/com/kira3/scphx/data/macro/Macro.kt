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
    /** Waits for this before each run. */
    val startWhen: MemoryCondition? = null,
    /** Ends the macro (and its loop) as soon as this holds. */
    val stopWhen: MemoryCondition? = null,
)

/** Nominal frames per second, to convert the milliseconds of older macros. */
private const val LEGACY_FPS = 60

/** This macro with its times in frames. */
fun Macro.inFrames(): Macro = if (frames) this else copy(
    length = length * LEGACY_FPS / 1000,
    events = events.map { it.copy(t = it.t * LEGACY_FPS / 1000) },
    frames = true,
)

enum class Comparison(val symbol: String) {
    Equal("="), NotEqual("\u2260"), Less("<"), LessOrEqual("\u2264"), Greater(">"), GreaterOrEqual("\u2265"),
}

/** The [size]-byte unsigned number at EE address [address] compared with [value] by [op]. */
@Serializable
data class MemoryCondition(
    val address: Int,
    val size: Int = 4,
    val op: Comparison = Comparison.Equal,
    val value: Long,
) {
    fun holds(current: Long): Boolean = when (op) {
        Comparison.Equal -> current == value
        Comparison.NotEqual -> current != value
        Comparison.Less -> current < value
        Comparison.LessOrEqual -> current <= value
        Comparison.Greater -> current > value
        Comparison.GreaterOrEqual -> current >= value
    }

    /** `0065B93C \u2265 100`. */
    fun describe(): String = "%08X %s %d".format(address, op.symbol, value)
}
