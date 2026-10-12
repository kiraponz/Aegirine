package com.kira3.scphx.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kira3.scphx.R
import com.kira3.scphx.emucore.settings.PerformancePreset
import com.kira3.scphx.emucore.settings.SettingsValues
import java.util.Locale
import kotlin.math.roundToInt

/** What a row can ask for. Paths are dotted EmulatorSettings paths. */
interface SettingsActions {
    fun set(path: String, value: Any)
    fun applyPreset(preset: PerformancePreset)
    fun reset(path: String)
}

/** One setting as a segmented list item. [shapes] places it within its section's group. */
@Composable
fun SettingRow(
    setting: Setting,
    state: SettingsUiState,
    isGame: Boolean,
    shapes: ListItemShapes,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    val values = state.values
    val enabled = setting.enabledWhen?.invoke(values) != false
    val path = setting.path
    val overline: (@Composable () -> Unit)? =
        if (isGame && path != null && path in state.overrides) {
            { OverrideBadge { actions.reset(path) } }
        } else {
            null
        }
    val row = RowParams(setting, values, enabled, shapes, overline, modifier)
    when (setting) {
        is Toggle -> ToggleRow(row, setting, actions)
        is Choice -> if (setting.inline) InlineChoiceRow(row, setting, actions) else DialogChoiceRow(row, setting, actions)
        is Slider -> SliderRow(row, setting, actions)
        is NumberInput -> NumberRow(row, setting, actions)
        PresetSetting -> PresetRow(row, actions)
        GameBiosSetting -> DialogChoiceRow(row, biosChoice(state), actions)
    }
}

private class RowParams(
    val setting: Setting,
    val values: SettingsValues,
    val enabled: Boolean,
    val shapes: ListItemShapes,
    val overline: (@Composable () -> Unit)?,
    val modifier: Modifier,
)

@Composable
private fun biosChoice(state: SettingsUiState): Choice = Choice(
    GameBiosSetting.path!!,
    GameBiosSetting.title,
    GameBiosSetting.summary,
    options = listOf(Option("", Label.res(R.string.settings_bios_default))) +
        state.bioses.map { Option(it.fileName, Label.text("${it.metadata.description} (${it.fileName})")) },
    gameOnly = true,
)

