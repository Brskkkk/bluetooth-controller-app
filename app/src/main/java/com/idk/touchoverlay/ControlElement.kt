package com.idk.touchoverlay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class ElementType {
    TOUCH_BUTTON,     // relays a tap at this element's own screen position - for games with their own on-screen controls
    GAMEPAD_BUTTON,   // sends a true SOURCE_GAMEPAD button press - recognized by games/emulators with real controller support
    DPAD,             // 4-direction compound widget, sends true DPAD_UP/DOWN/LEFT/RIGHT gamepad keys
    JOYSTICK_TOUCH,   // touch-relay joystick: drags a touch point within the game's own on-screen joystick control
    JOYSTICK_GAMEPAD, // true analog stick axis (left or right, see isRightStick)
    TRACKPAD,         // relative-motion mouse cursor + tap-to-click, like a laptop trackpad
    STOP_OVERLAY      // editable/movable/resizable like any other button, but tapping it just stops the overlay service
}

data class ControlElement(
    val id: String,
    val type: ElementType,
    var xPercent: Float,       // element's anchor position, 0..1 of screen width
    var yPercent: Float,       // 0..1 of screen height
    var scale: Float = 1f,     // size multiplier, adjustable via pinch-to-zoom in edit mode
    var label: String = "",
    var keyCode: Int = 0,      // for GAMEPAD_BUTTON
    var isRightStick: Boolean = false, // for JOYSTICK_GAMEPAD: false = left stick (AXIS_X/Y), true = right stick (AXIS_Z/RZ)
    var deadzonePercent: Float = 0.08f,  // 8% default - for JOYSTICK_* and TRACKPAD-as-drag
    var sensitivity: Float = 1.5f,       // TRACKPAD only: relative-motion multiplier
    var radiusDp: Float = 60f            // interactive radius for JOYSTICK_*/TRACKPAD/DPAD
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type.name)
        put("xPercent", xPercent)
        put("yPercent", yPercent)
        put("scale", scale)
        put("label", label)
        put("keyCode", keyCode)
        put("isRightStick", isRightStick)
        put("deadzonePercent", deadzonePercent)
        put("sensitivity", sensitivity)
        put("radiusDp", radiusDp)
    }

    companion object {
        fun fromJson(o: JSONObject): ControlElement = ControlElement(
            id = o.getString("id"),
            type = ElementType.valueOf(o.getString("type")),
            xPercent = o.getDouble("xPercent").toFloat(),
            yPercent = o.getDouble("yPercent").toFloat(),
            scale = o.optDouble("scale", 1.0).toFloat(),
            label = o.optString("label", ""),
            keyCode = o.optInt("keyCode", 0),
            isRightStick = o.optBoolean("isRightStick", false),
            deadzonePercent = o.optDouble("deadzonePercent", 0.08).toFloat(),
            sensitivity = o.optDouble("sensitivity", 1.5).toFloat(),
            radiusDp = o.optDouble("radiusDp", 60.0).toFloat()
        )
    }
}

object ControlsRepository {
    private const val PREFS = "touchoverlay_layout"
    private const val KEY_LAYOUT = "layout_json"
    private const val KEY_GYRO_ENABLED = "gyro_enabled"

