package com.kira3.scphx.emucore

/**
 * A DualShock 2 button. [code] is the number the core's pad bridge takes for it (Android
 * key codes for the buttons, plus the core's own numbers for stick directions).
 */
enum class PadButton(internal val code: Int) {
    Up(19),
    Down(20),
    Left(21),
    Right(22),
    Cross(96),
    Circle(97),
    Square(99),
    Triangle(100),
    L1(102),
    R1(103),
    L2(104),
    R2(105),
    L3(106),
    R3(107),
    Start(108),
    Select(109),

    /** The Analog (mode) button, which toggles the pad's analog mode. */
    Analog(200),
}

enum class PadStick(
    internal val up: Int,
    internal val right: Int,
    internal val down: Int,
    internal val left: Int,
) {
    Left(up = 110, right = 111, down = 112, left = 113),
    Right(up = 120, right = 121, down = 122, left = 123),
}
