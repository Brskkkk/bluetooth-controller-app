package com.idk.touchoverlay

/**
 * Configuration constants for Bluetooth HID mode.
 */
object BluetoothHIDConfig {

    // HID Report timing
    const val HID_REPORT_INTERVAL_MS = 16L  // ~60Hz
    const val GYRO_SAMPLE_RATE_US = 10000   // 100Hz

    // Device info
    const val DEVICE_NAME = "Bluetooth Gamepad"
    const val DEVICE_CLASS = 0x2508  // Gamepad (0x05) under Game Controller (0x25)
    const val VENDOR_ID = 0x1234
    const val PRODUCT_ID = 0x5678
    const val DEVICE_VERSION = 0x0200

    // HID Profile UUIDs
    val HID_SERVICE_UUID = "00001812-0000-1000-8000-00805F9B34FB"  // HID over GATT Profile
    val BATTERY_SERVICE_UUID = "0000180F-0000-1000-8000-00805F9B34FB"
    val DEVICE_INFO_UUID = "0000180A-0000-1000-8000-00805F9B34FB"

    // Default deadzone levels (as percentage)
    const val DEFAULT_STICK_DEADZONE_PERCENT = 0.08f  // 8%
    const val MIN_DEADZONE_PERCENT = 0.0f
    const val MAX_DEADZONE_PERCENT = 0.5f

    // Gyro sensitivity
    const val DEFAULT_GYRO_SENSITIVITY = 1.0f
    const val MIN_GYRO_SENSITIVITY = 0.5f
    const val MAX_GYRO_SENSITIVITY = 3.0f

    // Stick sensitivity
    const val DEFAULT_STICK_SENSITIVITY = 1.0f
    const val STICK_DEADZONE_CURVE = "linear"  // or "quadratic" for smoothness

    // Trigger sensitivity
    const val DEFAULT_TRIGGER_SENSITIVITY = 1.0f

    /**
     * HID Report Descriptor for standard gamepad.
     * Includes all axes and buttons that Android games expect.
     */
    val HID_REPORT_DESCRIPTOR = byteArrayOf(
        0x05.toByte(), 0x01.toByte(), // USAGE_PAGE (Generic Desktop)
        0x09.toByte(), 0x05.toByte(), // USAGE (Game Pad)
        0xA1.toByte(), 0x01.toByte(), // COLLECTION (Application)

        // Left Stick X (AXIS_X)
        0x09.toByte(), 0x30.toByte(), // USAGE (X)
        0x15.toByte(), 0x81.toByte(), // LOGICAL_MINIMUM (-127)
        0x25.toByte(), 0x7F.toByte(), // LOGICAL_MAXIMUM (127)
        0x95.toByte(), 0x01.toByte(), // REPORT_COUNT (1)
        0x75.toByte(), 0x08.toByte(), // REPORT_SIZE (8)
        0x81.toByte(), 0x02.toByte(), // INPUT (Data, Var, Abs)

        // Left Stick Y (AXIS_Y)
        0x09.toByte(), 0x31.toByte(), // USAGE (Y)
        0x81.toByte(), 0x02.toByte(), // INPUT (Data, Var, Abs)

        // Right Stick X (AXIS_Z)
        0x09.toByte(), 0x32.toByte(), // USAGE (Z)
        0x81.toByte(), 0x02.toByte(), // INPUT (Data, Var, Abs)

        // Right Stick Y (AXIS_RZ)
        0x09.toByte(), 0x35.toByte(), // USAGE (Rz)
        0x81.toByte(), 0x02.toByte(), // INPUT (Data, Var, Abs)

        // D-Pad (HAT Switch)
        0x09.toByte(), 0x39.toByte(), // USAGE (Hat Switch)
        0x15.toByte(), 0x00.toByte(), // LOGICAL_MINIMUM (0)
        0x25.toByte(), 0x07.toByte(), // LOGICAL_MAXIMUM (7)
        0x75.toByte(), 0x04.toByte(), // REPORT_SIZE (4)
        0x95.toByte(), 0x01.toByte(), // REPORT_COUNT (1)
        0x81.toByte(), 0x02.toByte(), // INPUT (Data, Var, Abs)

        // Padding for D-Pad (4 bits unused)
        0x75.toByte(), 0x04.toByte(), // REPORT_SIZE (4)
        0x95.toByte(), 0x01.toByte(), // REPORT_COUNT (1)
        0x81.toByte(), 0x01.toByte(), // INPUT (Cnst, Var, Abs)

        // Buttons (A, B, X, Y, LB, RB, Start, Select = 8 bits)
        0x05.toByte(), 0x09.toByte(), // USAGE_PAGE (Button)
        0x19.toByte(), 0x01.toByte(), // USAGE_MINIMUM (Button 1)
        0x29.toByte(), 0x08.toByte(), // USAGE_MAXIMUM (Button 8)
        0x15.toByte(), 0x00.toByte(), // LOGICAL_MINIMUM (0)
        0x25.toByte(), 0x01.toByte(), // LOGICAL_MAXIMUM (1)
        0x75.toByte(), 0x01.toByte(), // REPORT_SIZE (1)
        0x95.toByte(), 0x08.toByte(), // REPORT_COUNT (8)
        0x81.toByte(), 0x02.toByte(), // INPUT (Data, Var, Abs)

        // Triggers (Analog L/R)
        0x05.toByte(), 0x01.toByte(), // USAGE_PAGE (Generic Desktop)
        0x09.toByte(), 0x32.toByte(), // USAGE (Z) - Left Trigger
        0x15.toByte(), 0x00.toByte(), // LOGICAL_MINIMUM (0)
        0x25.toByte(), 0xFF.toByte(), // LOGICAL_MAXIMUM (255)
        0x75.toByte(), 0x08.toByte(), // REPORT_SIZE (8)
        0x95.toByte(), 0x02.toByte(), // REPORT_COUNT (2)
        0x81.toByte(), 0x02.toByte(), // INPUT (Data, Var, Abs)

        0xC0.toByte() // END_COLLECTION
    )

    /**
     * Map Android KeyCode to HID button index.
     */
    fun keyCodeToHIDButton(keyCode: Int): Int? = when (keyCode) {
        android.view.KeyEvent.KEYCODE_BUTTON_A -> HIDInputHandler.BUTTON_A
        android.view.KeyEvent.KEYCODE_BUTTON_B -> HIDInputHandler.BUTTON_B
        android.view.KeyEvent.KEYCODE_BUTTON_X -> HIDInputHandler.BUTTON_X
        android.view.KeyEvent.KEYCODE_BUTTON_Y -> HIDInputHandler.BUTTON_Y
        android.view.KeyEvent.KEYCODE_BUTTON_L1 -> HIDInputHandler.BUTTON_LB
        android.view.KeyEvent.KEYCODE_BUTTON_R1 -> HIDInputHandler.BUTTON_RB
        android.view.KeyEvent.KEYCODE_BUTTON_SELECT -> HIDInputHandler.BUTTON_BACK
        android.view.KeyEvent.KEYCODE_BUTTON_START -> HIDInputHandler.BUTTON_START
        else -> null
    }
}