    fun load(ctx: Context): MutableList<ControlElement> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAYOUT, null)
            ?: return defaultLayout()
        return try {
            val arr = JSONArray(raw)
            val list = MutableList(arr.length()) { i -> ControlElement.fromJson(arr.getJSONObject(i)) }
            ensureStopButton(ensureGuideButton(list))
        } catch (t: Throwable) {
            defaultLayout()
        }
    }

    /** Layouts saved before GUIDE existed won't have it - add it so upgrades still get an Xbox-style mode button. */
    private fun ensureGuideButton(list: MutableList<ControlElement>): MutableList<ControlElement> {
        if (list.none { it.keyCode == android.view.KeyEvent.KEYCODE_BUTTON_MODE }) {
            list.add(
                ControlElement("btn_guide", ElementType.GAMEPAD_BUTTON, 0.55f, 0.84f, label = "GUIDE", keyCode = android.view.KeyEvent.KEYCODE_BUTTON_MODE)
            )
        }
        return list
    }

    /** Layouts saved before STOP_OVERLAY existed won't have one - add it so upgrades still get a stop button. */
    private fun ensureStopButton(list: MutableList<ControlElement>): MutableList<ControlElement> {
        if (list.none { it.type == ElementType.STOP_OVERLAY }) {
            list.add(
                ControlElement("stop_overlay", ElementType.STOP_OVERLAY, 0.5f, 0.04f, label = "STOP", radiusDp = 40f)
            )
        }
        return list
    }

    fun isGyroEnabled(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_GYRO_ENABLED, false)

    fun setGyroEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_GYRO_ENABLED, enabled)
            .apply()
    }

    fun exportLayout(ctx: Context): String? {
        return try {
            val externalRoot = android.os.Environment.getExternalStorageDirectory()
            val file = java.io.File(externalRoot, "OverlayControls/TouchOverlayLayout.json")
            file.parentFile?.mkdirs()
            file.writeText(JSONArray(load(ctx).map { it.toJson() }).toString(2))
            file.absolutePath
        } catch (t: Throwable) {
            null
        }
    }

    fun loadFromFile(ctx: Context, path: String): Boolean {
        return try {
            val file = java.io.File(path)
            if (!file.exists()) return false
            val raw = file.readText()
            val arr = JSONArray(raw)
            val list = MutableList(arr.length()) { i -> ControlElement.fromJson(arr.getJSONObject(i)) }
            val normalized = ensureStopButton(ensureGuideButton(list))
            save(ctx, normalized)
            true
        } catch (t: Throwable) {
            false
        }
    }

    fun save(ctx: Context, elements: List<ControlElement>) {
        val arr = JSONArray()
        elements.forEach { arr.put(it.toJson()) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAYOUT, arr.toString())
            .apply()
    }

    /** Applies the same deadzone to both analog sticks (JOYSTICK_GAMEPAD), independent of edit mode. */
    fun setStickDeadzone(ctx: Context, deadzonePercent: Float) {
        val list = load(ctx)
        list.forEach { if (it.type == ElementType.JOYSTICK_GAMEPAD) it.deadzonePercent = deadzonePercent }
        save(ctx, list)
    }

    /** A reasonable starting layout: face buttons, D-pad, both joysticks, shoulder buttons - all as flat, independently-movable elements, not grouped. */
    fun defaultLayout(): MutableList<ControlElement> = mutableListOf(
        ControlElement("btn_a", ElementType.GAMEPAD_BUTTON, 0.90f, 0.75f, label = "A", keyCode = android.view.KeyEvent.KEYCODE_BUTTON_A),
        ControlElement("btn_b", ElementType.GAMEPAD_BUTTON, 0.95f, 0.65f, label = "B", keyCode = android.view.KeyEvent.KEYCODE_BUTTON_B),
        ControlElement("btn_x", ElementType.GAMEPAD_BUTTON, 0.85f, 0.65f, label = "X", keyCode = android.view.KeyEvent.KEYCODE_BUTTON_X),
        ControlElement("btn_y", ElementType.GAMEPAD_BUTTON, 0.90f, 0.55f, label = "Y", keyCode = android.view.KeyEvent.KEYCODE_BUTTON_Y),
        ControlElement("dpad", ElementType.DPAD, 0.12f, 0.75f, label = "D-Pad"),
        ControlElement("stick_left", ElementType.JOYSTICK_GAMEPAD, 0.12f, 0.55f, label = "L", isRightStick = false, radiusDp = 70f),
        ControlElement("stick_right", ElementType.JOYSTICK_GAMEPAD, 0.80f, 0.85f, label = "R", isRightStick = true, radiusDp = 70f),
        ControlElement("btn_l1", ElementType.GAMEPAD_BUTTON, 0.08f, 0.15f, label = "L1", keyCode = android.view.KeyEvent.KEYCODE_BUTTON_L1),
        ControlElement("btn_r1", ElementType.GAMEPAD_BUTTON, 0.92f, 0.15f, label = "R1", keyCode = android.view.KeyEvent.KEYCODE_BUTTON_R1),
        ControlElement("btn_l2", ElementType.GAMEPAD_BUTTON, 0.18f, 0.07f, label = "L2", keyCode = android.view.KeyEvent.KEYCODE_BUTTON_L2),
        ControlElement("btn_r2", ElementType.GAMEPAD_BUTTON, 0.82f, 0.07f, label = "R2", keyCode = android.view.KeyEvent.KEYCODE_BUTTON_R2),
        ControlElement("btn_l3", ElementType.GAMEPAD_BUTTON, 0.12f, 0.35f, label = "L3", keyCode = android.view.KeyEvent.KEYCODE_BUTTON_THUMBL),
        ControlElement("btn_r3", ElementType.GAMEPAD_BUTTON, 0.80f, 0.35f, label = "R3", keyCode = android.view.KeyEvent.KEYCODE_BUTTON_THUMBR),
        ControlElement("btn_guide", ElementType.GAMEPAD_BUTTON, 0.55f, 0.84f, label = "GUIDE", keyCode = android.view.KeyEvent.KEYCODE_BUTTON_MODE),
        ControlElement("stop_overlay", ElementType.STOP_OVERLAY, 0.5f, 0.04f, label = "STOP", radiusDp = 40f)
    )
}
