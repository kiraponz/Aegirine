package com.kira3.ametrine.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.selection.selectable

/** One disc in a [DiscSheet]; [number] is its place in the set, counted from 1. */
class DiscItem(val number: Int, val title: String, val current: Boolean)

/** A bottom sheet of cards, one per disc, the current one marked with [currentLabel]. Tapping a card picks it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscSheet(
    title: String,
    discs: List<DiscItem>,
    currentLabel: String,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp, bottom = 12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                discs.forEachIndexed { i, disc -> DiscCard(disc, currentLabel) { onPick(i) } }
            }
        }
    }
}

@Composable
private fun DiscCard(disc: DiscItem, currentLabel: String, onClick: () -> Unit) {
    val container = if (disc.current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val content = if (disc.current) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = container,
        contentColor = content,
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).selectable(selected = disc.current, role = Role.Button, onClick = onClick),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            DiscBadge(disc.number, disc.current)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(disc.title, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (disc.current) {
                    Text(currentLabel, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** A disc: a ring with its number in the middle and a hole in the hub's colour. */
@Composable
private fun DiscBadge(number: Int, current: Boolean) {
    val ring = if (current) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.secondaryContainer
    val onRing = if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Box(Modifier.size(48.dp).clip(CircleShape).background(ring), contentAlignment = Alignment.Center) {
        Text(number.toString(), color = onRing, fontSize = 22.sp, style = MaterialTheme.typography.titleLarge)
    }
}
