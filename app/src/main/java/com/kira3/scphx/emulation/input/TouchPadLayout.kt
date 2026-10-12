package com.kira3.scphx.emulation.input

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.kira3.scphx.emucore.PadButton
import com.kira3.scphx.emucore.PadStick
import kotlin.math.min

/** One control on the touch pad, in pixels of the pad's area. */
internal sealed interface TouchControl {
    /** A button, round when [round] and pill-shaped otherwise. */
    data class Button(val button: PadButton, val bounds: Rect, val round: Boolean) : TouchControl

    data class DPad(val center: Offset, val radius: Float) : TouchControl

    data class Stick(val stick: PadStick, val center: Offset, val radius: Float) : TouchControl
}

/**
 * [fastForward], [rewind], [screenshot] and [macro] are the centers of the buttons the pad does not draw itself. Rewind
 * is kept well away from the shoulder buttons, so it is not pressed by a slip.
 */
internal class TouchPadLayout(
    val unit: Float,
    val controls: List<TouchControl>,
    val fastForward: Offset,
    val rewind: Offset,
    val screenshot: Offset,
    val macro: Offset,
) {
    /** The same pad without the drawn sticks, for sticks that appear under a finger. */
    fun withoutSticks() = TouchPadLayout(unit, controls.filter { it !is TouchControl.Stick }, fastForward, rewind, screenshot, macro)
}

/**
 * Places the controls for an area of [size]. Everything is sized from one unit, so the pad
 * scales with the screen; [maxUnit] keeps it from growing huge on tablets.
 *
 * Landscape puts the clusters on either side of the picture, as on a DualShock 2. Portrait
 * stacks them under the picture, which the core draws at the top of the screen.
 */
internal fun touchPadLayout(size: Size, maxUnit: Float): TouchPadLayout =
    if (size.width >= size.height) landscapeLayout(size, maxUnit) else portraitLayout(size, maxUnit)

private fun landscapeLayout(size: Size, maxUnit: Float): TouchPadLayout {
    val (w, h) = size
    val u = min(min(h / 6.5f, w / 14f), maxUnit)
    val m = 0.35f * u
    val clusterY = h - m - 3.2f * u
    val stickY = h - m - 1.2f * u
    val shoulder = Size(1.7f * u, 0.85f * u)

    return TouchPadLayout(
        u,
        buildList {
            add(pill(PadButton.L2, Offset(m, m), shoulder))
            add(pill(PadButton.L1, Offset(m + 1.9f * u, m), shoulder))
            add(pill(PadButton.R1, Offset(w - m - 1.9f * u - shoulder.width, m), shoulder))
            add(pill(PadButton.R2, Offset(w - m - shoulder.width, m), shoulder))

            add(TouchControl.DPad(Offset(m + 1.5f * u, clusterY), 1.45f * u))
            addAll(faceButtons(Offset(w - m - 1.5f * u, clusterY), u))

            add(TouchControl.Stick(PadStick.Left, Offset(m + 3.7f * u, stickY), 1.15f * u))
            add(TouchControl.Stick(PadStick.Right, Offset(w - m - 3.7f * u, stickY), 1.15f * u))
            add(round(PadButton.L3, Offset(m + 0.55f * u, h - m - 0.55f * u), 0.42f * u))
            add(round(PadButton.R3, Offset(w - m - 0.55f * u, h - m - 0.55f * u), 0.42f * u))

            addAll(selectStart(Offset(w / 2, h - m - 0.35f * u), u))
        },
        fastForward = Offset(w / 2, m + shoulder.height / 2),
        // Below L2: nothing fits above it.
        rewind = Offset(m + shoulder.width / 2, m + shoulder.height + 1.6f * u),
        // Below R2, mirroring rewind.
        screenshot = Offset(w - m - shoulder.width / 2, m + shoulder.height + 1.6f * u),
        // Beside rewind, under L1.
        macro = Offset(m + shoulder.width / 2 + 1.4f * u, m + shoulder.height + 1.6f * u),
    )
}

private fun portraitLayout(size: Size, maxUnit: Float): TouchPadLayout {
    val (w, h) = size
    val u = min(w / 9.6f, maxUnit)
    val m = 0.35f * u
    val top = h - m - 7.65f * u
    val clusterY = top + 3.55f * u
    val stickY = top + 6.45f * u
    val shoulder = Size(1.7f * u, 0.8f * u)
    val shoulderGap = 0.95f * u

    return TouchPadLayout(
        u,
        buildList {
            add(pill(PadButton.L2, Offset(m, top), shoulder))
            add(pill(PadButton.L1, Offset(m, top + shoulderGap), shoulder))
            add(pill(PadButton.R2, Offset(w - m - shoulder.width, top), shoulder))
            add(pill(PadButton.R1, Offset(w - m - shoulder.width, top + shoulderGap), shoulder))
            addAll(selectStart(Offset(w / 2, top + 0.6f * u), u))

            add(TouchControl.DPad(Offset(m + 1.5f * u, clusterY), 1.45f * u))
            addAll(faceButtons(Offset(w - m - 1.5f * u, clusterY), u))

            add(TouchControl.Stick(PadStick.Left, Offset(m + 2.6f * u, stickY), 1.15f * u))
            add(TouchControl.Stick(PadStick.Right, Offset(w - m - 2.6f * u, stickY), 1.15f * u))
            add(round(PadButton.L3, Offset(m + 0.5f * u, stickY + 0.75f * u), 0.42f * u))
            add(round(PadButton.R3, Offset(w - m - 0.5f * u, stickY + 0.75f * u), 0.42f * u))
        },
        // Under Select/Start, between the D-pad and the face buttons.
        fastForward = Offset(w / 2, top + 1.6f * u),
        // Above L2, in the empty space under the picture.
        rewind = Offset(m + shoulder.width / 2, top - 1.6f * u),
        // Above R2, mirroring rewind.
        screenshot = Offset(w - m - shoulder.width / 2, top - 1.6f * u),
        // Beside rewind.
        macro = Offset(m + shoulder.width / 2 + 1.4f * u, top - 1.6f * u),
    )
}

private fun faceButtons(center: Offset, u: Float): List<TouchControl> {
    val d = 0.92f * u
    val r = 0.58f * u
    return listOf(
        round(PadButton.Triangle, center + Offset(0f, -d), r),
        round(PadButton.Circle, center + Offset(d, 0f), r),
        round(PadButton.Cross, center + Offset(0f, d), r),
        round(PadButton.Square, center + Offset(-d, 0f), r),
    )
}

private fun selectStart(center: Offset, u: Float): List<TouchControl> {
    val size = Size(1.3f * u, 0.55f * u)
    val dx = 0.8f * u
    return listOf(
        pill(PadButton.Select, center + Offset(-dx - size.width / 2, -size.height / 2), size),
        pill(PadButton.Start, center + Offset(dx - size.width / 2, -size.height / 2), size),
    )
}

private fun pill(button: PadButton, topLeft: Offset, size: Size) =
    TouchControl.Button(button, Rect(topLeft, size), round = false)

private fun round(button: PadButton, center: Offset, radius: Float) =
    TouchControl.Button(button, Rect(center, radius), round = true)
