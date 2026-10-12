package com.kira3.scphx.emulation.input

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kira3.scphx.R
import com.kira3.scphx.emucore.PadButton
import com.kira3.scphx.emucore.PadStick
import kotlin.math.min
import kotlin.math.roundToInt

/** Where the touch pad sends its input. Called on the UI thread, straight from touch handling. */
interface PadSink {
    fun onButton(button: PadButton, pressed: Boolean)
    fun onStick(stick: PadStick, x: Float, y: Float)
}

/**
 * The on-screen pad, drawn over the game. Each finger is tracked on its own, so several
 * controls can be held at once; a finger can slide between buttons and across the D-pad.
 *
 * While hidden ([visible] false) it draws nothing, and a touch anywhere calls [onShowRequest].
 *
 * [fastForwardButton], if given, is placed where the layout leaves room for it, and stays
 * while the pad is hidden.
 */
@Composable
fun TouchPad(
    sink: PadSink,
    visible: Boolean,
    onShowRequest: () -> Unit,
    modifier: Modifier = Modifier,
    fastForwardButton: (@Composable () -> Unit)? = null,
) {
    val view = LocalView.current
    val tracker = remember(sink) {
        TouchTracker(sink) { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
    }
    DisposableEffect(tracker, visible) {
        onDispose { tracker.releaseAll() }
    }
    val currentOnShowRequest by rememberUpdatedState(onShowRequest)

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.displayCutout),
    ) {
        val maxUnit = with(LocalDensity.current) { MAX_UNIT.toPx() }
        val layout = remember(constraints.maxWidth, constraints.maxHeight, maxUnit) {
            touchPadLayout(
                Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()),
                maxUnit,
            )
        }
        val labels = buttonLabels()
        val textMeasurer = rememberTextMeasurer()

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(layout, visible) {
                    if (visible) {
                        tracker.layout = layout
                        try {
                            awaitPointerEventScope {
                                while (true) tracker.handle(awaitPointerEvent().changes)
                            }
                        } finally {
                            tracker.releaseAll()
                        }
                    } else {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.changes.any { it.changedToDown() }) {
                                    event.changes.forEach { it.consume() }
                                    currentOnShowRequest()
                                }
                            }
                        }
                    }
                },
        ) {
            if (visible) drawPad(layout, tracker, labels, textMeasurer)
        }
        if (fastForwardButton != null) {
            Box(Modifier.centeredAt(layout.fastForward)) { fastForwardButton() }
        }
    }
}

/** Places the content with its center at [center]. */
private fun Modifier.centeredAt(center: Offset): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    layout(placeable.width, placeable.height) {
        placeable.place(
            (center.x - placeable.width / 2f).roundToInt(),
            (center.y - placeable.height / 2f).roundToInt(),
        )
    }
}

@Composable
private fun buttonLabels(): Map<PadButton, String> = mapOf(
    PadButton.L1 to stringResource(R.string.pad_l1),
    PadButton.L2 to stringResource(R.string.pad_l2),
    PadButton.R1 to stringResource(R.string.pad_r1),
    PadButton.R2 to stringResource(R.string.pad_r2),
    PadButton.L3 to stringResource(R.string.pad_l3),
    PadButton.R3 to stringResource(R.string.pad_r3),
    PadButton.Select to stringResource(R.string.pad_select),
    PadButton.Start to stringResource(R.string.pad_start),
)

