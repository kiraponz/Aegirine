package com.kira3.scphx.emulation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kira3.scphx.R
import com.kira3.scphx.emulation.search.MemorySearch
import com.kira3.scphx.emulation.search.MemorySearch.Mode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Finds the address of a number in the game's memory (a character's HP, say): scan for the value,
 * play until it changes, scan again with the new value. The state outlives the panel, so it can be
 * closed to let the game run.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemorySearchMenu(search: MemorySearch, onClose: () -> Unit) {
    val state = search.state
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val text = search.valueText
    val mode = search.mode
    val value = text.trim().toLongOrNull()?.takeIf { it >= 0 }

    // Live values of the listed addresses, while the panel is up (they move once the game runs).
    LaunchedEffect(state.hits.size) {
        while (state.hits.isNotEmpty()) {
            delay(500)
            search.refresh()
        }
    }

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
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .clickable(interactionSource = null, indication = null) {},
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(vertical = 16.dp, horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.search_title),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onClose) { Text(stringResource(R.string.menu_resume)) }
                }
                Text(
                    stringResource(if (state.started) R.string.search_hint_next else R.string.search_hint_first),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (size in listOf(1, 2, 4)) {
                        FilterChip(
                            selected = state.size == size,
                            onClick = { search.setSize(size) },
                            enabled = !state.started && !state.busy,
                            label = { Text(stringResource(R.string.search_size, size)) },
                        )
                    }
                }
                if (state.started) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (m in Mode.entries) {
                            FilterChip(
                                selected = mode == m,
                                onClick = { search.mode = m },
                                label = { Text(stringResource(modeLabel(m))) },
                            )
                        }
                    }
                }
                if (!state.started || mode == Mode.Exact) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { search.valueText = it.filter(Char::isDigit).take(10) },
                        label = { Text(stringResource(R.string.search_value)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    if (!state.started) {
                        Button(
                            onClick = { scope.launch { search.first(value) } },
                            enabled = value != null && !state.busy,
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.search_first)) }
                        OutlinedButton(
                            onClick = { scope.launch { search.first(null) } },
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.search_unknown)) }
                    } else {
                        Button(
                            onClick = { scope.launch { search.next(mode, value) } },
                            enabled = (mode != Mode.Exact || value != null) && !state.busy,
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.search_next)) }
                        OutlinedButton(
                            onClick = search::reset,
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.search_reset)) }
                    }
                }
                when {
                    state.busy -> Text(stringResource(R.string.search_busy), style = MaterialTheme.typography.bodyMedium)
                    state.problem == MemorySearch.Problem.NoGame ->
                        Text(stringResource(R.string.search_no_game), color = MaterialTheme.colorScheme.error)
                    state.problem == MemorySearch.Problem.TooMany ->
                        Text(stringResource(R.string.search_too_many), color = MaterialTheme.colorScheme.error)
                    state.started -> Text(
                        stringResource(R.string.search_count, state.count),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                for (hit in state.hits) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { copyAddress(context, hit.address) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "%08X".format(hit.address),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Text(hit.value.toString(), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (state.hits.isNotEmpty()) {
                    Text(
                        stringResource(R.string.search_tap_to_copy),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun modeLabel(mode: Mode) = when (mode) {
    Mode.Exact -> R.string.search_mode_exact
    Mode.Changed -> R.string.search_mode_changed
    Mode.Unchanged -> R.string.search_mode_unchanged
    Mode.Increased -> R.string.search_mode_increased
    Mode.Decreased -> R.string.search_mode_decreased
}

private fun copyAddress(context: Context, address: Int) {
    val text = "%08X".format(address)
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("address", text))
    Toast.makeText(context, context.getString(R.string.search_copied, text), Toast.LENGTH_SHORT).show()
}
