package com.kira3.scphx.emulation.macro

import com.kira3.scphx.data.macro.Macro
import com.kira3.scphx.data.macro.MemoryCondition
import com.kira3.scphx.emucore.EmulatorCore
import com.kira3.scphx.emucore.PadButton
import com.kira3.scphx.emucore.PadStick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Plays a [Macro] into the pad of [port]. Time is counted in emulated frames, so the macro keeps pace with
 * the game: opening the menu pauses it, and fast-forwarding speeds it up. A start condition
 * holds each run back until the game's memory says so; a stop condition ends the macro. Only one plays at a time; playing another replaces it.
 */
class MacroPlayer(private val core: EmulatorCore, private val port: Int, private val scope: CoroutineScope) {
    private val _playing = MutableStateFlow<Macro?>(null)

    /** The macro that is playing, or null. */
    val playing: StateFlow<Macro?> = _playing.asStateFlow()

    private val _waiting = MutableStateFlow(false)

    /** True while the macro waits for its start condition. */
    val waiting: StateFlow<Boolean> = _waiting.asStateFlow()

    private var job: Job? = null
    private val held = HashSet<PadButton>()
    private val sticks = HashSet<PadStick>()

    fun play(macro: Macro) {
        stop()
        if (macro.events.isEmpty()) return
        _playing.value = macro
        job = scope.launch {
            try {
                while (isActive) {
                    if (stopMet(macro) || !awaitStart(macro)) break
                    if (!runOnce(macro) || !macro.loop) break
                }
            } finally {
                releaseAll()
                _waiting.value = false
                if (_playing.value?.id == macro.id) _playing.value = null
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        releaseAll()
        _playing.value = null
    }

    /** Plays the macro once; false if the stop condition ended it. */
    private suspend fun runOnce(macro: Macro): Boolean {
        var next = 0
        var played = 0L
        var last = core.frameCount
        while (next < macro.events.size || played < macro.length) {
            delay(TICK_MS)
            val now = core.frameCount
            // A load state sends the counter back; that adds nothing.
            if (now > last) {
                played += now - last
                if (stopMet(macro)) {
                    releaseAll()
                    return false
                }
            }
            last = now
            while (next < macro.events.size && macro.events[next].t <= played) {
                send(macro.events[next++])
            }
        }
        releaseAll()
        return true
    }

    private fun send(e: com.kira3.scphx.data.macro.MacroEvent) {
        val button = e.button?.let { name -> PadButton.entries.firstOrNull { it.name == name } }
        if (button != null) {
            if (e.pressed) held += button else held -= button
            core.setPadButton(port, button, e.pressed, e.value)
            return
        }
        val stick = e.stick?.let { name -> PadStick.entries.firstOrNull { it.name == name } } ?: return
        if (e.x != 0f || e.y != 0f) sticks += stick else sticks -= stick
        core.setPadStick(port, stick, e.x, e.y)
    }

    private fun releaseAll() {
        for (b in held) core.setPadButton(port, b, false)
        held.clear()
        for (s in sticks) core.setPadStick(port, s, 0f, 0f)
        sticks.clear()
    }

    private fun stopMet(macro: Macro) = macro.stopWhen?.let { holds(it) } == true

    private fun holds(condition: MemoryCondition): Boolean =
        core.readValue(condition.address, condition.size)?.let(condition::holds) == true

    /** Waits for the start condition, a frame at a time; false if the stop condition came first. */
    private suspend fun awaitStart(macro: Macro): Boolean {
        val start = macro.startWhen ?: return true
        var seen = core.frameCount
        _waiting.value = true
        try {
            while (true) {
                if (holds(start)) return true
                if (stopMet(macro)) return false
                // Wait for the next frame; the count stands still while the game is paused.
                while (core.frameCount == seen) delay(TICK_MS)
                seen = core.frameCount
            }
        } finally {
            _waiting.value = false
        }
    }

    private companion object {
        const val TICK_MS = 2L
    }
}
