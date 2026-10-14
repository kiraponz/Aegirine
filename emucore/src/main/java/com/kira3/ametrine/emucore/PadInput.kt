package com.kira3.ametrine.emucore

/**
 * A DualShock button. [code] is the core's bind index for it (AnalogController::Button), which
 * the digital pad shares for the buttons it has.
 */
enum class PadButton(internal val code: Int) {
    Up(4),
    Down(6),
    Left(7),
    Right(5),
    Cross(14),
    Circle(13),
    Square(15),
    Triangle(12),
    L1(10),
    R1(11),
    L2(8),
    R2(9),
    L3(1),
    R3(2),
    Start(3),
    Select(0),

    /** The Analog (mode) button, which toggles the pad's analog mode. */
    Analog(16),
}

enum class PadStick {
    Left,
    Right,
}
