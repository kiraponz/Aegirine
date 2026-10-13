package com.kira3.scphx.emulation

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kira3.scphx.R
import com.kira3.scphx.data.memo.Memo
import kotlin.math.roundToInt

/** What the memo cards can ask for. */
interface MemoActions {
    /** [x] and [y] are fractions of the screen. */
    fun move(memo: Memo, x: Float, y: Float)
    fun setCollapsed(memo: Memo, collapsed: Boolean)
    fun edit(memo: Memo)
    fun delete(memo: Memo)
}

/**
 * The memos, as cards over the game. Only the cards take touches: everywhere else the touch goes
 * through to the pad. A card is moved by dragging its title bar.
 */
@Composable
fun MemoOverlay(memos: List<Memo>, actions: MemoActions, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize().safeDrawingPadding()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        memos.forEach { memo ->
            key(memo.id) { MemoCard(memo, width, height, actions) }
        }
    }
}

@Composable
private fun MemoCard(memo: Memo, areaWidth: Float, areaHeight: Float, actions: MemoActions) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var position by remember(memo.x, memo.y, areaWidth, areaHeight) { mutableStateOf(Offset(memo.x * areaWidth, memo.y * areaHeight)) }
    var confirmDelete by remember { mutableStateOf(false) }

    // Kept on the screen, also after a rotation or when the card grows.
    val clamped = Offset(
        position.x.coerceIn(0f, (areaWidth - size.width).coerceAtLeast(0f)),
        position.y.coerceIn(0f, (areaHeight - size.height).coerceAtLeast(0f)),
    )
    Surface(
        shape = MaterialTheme.shapes.medium,
        // Translucent so the game shows through; the content colour is set outright because
        // a translucent surface colour would make contentColorFor pick an unreadable one.
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .offset { IntOffset(clamped.x.roundToInt(), clamped.y.roundToInt()) }
            .width(CARD_WIDTH)
            .onSizeChanged { size = it },
    ) {
        Column {
            Row(
                Modifier
                    .pointerInput(memo, areaWidth, areaHeight, size) {
                        detectDragGestures(
                            onDragEnd = { actions.move(memo, position.x / areaWidth, position.y / areaHeight) },
                            onDragCancel = { actions.move(memo, position.x / areaWidth, position.y / areaHeight) },
                        ) { change, drag ->
                            change.consume()
                            position = Offset(
                                (position.x + drag.x).coerceIn(0f, (areaWidth - size.width).coerceAtLeast(0f)),
                                (position.y + drag.y).coerceIn(0f, (areaHeight - size.height).coerceAtLeast(0f)),
                            )
                        }
                    }
                    .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    memo.question.ifBlank { memo.text },
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { actions.setCollapsed(memo, !memo.collapsed) }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        if (memo.collapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                        contentDescription = stringResource(if (memo.collapsed) R.string.memo_expand else R.string.memo_collapse),
                    )
                }
                IconButton(onClick = { actions.edit(memo) }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.memo_edit))
                }
                IconButton(onClick = { confirmDelete = true }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.memo_delete))
                }
            }
            if (!memo.collapsed) {
                Text(
                    memo.text,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.memo_delete_title)) },
            text = { Text(memo.question.ifBlank { memo.text }, maxLines = 3, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    actions.delete(memo)
                }) { Text(stringResource(R.string.memo_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.menu_cancel)) }
            },
        )
    }
}

/** Writes a memo by hand, or changes [initial]: a title and the text. */
@Composable
fun MemoDialog(initial: Memo?, onSave: (title: String, text: String) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf(initial?.question.orEmpty()) }
    var text by remember { mutableStateOf(initial?.text.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.memo_add_title else R.string.memo_edit_title)) },
        text = {
            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    title,
                    { title = it },
                    label = { Text(stringResource(R.string.memo_title_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    text,
                    { text = it },
                    label = { Text(stringResource(R.string.memo_text_label)) },
                    minLines = 4,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onSave(title.trim(), text.trim()) }) {
                Text(stringResource(R.string.macro_condition_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.menu_cancel)) } },
    )
}

private val CARD_WIDTH = 240.dp
