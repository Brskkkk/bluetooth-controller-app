package com.idk.touchoverlay

import kotlin.math.abs

/**
 * Converts ControlOverlayView state into HID gamepad reports.
 * HID Report format (12 bytes):
 *   [0] Left Stick X (-127..127)
 *   [1] Left Stick Y (-127..127)
 *   [2] Right Stick X (-127..127)
 *   [3] Right Stick Y (-127..127)
 *   [4] D-Pad (bits 0-3) + Padding (bits 4-7)
 *   [5] Buttons (bits 0-7: A, B, X, Y, LB, RB, Back, Start)
 *   [6] Left Trigger (0..255)
 *   [7] Right Trigger (0..255)
 */
class HIDInputHandler {

    // Current HID report state
    private var leftStickX = 0
    private var leftStickY = 0
    private var rightStickX = 0
    private var rightStickY = 0
    private var dPad = 0
    private var buttons = 0
    private var leftTrigger = 0
    private var rightTrigger = 0

    /**
     * Build a HID gamepad report from current state.
     * Returns 12-byte array suitable for Bluetooth HID transmission.
     */
    fun buildReport(): ByteArray = byteArrayOf(
        leftStickX.toByte(),
        leftStickY.toByte(),
        rightStickX.toByte(),
        rightStickY.toByte(),
        dPad.toByte(),
        buttons.toByte(),
        leftTrigger.toByte(),
        rightTrigger.toByte(),
        0,  // Padding
        0,  // Padding
        0,  // Padding
        0   // Padding
    )

    /**
     * Update left stick position (-1.0 to 1.0).
     */
    fun setLeftStick(x: Float, y: Float) {
        leftStickX = (x * 127).toInt().coerceIn(-127, 127)
        leftStickY = (y * 127).toInt().coerceIn(-127, 127)
    }

    /**
     * Update right stick position (-1.0 to 1.0).
     */
    fun setRightStick(x: Float, y: Float) {
        rightStickX = (x * 127).toInt().coerceIn(-127, 127)
        rightStickY = (y * 127).toInt().coerceIn(-127, 127)
    }

    /**
     * Set D-pad direction.
     * Hat value: 0=Up, 1=UpRight, 2=Right, 3=DownRight, 4=Down, 5=DownLeft, 6=Left, 7=UpLeft, 8=Neutral
     */
    fun setDPad(hatValue: Int) {
        dPad = (hatValue and 0x0F)
    }

    /**
     * Set individual button state.
     * buttonIndex: 0=A, 1=B, 2=X, 3=Y, 4=LB, 5=RB, 6=Back, 7=Start
     */
    fun setButtonPressed(buttonIndex: Int, pressed: Boolean) {
        if (pressed) {
            buttons = buttons or (1 shl buttonIndex)
        } else {
            buttons = buttons and (1 shl buttonIndex).inv()
        }
    }

    /**
     * Set all buttons at once (8-bit mask).
     */
    fun setButtons(mask: Int) {
        buttons = mask and 0xFF
    }

    /**
     * Set left trigger (0.0 to 1.0).
     */
    fun setLeftTrigger(value: Float) {
        leftTrigger = (value * 255).toInt().coerceIn(0, 255)
    }

    /**
     * Set right trigger (0.0 to 1.0).
     */
    fun setRightTrigger(value: Float) {
        rightTrigger = (value * 255).toInt().coerceIn(0, 255)
    }

    /**
     * Reset all input to neutral/unpressed state.
     */
    fun reset() {
        leftStickX = 0
        leftStickY = 0
        rightStickX = 0
        rightStickY = 0
        dPad = 8  // Neutral
        buttons = 0
        leftTrigger = 0
        rightTrigger = 0
    }

    /**
     * Deadzone filter: returns 0 if magnitude is below deadzone threshold.
     */
    fun applyDeadzone(value: Float, deadzonePercent: Float): Float {
        val deadzone = deadzonePercent.coerceIn(0f, 1f)
        return if (abs(value) < deadzone) 0f else value
    }

    /**
     * Convert gamepad hat axis values to HID hat switch value.
     * hatX: -1 (left) to 1 (right)
     * hatY: -1 (up) to 1 (down)
     */
    fun hatAxisToValue(hatX: Float, hatY: Float): Int {
        val hatXInt = when {
            hatX > 0.5f -> 1   // Right
            hatX < -0.5f -> -1 // Left
            else -> 0           // Neutral
        }
        val hatYInt = when {
            hatY > 0.5f -> 1   // Down
            hatY < -0.5f -> -1 // Up
            else -> 0           // Neutral
        }

        return when (Pair(hatXInt, hatYInt)) {
            Pair(0, -1) -> 0   // Up
            Pair(1, -1) -> 1   // UpRight
            Pair(1, 0) -> 2    // Right
            Pair(1, 1) -> 3    // DownRight
            Pair(0, 1) -> 4    // Down
            Pair(-1, 1) -> 5   // DownLeft
            Pair(-1, 0) -> 6   // Left
            Pair(-1, -1) -> 7  // UpLeft
            else -> 8          // Neutral
        }
    }

    companion object {
        // Button indices
        const val BUTTON_A = 0
        const val BUTTON_B = 1
        const val BUTTON_X = 2
        const val BUTTON_Y = 3
        const val BUTTON_LB = 4
        const val BUTTON_RB = 5
        const val BUTTON_BACK = 6
        const val BUTTON_START = 7
    }
}