/** Which control each finger is on, and what the pad as a whole is holding. */
private class TouchTracker(
    private val sink: PadSink,
    private val onPress: () -> Unit,
) {
    var layout: TouchPadLayout? = null

    var pressed by mutableStateOf(emptySet<PadButton>())
        private set
    val sticks = mutableStateMapOf<PadStick, Offset>()

    private val fingers = HashMap<PointerId, Finger>()

    private sealed interface Finger {
        /** On a button; it may slide to another one, or off all of them ([button] null). */
        class OnButton(var button: PadButton?) : Finger
        class OnDPad(val dpad: TouchControl.DPad, var directions: Set<PadButton>) : Finger
        class OnStick(val stick: TouchControl.Stick, var value: Offset) : Finger
    }

    fun handle(changes: List<PointerInputChange>) {
        val controls = layout?.controls ?: return
        for (change in changes) {
            val id = change.id
            when {
                change.changedToDown() -> {
                    fingers[id] = when (val hit = hitTest(controls, change.position) ?: continue) {
                        is TouchControl.Button -> Finger.OnButton(hit.button)
                        is TouchControl.DPad -> Finger.OnDPad(hit, dpadDirections(hit, change.position))
                        is TouchControl.Stick -> Finger.OnStick(hit, stickValue(hit, change.position))
                    }
                    change.consume()
                }

                !change.pressed -> {
                    if (fingers.remove(id) != null) change.consume()
                }

                else -> {
                    when (val finger = fingers[id] ?: continue) {
                        is Finger.OnButton -> finger.button = controls
                            .filterIsInstance<TouchControl.Button>()
                            .firstOrNull { it.contains(change.position) }?.button
                        is Finger.OnDPad ->
                            finger.directions = dpadDirections(finger.dpad, change.position)
                        is Finger.OnStick ->
                            finger.value = stickValue(finger.stick, change.position)
                    }
                    change.consume()
                }
            }
        }
        publish()
    }

    fun releaseAll() {
        fingers.clear()
        publish()
    }

    private fun publish() {
        val nowPressed = buildSet {
            for (finger in fingers.values) when (finger) {
                is Finger.OnButton -> finger.button?.let(::add)
                is Finger.OnDPad -> addAll(finger.directions)
                is Finger.OnStick -> Unit
            }
        }
        if (nowPressed != pressed) {
            for (button in pressed - nowPressed) sink.onButton(button, false)
            val added = nowPressed - pressed
            for (button in added) sink.onButton(button, true)
            pressed = nowPressed
            if (added.isNotEmpty()) onPress()
        }

        for (stick in PadStick.entries) {
            val value = fingers.values.firstNotNullOfOrNull {
                (it as? Finger.OnStick)?.takeIf { f -> f.stick.stick == stick }?.value
            } ?: Offset.Zero
            if (value != (sticks[stick] ?: Offset.Zero)) {
                sticks[stick] = value
                sink.onStick(stick, value.x, value.y)
            }
        }
    }

    private fun hitTest(controls: List<TouchControl>, position: Offset): TouchControl? =
        controls.firstOrNull {
            when (it) {
                is TouchControl.Button -> it.contains(position)
                is TouchControl.DPad -> (position - it.center).getDistance() <= it.radius * 1.15f
                is TouchControl.Stick -> (position - it.center).getDistance() <= it.radius * 1.4f
            }
        }

    private fun TouchControl.Button.contains(position: Offset): Boolean {
        // A little larger than drawn, since a fingertip covers more than its center.
        val slop = min(bounds.width, bounds.height) * 0.2f
        return if (round) {
            (position - bounds.center).getDistance() <= bounds.width / 2 + slop
        } else {
            bounds.inflate(slop).contains(position)
        }
    }

    /** Eight-way: a diagonal covers the 45 degrees between two directions. */
    private fun dpadDirections(dpad: TouchControl.DPad, position: Offset): Set<PadButton> {
        val v = position - dpad.center
        val distance = v.getDistance()
        if (distance < dpad.radius * 0.2f) return emptySet()
        val nx = v.x / distance
        val ny = v.y / distance
        return buildSet {
            if (nx > DIAGONAL) add(PadButton.Right)
            if (nx < -DIAGONAL) add(PadButton.Left)
            if (ny > DIAGONAL) add(PadButton.Down)
            if (ny < -DIAGONAL) add(PadButton.Up)
        }
    }

    private fun stickValue(stick: TouchControl.Stick, position: Offset): Offset {
        val v = (position - stick.center) / stick.radius
        val distance = v.getDistance()
        return if (distance > 1f) v / distance else v
    }

    private companion object {
        /** cos(67.5°): beyond this a direction counts, so diagonals get 45 degrees. */
        const val DIAGONAL = 0.383f
    }
}

private val MAX_UNIT = 56.dp

private val Fill = Color.White.copy(alpha = 0.16f)
private val FillPressed = Color.White.copy(alpha = 0.45f)
private val Outline = Color.White.copy(alpha = 0.5f)
private val Label = Color.White.copy(alpha = 0.85f)

private val TriangleColor = Color(0xFF5FD6C2)
private val CircleColor = Color(0xFFFF7A7A)
private val CrossColor = Color(0xFF8DB8FF)
private val SquareColor = Color(0xFFF59AD8)

