package com.kira3.ametrine.emulation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kira3.ametrine.R

/** Switches between fast-forward and normal speed. Drawn over the game like the touch pad. */
@Composable
fun FastForwardButton(
    active: Boolean,
    onActiveChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (active) FillActive else Fill)
            .border(1.5.dp, Outline, CircleShape)
            .toggleable(value = active, role = Role.Switch, onValueChange = onActiveChange),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_fast_forward),
            contentDescription = stringResource(R.string.emulation_fast_forward),
            tint = Color.White.copy(alpha = if (active) 1f else 0.85f),
            modifier = Modifier.padding(10.dp),
        )
    }
}

// The touch pad's colors.
private val Fill = Color.White.copy(alpha = 0.16f)
private val FillActive = Color.White.copy(alpha = 0.45f)
private val Outline = Color.White.copy(alpha = 0.5f)
