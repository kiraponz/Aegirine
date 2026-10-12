package com.kira3.scphx.emulation

import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kira3.scphx.R
import com.kira3.scphx.emucore.settings.EmulatorSettings

/** One save-state slot as the menu shows it. [savedAt] is null for an empty slot. */
class SaveSlot(val slot: Int, val savedAt: Long?, val thumbnail: ImageBitmap?)

/** What the in-game menu can ask for. */
interface InGameMenuActions {
    fun resume()
    fun selectSlot(slot: Int)
    fun saveState()
    fun loadState()
    fun screenshot()
    fun reset()
    fun exit()

    /** Saves where the game stands, then exits; the next start picks up from there. */
    fun exitAndResumeLater()
    fun rewind()
    fun cheats()
    fun macros()
    fun setMacroButton(show: Boolean)
    fun setShowMemos(show: Boolean)
    fun setShowFps(show: Boolean)
    fun setFastForwardButton(show: Boolean)
    fun setScreenshotButton(show: Boolean)
    fun setFastForwardSpeed(speed: Int)
}

/** Fast-forward speeds offered, as [com.kira3.scphx.emucore.settings.FrameLimitSettings.fastForwardSpeed] values. */
private val FAST_FORWARD_SPEEDS = listOf(0) + (2..10)

/**
 * The menu the back key opens over a paused game. [slots] is null while the slots are being
 * read; [busy] disables everything while a save or load runs.
 */
@Composable
fun InGameMenu(
    settings: EmulatorSettings,
    slots: List<SaveSlot>?,
    selectedSlot: Int,
    busy: Boolean,
    hasGame: Boolean,
    actions: InGameMenuActions,
) {
    var confirm by remember { mutableStateOf<Confirm?>(null) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(interactionSource = null, indication = null, enabled = !busy, onClick = actions::resume)
            .safeDrawingPadding()
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.menu_paused),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = actions::resume, enabled = !busy) {
                        Text(stringResource(R.string.menu_resume))
                    }
                }

                SectionHeader(stringResource(R.string.menu_save_states))
                SlotRow(slots, selectedSlot, busy, actions::selectSlot)
                val selected = slots?.firstOrNull { it.slot == selectedSlot }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(
                        onClick = actions::saveState,
                        enabled = !busy && slots != null,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.menu_save)) }
                    OutlinedButton(
                        onClick = actions::loadState,
                        enabled = !busy && selected?.savedAt != null,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.menu_load)) }
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                if (hasGame && settings.rewind.enabled) {
                    MenuAction(stringResource(R.string.menu_rewind), !busy, actions::rewind)
                }
                if (hasGame) {
                    MenuAction(stringResource(R.string.menu_cheats), !busy, actions::cheats)
                }
                if (hasGame) {
                    MenuAction(stringResource(R.string.macro_title), !busy, actions::macros)
                }
                MenuAction(stringResource(R.string.menu_screenshot), !busy, actions::screenshot)
                MenuAction(stringResource(R.string.menu_reset), !busy) { confirm = Confirm.Reset }
                if (hasGame) {
                    MenuAction(stringResource(R.string.menu_exit_suspend), !busy, actions::exitAndResumeLater)
                }
                MenuAction(stringResource(R.string.menu_exit), !busy) { confirm = Confirm.Exit }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                MenuSwitch(stringResource(R.string.menu_show_fps), settings.osd.osdShowFps, !busy, actions::setShowFps)
                MenuSwitch(
                    stringResource(R.string.menu_fast_forward_button),
                    settings.frameLimit.fastForwardButton,
                    !busy,
                    actions::setFastForwardButton,
                )
                MenuSwitch(
                    stringResource(R.string.menu_screenshot_button),
                    settings.frameLimit.screenshotButton,
                    !busy,
                    actions::setScreenshotButton,
                )
                if (hasGame) {
                    MenuSwitch(
                        stringResource(R.string.menu_macro_button),
                        settings.frameLimit.macroButton,
                        !busy,
                        actions::setMacroButton,
                    )
                }
                if (hasGame && settings.gemini.enabled) {
                    MenuSwitch(
                        stringResource(R.string.menu_show_memos),
                        settings.gemini.memosVisible,
                        !busy,
                        actions::setShowMemos,
                    )
                }
                FastForwardSpeed(settings.frameLimit.fastForwardSpeed, !busy, actions::setFastForwardSpeed)
            }
        }
    }

    confirm?.let { action ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(action.title)) },
            text = { Text(stringResource(R.string.menu_confirm_unsaved)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    if (action == Confirm.Reset) actions.reset() else actions.exit()
                }) { Text(stringResource(action.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.menu_cancel)) }
            },
        )
    }
}

private enum class Confirm(val title: Int, val confirm: Int) {
    Reset(R.string.menu_confirm_reset, R.string.menu_reset),
    Exit(R.string.menu_confirm_exit, R.string.menu_exit),
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun SlotRow(slots: List<SaveSlot>?, selectedSlot: Int, busy: Boolean, onSelect: (Int) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(slots.orEmpty(), key = { it.slot }) { slot ->
            SlotCard(slot, slot.slot == selectedSlot, !busy) { onSelect(slot.slot) }
        }
    }
}

@Composable
private fun SlotCard(slot: SaveSlot, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .width(128.dp)
            .clip(MaterialTheme.shapes.medium)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) colors.primary else colors.outlineVariant,
                shape = MaterialTheme.shapes.medium,
            )
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(4f / 3f).background(colors.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            if (slot.thumbnail != null) {
                Image(slot.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else if (slot.savedAt == null) {
                Text(stringResource(R.string.menu_slot_empty), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }
        }
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(stringResource(R.string.menu_slot, slot.slot), style = MaterialTheme.typography.labelLarge)
            Text(
                slot.savedAt?.let {
                    DateUtils.formatDateTime(
                        context,
                        it,
                        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL,
                    )
                } ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

private val transparentListItem
    @Composable get() = ListItemDefaults.colors(containerColor = Color.Transparent)

@Composable
private fun MenuAction(text: String, enabled: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(text) },
        colors = transparentListItem,
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 8.dp),
    )
}

@Composable
private fun MenuSwitch(text: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(text) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        colors = transparentListItem,
        modifier = Modifier
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 8.dp),
    )
}

@Composable
private fun FastForwardSpeed(speed: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.menu_fast_forward_speed)) },
        trailingContent = {
            // Inside the trailing slot so the menu drops down from the value it changes.
            Box {
                Text(fastForwardSpeedLabel(speed))
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    for (option in FAST_FORWARD_SPEEDS) {
                        DropdownMenuItem(
                            text = { Text(fastForwardSpeedLabel(option)) },
                            onClick = {
                                expanded = false
                                onSelect(option)
                            },
                        )
                    }
                }
            }
        },
        colors = transparentListItem,
        modifier = Modifier.clickable(enabled = enabled) { expanded = true }.padding(horizontal = 8.dp),
    )
}

@Composable
private fun fastForwardSpeedLabel(speed: Int): String =
    if (speed == 0) stringResource(R.string.menu_fast_forward_unlimited) else stringResource(R.string.menu_fast_forward_multiple, speed)
