package com.kira3.scphx.emulation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kira3.scphx.R
import com.kira3.scphx.data.macro.Comparison
import com.kira3.scphx.data.macro.Macro
import com.kira3.scphx.data.macro.MemoryCondition

/** What the macro screens can ask for. */
interface MacroActions {
    fun play(macro: Macro)
    fun stop()
    fun record()
    fun rename(macro: Macro, name: String)
    fun setLoop(macro: Macro, loop: Boolean)
    fun setStartCondition(macro: Macro, condition: MemoryCondition?)
    fun setStopCondition(macro: Macro, condition: MemoryCondition?)
    fun delete(macro: Macro)
    fun manage()
}

/**
 * The macro button over the game. Tapped, it lists the game's macros to play without pausing; while
 * one plays it turns into a stop button.
 */
@Composable
fun MacroButton(macros: List<Macro>, playing: Boolean, actions: MacroActions, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = if (playing) 0.4f else 0.16f))
                .border(1.5.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                .clickable(role = Role.Button) { if (playing) actions.stop() else open = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(if (playing) R.drawable.ic_stop else R.drawable.ic_macro),
                contentDescription = stringResource(if (playing) R.string.macro_stop else R.string.macro_title),
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.padding(10.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (macro in macros) {
                DropdownMenuItem(
                    text = { Text(macro.name, maxLines = 1) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_macro), contentDescription = null) },
                    onClick = { open = false; actions.play(macro) },
                )
            }
            if (macros.isNotEmpty()) HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.macro_record)) },
                onClick = { open = false; actions.record() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.macro_manage)) },
                onClick = { open = false; actions.manage() },
            )
        }
    }
}

/**
 * A pill while a macro records or plays, with the button that ends it. Held upright it sits just
 * under the picture (which the core draws at the top with the shape [gameAspect], width / height),
 * so it does not cover the game; otherwise it is at the top of the screen.
 */
@Composable
fun MacroStatus(
    label: String,
    onStop: () -> Unit,
    recording: Boolean,
    gameAspect: Float? = null,
    /** While recording: puts a wait point at this moment. */
    onWait: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val upright = maxHeight > maxWidth
        val top = if (upright && gameAspect != null) {
            // The picture is shifted down by the display cutout (see EmulationActivity.pushDisplayCutoutInset).
            val cutout = WindowInsets.displayCutout.asPaddingValues().calculateTopPadding()
            cutout + maxWidth / gameAspect + 8.dp
        } else {
            WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding() + 8.dp
        }
        Box(Modifier.fillMaxWidth().padding(top = top), contentAlignment = Alignment.TopCenter) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.7f))
                        .clickable(role = Role.Button, onClick = onStop)
                        .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (recording) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFFF5252)))
                    }
                    Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    Icon(
                        painterResource(R.drawable.ic_stop),
                        contentDescription = stringResource(if (recording) R.string.macro_stop_recording else R.string.macro_stop),
                        tint = Color.White,
                        modifier = Modifier.padding(6.dp).size(20.dp),
                    )
                }
                if (onWait != null) {
                    Text(
                        stringResource(R.string.macro_wait_here),
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.7f))
                            .clickable(role = Role.Button, onClick = onWait)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

/** The game's macros over the paused game. [macros] is null while they are read. */
@Composable
fun MacrosMenu(
    macros: List<Macro>?,
    actions: MacroActions,
    /** Addresses (and their sizes) the memory search has narrowed down, to pick from for a condition. */
    suggestions: List<Pair<Int, Int>>,
    onClose: () -> Unit,
) {
    var editing by remember { mutableStateOf<Pair<Macro, Boolean>?>(null) }
    var renaming by remember { mutableStateOf<Macro?>(null) }
    var deleting by remember { mutableStateOf<Macro?>(null) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(interactionSource = null, indication = null, onClick = onClose)
            .safeDrawingPadding()
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().clickable(interactionSource = null, indication = null) {},
        ) {
            Column(Modifier.padding(vertical = 16.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.macro_title),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onClose) { Text(stringResource(R.string.menu_resume)) }
                }
                FilledTonalButton(
                    onClick = actions::record,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                ) { Text(stringResource(R.string.macro_record)) }
                when {
                    macros == null -> Unit
                    macros.isEmpty() -> Text(
                        stringResource(R.string.macro_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    )
                    else -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(macros, key = { it.id }) { macro ->
                            ListItem(
                                headlineContent = { Text(macro.name, maxLines = 1) },
                                supportingContent = { Text(macro.details()) },
                                trailingContent = {
                                    MacroMenu(
                                        macro,
                                        actions,
                                        onRename = { renaming = macro },
                                        onDelete = { deleting = macro },
                                        onCondition = { start -> editing = macro to start },
                                    )
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier.clickable { actions.play(macro) },
                            )
                        }
                    }
                }
            }
        }
    }

    editing?.let { (macro, start) ->
        ConditionDialog(
            kind = if (start) ConditionKind.Start else ConditionKind.Stop,
            current = if (start) macro.startWhen else macro.stopWhen,
            suggestions = suggestions,
            onDismiss = { editing = null },
            onSave = { condition, _ ->
                editing = null
                if (start) actions.setStartCondition(macro, condition) else actions.setStopCondition(macro, condition)
            },
        )
    }
    renaming?.let { macro ->
        var name by remember(macro.id) { mutableStateOf(macro.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.macro_rename)) },
            text = {
                OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        renaming = null
                        actions.rename(macro, name.trim())
                    },
                ) { Text(stringResource(R.string.macro_rename)) }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.menu_cancel)) }
            },
        )
    }
    deleting?.let { macro ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.macro_delete_title, macro.name)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    actions.delete(macro)
                }) { Text(stringResource(R.string.macro_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.menu_cancel)) }
            },
        )
    }
}