/** The text under a row's title: its current value, what it does, and when it takes effect. */
@Composable
private fun Supporting(setting: Setting, value: String? = null, showSummary: Boolean = true) {
    Column {
        if (value != null) Text(value, color = MaterialTheme.colorScheme.primary)
        if (showSummary && setting.summary != 0) Text(stringResource(setting.summary))
        if (setting.appliesOnNextBoot) {
            Text(
                stringResource(R.string.settings_applies_next_boot),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

private fun Setting.hasSupporting(value: Boolean = false) = value || summary != 0 || appliesOnNextBoot

@Composable
private fun ToggleRow(row: RowParams, setting: Toggle, actions: SettingsActions) {
    val path = setting.path!!
    val checked = row.values[path] == setting.on
    SegmentedListItem(
        checked = checked,
        onCheckedChange = { actions.set(path, if (it) setting.on else setting.off) },
        shapes = row.shapes,
        colors = settingsListColors(),
        modifier = row.modifier,
        enabled = row.enabled,
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = row.enabled) },
        overlineContent = row.overline,
        supportingContent = if (setting.hasSupporting()) ({ Supporting(setting) }) else null,
    ) { Text(stringResource(setting.title)) }
}

@Composable
private fun InlineChoiceRow(row: RowParams, setting: Choice, actions: SettingsActions) {
    val path = setting.path!!
    SegmentedListItem(
        shapes = row.shapes,
        colors = settingsListColors(),
        modifier = row.modifier,
        enabled = row.enabled,
        overlineContent = row.overline,
        supportingContent = {
            Column {
                if (setting.hasSupporting()) Supporting(setting)
                Spacer(Modifier.height(12.dp))
                ConnectedChoice(setting.options, row.values[path], row.enabled) { actions.set(path, it) }
            }
        },
    ) { Text(stringResource(setting.title)) }
}

/** Options as a connected button group, the Expressive replacement for segmented buttons. */
@Composable
private fun ConnectedChoice(options: List<Option>, selected: Any?, enabled: Boolean, onSelect: (Any) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { index, option ->
            ToggleButton(
                checked = option.value == selected,
                onCheckedChange = { onSelect(option.value) },
                enabled = enabled,
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
            ) {
                Text(option.label.resolve(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun DialogChoiceRow(row: RowParams, setting: Choice, actions: SettingsActions) {
    val path = setting.path!!
    val current = row.values[path]
    var open by rememberSaveable { mutableStateOf(false) }
    val label = setting.options.firstOrNull { it.value == current }?.label?.resolve() ?: current.toString()
    SegmentedListItem(
        onClick = { open = true },
        shapes = row.shapes,
        colors = settingsListColors(),
        modifier = row.modifier,
        enabled = row.enabled,
        overlineContent = row.overline,
        supportingContent = { Supporting(setting, value = label, showSummary = false) },
    ) { Text(stringResource(setting.title)) }

    if (open) {
        ChoiceDialog(
            setting = setting,
            selected = current,
            onSelect = {
                open = false
                if (it != current) actions.set(path, it)
            },
            onDismiss = { open = false },
        )
    }
}

@Composable
private fun ChoiceDialog(setting: Choice, selected: Any, onSelect: (Any) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(setting.title)) },
        text = {
            Column {
                if (setting.summary != 0) {
                    Text(stringResource(setting.summary), modifier = Modifier.padding(bottom = 12.dp))
                }
                LazyColumn {
                    items(setting.options) { option ->
                        val isSelected = option.value == selected
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(selected = isSelected, role = Role.RadioButton) { onSelect(option.value) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = isSelected, onClick = null)
                            Text(option.label.resolve(), modifier = Modifier.padding(start = 16.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}

@Composable
private fun SliderRow(row: RowParams, setting: Slider, actions: SettingsActions) {
    val path = setting.path!!
    val range = setting.range
    val current = row.values.int(path)
    // Follows the finger while dragging; saved once the drag ends.
    var position by remember(current) { mutableFloatStateOf(current.coerceIn(range).toFloat()) }
    fun snapped() = (((position - range.first) / setting.step).roundToInt() * setting.step + range.first).coerceIn(range)
    val stops = (range.last - range.first) / setting.step
    SegmentedListItem(
        shapes = row.shapes,
        colors = settingsListColors(),
        modifier = row.modifier,
        enabled = row.enabled,
        overlineContent = row.overline,
        trailingContent = { Text(sliderLabel(setting, snapped()), style = MaterialTheme.typography.labelLarge) },
        supportingContent = {
            Column {
                if (setting.hasSupporting()) Supporting(setting)
                Slider(
                    value = position,
                    onValueChange = { position = it },
                    onValueChangeFinished = {
                        val value = snapped()
                        position = value.toFloat()
                        if (value != current) actions.set(path, value)
                    },
                    valueRange = range.first.toFloat()..range.last.toFloat(),
                    // Tick marks only while they stay far enough apart to read.
                    steps = if (stops <= 20) stops - 1 else 0,
                    enabled = row.enabled,
                )
            }
        },
    ) { Text(stringResource(setting.title)) }
}

@Composable
private fun sliderLabel(setting: Slider, value: Int): String =
    setting.labels[value]?.resolve() ?: if (setting.unit != 0) stringResource(setting.unit, value) else value.toString()

@Composable
private fun NumberRow(row: RowParams, setting: NumberInput, actions: SettingsActions) {
    val path = setting.path!!
    val current = row.values[path] as Number
    var open by rememberSaveable { mutableStateOf(false) }
    SegmentedListItem(
        onClick = { open = true },
        shapes = row.shapes,
        colors = settingsListColors(),
        modifier = row.modifier,
        enabled = row.enabled,
        overlineContent = row.overline,
        supportingContent = { Supporting(setting, value = formatNumber(current), showSummary = false) },
    ) { Text(stringResource(setting.title)) }

    if (open) {
        NumberDialog(
            setting = setting,
            initial = formatNumber(current),
            onConfirm = {
                open = false
                actions.set(path, it)
            },
            onDismiss = { open = false },
        )
    }
}

private fun formatNumber(value: Number): String =
    if (value is Float || value is Double) {
        String.format(Locale.ROOT, "%.3f", value.toDouble()).trimEnd('0').trimEnd('.')
    } else {
        value.toString()
    }

@Composable
private fun NumberDialog(setting: NumberInput, initial: String, onConfirm: (Number) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val parsed = text.trim().replace(',', '.').toDoubleOrNull()
        ?.takeIf { it in setting.min..setting.max && (setting.decimal || it % 1.0 == 0.0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(setting.title)) },
        text = {
            Column {
                if (setting.summary != 0) {
                    Text(stringResource(setting.summary), modifier = Modifier.padding(bottom = 12.dp))
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    isError = parsed == null,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (setting.decimal) KeyboardType.Decimal else KeyboardType.Number,
                    ),
                    supportingText = {
                        Text(stringResource(R.string.settings_number_range, formatNumber(setting.min), formatNumber(setting.max)))
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { parsed?.let { onConfirm(if (setting.decimal) it.toFloat() else it.toInt()) } },
                enabled = parsed != null,
            ) { Text(stringResource(R.string.settings_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}

@Composable
private fun PresetRow(row: RowParams, actions: SettingsActions) {
    val matching = remember(row.values) { PerformancePreset.matching(row.values.settings) }
    val options = PerformancePreset.entries.map {
        Option(
            it,
            Label.res(
                when (it) {
                    PerformancePreset.Optimal -> R.string.settings_preset_optimal
                    PerformancePreset.Fast -> R.string.settings_preset_fast
                    PerformancePreset.LowEnd -> R.string.settings_preset_low_end
                },
            ),
        )
    }
    SegmentedListItem(
        shapes = row.shapes,
        colors = settingsListColors(),
        modifier = row.modifier,
        supportingContent = {
            Column {
                Text(stringResource(if (matching == null) R.string.settings_preset_custom else PresetSetting.summary))
                Spacer(Modifier.height(12.dp))
                ConnectedChoice(options, matching, enabled = true) { actions.applyPreset(it as PerformancePreset) }
            }
        },
    ) { Text(stringResource(PresetSetting.title)) }
}

/** Marks a value the game sets for itself. Tapping it goes back to the global value. */
@Composable
private fun OverrideBadge(onReset: () -> Unit) {
    val description = stringResource(R.string.settings_override_badge_description)
    Surface(
        onClick = onReset,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.padding(bottom = 4.dp).semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.settings_this_game), style = MaterialTheme.typography.labelMedium)
            Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(14.dp))
        }
    }
}

/** Shapes for item [index] of [count] in a section's group. */
@Composable
fun segmentedShapes(index: Int, count: Int): ListItemShapes = ListItemDefaults.segmentedShapes(index, count)

/**
 * Items stand out from the page as in Android's own settings: bright items on a container
 * background. Only [highlightSelected] rows tint when selected; a switch that is on does not.
 */
@Composable
fun settingsListColors(highlightSelected: Boolean = false): ListItemColors = ListItemDefaults.segmentedColors(
    containerColor = MaterialTheme.colorScheme.surfaceBright,
    selectedContainerColor = if (highlightSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceBright,
    selectedContentColor = if (highlightSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
)