private fun DrawScope.drawPad(
    layout: TouchPadLayout,
    tracker: TouchTracker,
    labels: Map<PadButton, String>,
    textMeasurer: TextMeasurer,
) {
    val stroke = Stroke(width = 1.5.dp.toPx())
    val pressed = tracker.pressed
    val labelStyle = TextStyle(
        color = Label,
        fontSize = (layout.unit * 0.3f).toSp(),
        fontWeight = FontWeight.Medium,
    )

    for (control in layout.controls) when (control) {
        is TouchControl.Button -> {
            val fill = if (control.button in pressed) FillPressed else Fill
            val b = control.bounds
            if (control.round) {
                drawCircle(fill, b.width / 2, b.center)
                drawCircle(Outline, b.width / 2, b.center, style = stroke)
            } else {
                val corner = CornerRadius(b.height / 2)
                drawRoundRect(fill, b.topLeft, b.size, corner)
                drawRoundRect(Outline, b.topLeft, b.size, corner, style = stroke)
            }
            val label = labels[control.button]
            if (label != null) {
                val text = textMeasurer.measure(label, labelStyle)
                drawText(
                    text,
                    topLeft = b.center - Offset(text.size.width / 2f, text.size.height / 2f),
                )
            } else {
                drawFaceSymbol(control.button, b.center, b.width * 0.22f)
            }
        }

        is TouchControl.DPad -> drawDPad(control, pressed, stroke)

        is TouchControl.Stick -> {
            val value = tracker.sticks[control.stick] ?: Offset.Zero
            val knobRadius = control.radius * 0.48f
            drawCircle(Fill, control.radius, control.center)
            drawCircle(Outline, control.radius, control.center, style = stroke)
            val knob = control.center + value * (control.radius - knobRadius)
            drawCircle(if (value != Offset.Zero) FillPressed else Fill, knobRadius, knob)
            drawCircle(Outline, knobRadius, knob, style = stroke)
        }
    }
}

private fun DrawScope.drawDPad(dpad: TouchControl.DPad, pressed: Set<PadButton>, stroke: Stroke) {
    val r = dpad.radius
    val arm = r * 0.64f
    val h = arm / 2
    val c = dpad.center
    // Arms and hub don't overlap, so the translucent fill stays even.
    val arms = listOf(
        PadButton.Up to Rect(c.x - h, c.y - r, c.x + h, c.y - h),
        PadButton.Down to Rect(c.x - h, c.y + h, c.x + h, c.y + r),
        PadButton.Left to Rect(c.x - r, c.y - h, c.x - h, c.y + h),
        PadButton.Right to Rect(c.x + h, c.y - h, c.x + r, c.y + h),
    )
    for ((button, rect) in arms) {
        drawRect(if (button in pressed) FillPressed else Fill, rect.topLeft, rect.size)
    }
    drawRect(Fill, Offset(c.x - h, c.y - h), Size(arm, arm))
    // The outline of the whole cross, so the arms read as one D-pad.
    val outline = Path().apply {
        moveTo(c.x - h, c.y - r)
        lineTo(c.x + h, c.y - r)
        lineTo(c.x + h, c.y - h)
        lineTo(c.x + r, c.y - h)
        lineTo(c.x + r, c.y + h)
        lineTo(c.x + h, c.y + h)
        lineTo(c.x + h, c.y + r)
        lineTo(c.x - h, c.y + r)
        lineTo(c.x - h, c.y + h)
        lineTo(c.x - r, c.y + h)
        lineTo(c.x - r, c.y - h)
        lineTo(c.x - h, c.y - h)
        close()
    }
    drawPath(outline, Outline, style = stroke)
}

private fun DrawScope.drawFaceSymbol(button: PadButton, center: Offset, size: Float) {
    val stroke = Stroke(width = size * 0.28f)
    when (button) {
        PadButton.Triangle -> {
            val path = Path().apply {
                moveTo(center.x, center.y - size)
                lineTo(center.x + size * 0.95f, center.y + size * 0.7f)
                lineTo(center.x - size * 0.95f, center.y + size * 0.7f)
                close()
            }
            drawPath(path, TriangleColor, style = stroke)
        }
        PadButton.Circle -> drawCircle(CircleColor, size, center, style = stroke)
        PadButton.Cross -> {
            drawLine(CrossColor, center + Offset(-size, -size), center + Offset(size, size), stroke.width)
            drawLine(CrossColor, center + Offset(size, -size), center + Offset(-size, size), stroke.width)
        }
        PadButton.Square -> {
            val s = size * 0.85f
            drawRect(SquareColor, center - Offset(s, s), Size(s * 2, s * 2), style = stroke)
        }
        else -> Unit
    }
}

@Preview(widthDp = 800, heightDp = 380, backgroundColor = 0xFF202020, showBackground = true)
@Preview(widthDp = 400, heightDp = 860, backgroundColor = 0xFF202020, showBackground = true)
@Composable
private fun TouchPadPreview() {
    TouchPad(
        sink = object : PadSink {
            override fun onButton(button: PadButton, pressed: Boolean) = Unit
            override fun onStick(stick: PadStick, x: Float, y: Float) = Unit
        },
        visible = true,
        onShowRequest = {},
    )
}