@Composable
private fun MacroMenu(
    macro: Macro,
    actions: MacroActions,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onCondition: (start: Boolean) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.macro_actions))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(if (macro.loop) R.string.macro_loop_off else R.string.macro_loop_on)) },
                onClick = { open = false; actions.setLoop(macro, !macro.loop) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.macro_start_condition)) },
                onClick = { open = false; onCondition(true) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.macro_stop_condition)) },
                onClick = { open = false; onCondition(false) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.macro_rename)) },
                onClick = { open = false; onRename() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.macro_delete)) },
                onClick = { open = false; onDelete() },
            )
        }
    }
}

@Composable
private fun Macro.details(): String {
    val inputs = pluralStringResource(R.plurals.macro_inputs, events.size, events.size)
    val parts = mutableListOf(inputs, durationLabel(length * 1000 / FRAMES_PER_SECOND))
    if (loop) parts += stringResource(R.string.macro_loop)
    startWhen?.let { parts += stringResource(R.string.macro_start_summary, it.describe()) }
    stopWhen?.let { parts += stringResource(R.string.macro_stop_summary, it.describe()) }
    if (waits.isNotEmpty()) parts += pluralStringResource(R.plurals.macro_waits, waits.size, waits.size)
    return parts.joinToString(" · ")
}

/** `0:07` or `12:03`. */
fun durationLabel(ms: Long): String {
    val seconds = (ms + 500) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/** Frames in a second of game time, for showing a macro's length. */
private const val FRAMES_PER_SECOND = 60

/**
 * Sets (or clears) a condition on the game's memory: the address as the memory search shows it
 * (hex), the size, how to compare, and the number. [suggestions] fill the address in one tap.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConditionDialog(
    kind: ConditionKind,
    current: MemoryCondition?,
    suggestions: List<Pair<Int, Int>>,
    onDismiss: () -> Unit,
    /** The condition (null clears it) and, for a wait point, the timeout in seconds (null: wait for ever). */
    onSave: (MemoryCondition?, Long?) -> Unit,
) {
    var timeout by remember { mutableStateOf("") }
    var address by remember { mutableStateOf(current?.let { "%08X".format(it.address) }.orEmpty()) }
    var size by remember { mutableStateOf(current?.size ?: 4) }
    var op by remember { mutableStateOf(current?.op ?: Comparison.Equal) }
    var value by remember { mutableStateOf(current?.value?.toString().orEmpty()) }
    val parsed = address.toIntOrNull(16)?.takeIf { it in 0 until MAIN_RAM }
        ?.let { a -> value.toLongOrNull()?.takeIf { it >= 0 }?.let { MemoryCondition(a, size, op, it) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    when (kind) {
                        ConditionKind.Start -> R.string.macro_start_condition
                        ConditionKind.Stop -> R.string.macro_stop_condition
                        ConditionKind.Wait -> R.string.macro_wait_title
                    },
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        when (kind) {
                            ConditionKind.Start -> R.string.macro_start_hint
                            ConditionKind.Stop -> R.string.macro_stop_hint
                            ConditionKind.Wait -> R.string.macro_wait_hint
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    address,
                    { address = it.filter { c -> c.isDigit() || c in 'a'..'f' || c in 'A'..'F' }.take(8).uppercase() },
                    label = { Text(stringResource(R.string.macro_condition_address)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (suggestions.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((a, s) in suggestions.take(MAX_SUGGESTIONS)) {
                            FilterChip(
                                selected = address == "%08X".format(a) && size == s,
                                onClick = { address = "%08X".format(a); size = s },
                                label = { Text("%08X".format(a)) },
                            )
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (s in listOf(1, 2, 4)) {
                        FilterChip(
                            selected = size == s,
                            onClick = { size = s },
                            label = { Text(stringResource(R.string.search_size, s)) },
                        )
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (c in Comparison.entries) {
                        FilterChip(selected = op == c, onClick = { op = c }, label = { Text(c.symbol) })
                    }
                }
                OutlinedTextField(
                    value,
                    { value = it.filter(Char::isDigit).take(10) },
                    label = { Text(stringResource(R.string.search_value)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (kind == ConditionKind.Wait) {
                    OutlinedTextField(
                        timeout,
                        { timeout = it.filter(Char::isDigit).take(5) },
                        label = { Text(stringResource(R.string.macro_wait_timeout)) },
                        supportingText = { Text(stringResource(R.string.macro_wait_timeout_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = parsed != null, onClick = { onSave(parsed, timeout.toLongOrNull()) }) {
                Text(stringResource(R.string.macro_condition_save))
            }
        },
        dismissButton = {
            Row {
                if (current != null) {
                    TextButton(onClick = { onSave(null, null) }) { Text(stringResource(R.string.macro_condition_clear)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.menu_cancel)) }
            }
        },
    )
}

/** What a [ConditionDialog] sets. */
enum class ConditionKind { Start, Stop, Wait }

/** Frames in a second of game time, for turning a timeout in seconds into frames. */
fun secondsToFrames(seconds: Long): Long = seconds * FRAMES_PER_SECOND

private const val MAIN_RAM = 32 * 1024 * 1024
private const val MAX_SUGGESTIONS = 6
