package com.idk.touchoverlay

import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs under the shell UID via Shizuku.bindUserService. Maintains the
 * actual multi-touch pointer state (which virtual buttons/joysticks are
 * currently held) and synthesizes correctly-shaped Android MotionEvents -
 * a single MotionEvent per state change carries EVERY currently-active
 * pointer's coordinates, with the action mask identifying which one just
 * changed (ACTION_DOWN/ACTION_POINTER_DOWN/ACTION_MOVE/
 * ACTION_POINTER_UP/ACTION_UP) - that shape is what lets, say, a joystick
 * and two buttons be held down at once and all still work correctly.
 * 
 * Also maintains a uinput virtual HID device for apps that check for
 * actual controller presence (e.g. CoD Mobile). D-pad is mapped as 4 
 * separate buttons (up/down/left/right arrow keys).
 */
class InputInjectorService : IInputInjector.Stub() {

    private val tag = "InputInjectorService"

    // pointerId -> (x, y) for every currently-down pointer, in touch-down order.
    private val activePointers = LinkedHashMap<Int, FloatArray>()
    private var downTimeMs = 0L

    private val uinputLock = Any()
    private var uinputFd: Int = -1
    private val activeButtonStates = LinkedHashMap<Int, Int>()
    private val activeAxisStates = LinkedHashMap<Int, Int>()
    private var uinputInitialized = false
    private var uinputInitFailed = false

    private fun initUinput() {
        synchronized(uinputLock) {
            if (uinputInitialized || uinputInitFailed) return
            try {
                val fd = nativeOpenUinput()
                if (fd < 0) {
                    Log.w(tag, "uinput open failed: $fd")
                    uinputInitFailed = true
                    return
                }
                uinputFd = fd
                val result = nativeSetupGamepad(fd)
                if (result < 0) {
                    Log.w(tag, "uinput setup failed: $result")
                    nativeCloseUinput(fd)
                    uinputFd = -1
                    uinputInitFailed = true
                    return
                }
                uinputInitialized = true
                Log.d(tag, "uinput initialized, fd=$fd")
            } catch (t: Throwable) {
                Log.w(tag, "uinput init exception", t)
                uinputInitFailed = true
            }
        }
    }

    private fun writeButton(code: Int, value: Int) {
        if (value == 0) {
            activeButtonStates.remove(code)
        } else {
            activeButtonStates[code] = value
        }
        if (uinputInitialized) {
            nativeWriteEvent(uinputFd, 1, code, value)
        }
    }

    private fun writeAxis(code: Int, value: Int) {
        activeAxisStates[code] = value
        if (uinputInitialized) {
            nativeWriteEvent(uinputFd, 3, code, value)
        }
    }

    private fun inject(event: android.view.InputEvent) {
        // Legacy fallback path kept for touch events only. Controller buttons/axes continue to be emitted through uinput.
        try {
            val cls = Class.forName("android.hardware.input.InputManager")
            val instance = cls.getMethod("getInstance").invoke(null)
            val method = cls.getMethod("injectInputEvent", android.view.InputEvent::class.java, Int::class.javaPrimitiveType)
            method.invoke(instance, event, 0)
        } catch (t: Throwable) {
            Log.w(tag, "injectInputEvent failed", t)
        }
    }

    @Synchronized
    override fun injectPointer(pointerId: Int, action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()

        when (action) {
            0 -> { // DOWN
                val wasEmpty = activePointers.isEmpty()
                if (wasEmpty) downTimeMs = now
                activePointers[pointerId] = floatArrayOf(x, y)
                val index = activePointers.keys.indexOf(pointerId)
                val actionMasked = if (wasEmpty) MotionEvent.ACTION_DOWN
                    else (MotionEvent.ACTION_POINTER_DOWN or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
                sendMotionEvent(now, actionMasked)
            }
            1 -> { // MOVE
                if (activePointers.containsKey(pointerId)) {
                    activePointers[pointerId] = floatArrayOf(x, y)
                    sendMotionEvent(now, MotionEvent.ACTION_MOVE)
                }
            }
            2 -> { // UP
                if (!activePointers.containsKey(pointerId)) return
                val index = activePointers.keys.indexOf(pointerId)
                val isLast = activePointers.size == 1
                val actionMasked = if (isLast) MotionEvent.ACTION_UP
                    else (MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
                // Position must still be present in the event that reports
                // this pointer going up, so build the event BEFORE removing it.
                sendMotionEvent(now, actionMasked)
                activePointers.remove(pointerId)
            }
        }
    }

    private fun sendMotionEvent(eventTime: Long, actionMasked: Int) {
        val count = activePointers.size
        if (count == 0) return

        val ids = activePointers.keys.toList()
        val props = Array(count) { i ->
            MotionEvent.PointerProperties().apply {
                id = ids[i]
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(count) { i ->
            val xy = activePointers[ids[i]]!!
            MotionEvent.PointerCoords().apply {
                x = xy[0]
                y = xy[1]
                pressure = 1f
                size = 1f
            }
        }

        val event = MotionEvent.obtain(
            downTimeMs, eventTime, actionMasked,
            count, props, coords,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        try {
            inject(event)
        } finally {
            event.recycle()
        }
    }

    override fun injectKey(keyCode: Int, down: Boolean) {
        val linuxCode = androidKeyCodeToLinux(keyCode)
        writeButton(linuxCode, if (down) 1 else 0)
    }

    /**
     * SOURCE_GAMEPAD instead of SOURCE_KEYBOARD - this is what makes a
     * game/emulator with real controller support recognize it as a
     * genuine gamepad button press, independent of any on-screen layout.
     * 
     * D-pad is handled as 4 separate arrow buttons (up/down/left/right).
     */
    override fun injectGamepadButton(keyCode: Int, down: Boolean) {
        initUinput()
        val linuxCode = androidGamepadCodeToLinux(keyCode)
        val value = if (down) 1 else 0
        writeButton(linuxCode, value)
    }

    /**
     * One combined generic-motion event carrying every analog axis at
     * once (left stick, right stick, both triggers) - exactly how a real
     * physical gamepad reports its full state per report, using the
     * standard Android axis mapping (AXIS_X/Y for the left stick,
     * AXIS_Z/RZ for the right stick - yes, RZ not RY, that's the actual
     * AOSP convention most games/emulators expect - and AXIS_LTRIGGER/
     * RTRIGGER for the triggers).
     */
    override fun injectGamepadAxes(leftX: Float, leftY: Float, rightX: Float, rightY: Float, triggerL: Float, triggerR: Float, hatX: Float, hatY: Float) {
        initUinput()
        val leftXValue = (leftX * 32767f).toInt().coerceIn(-32768, 32767)
        val leftYValue = (leftY * 32767f).toInt().coerceIn(-32768, 32767)
        val rightXValue = (rightX * 32767f).toInt().coerceIn(-32768, 32767)
        val rightYValue = (rightY * 32767f).toInt().coerceIn(-32768, 32767)
        val triggerLValue = (triggerL * 255f).toInt().coerceIn(0, 255)
        val triggerRValue = (triggerR * 255f).toInt().coerceIn(0, 255)

        writeAxis(0, leftXValue)
        writeAxis(1, leftYValue)
        writeAxis(2, triggerLValue)
        writeAxis(3, rightXValue)
        writeAxis(4, rightYValue)
        writeAxis(5, triggerRValue)
        
        // D-pad as separate arrow button keys (not analog axes)
        // hatX: -1 = left, 0 = center, 1 = right
        // hatY: -1 = up, 0 = center, 1 = down
        writeButton(KEY_UP, if (hatY < -0.5f) 1 else 0)
        writeButton(KEY_DOWN, if (hatY > 0.5f) 1 else 0)
        writeButton(KEY_LEFT, if (hatX < -0.5f) 1 else 0)
        writeButton(KEY_RIGHT, if (hatX > 0.5f) 1 else 0)
    }

    @Volatile private var mouseButtonWasDown = false

    /**
     * Trackpad-driven cursor movement. [x]/[y] are already-accumulated
     * absolute positions (the client tracks its own virtual cursor
     * position and moves it by relative deltas * sensitivity - see
     * TrackpadView - this call just paints that resulting position).
     * ACTION_HOVER_MOVE while no button is held (cursor glides without
     * clicking anything), or a DOWN+MOVE drag sequence while it is.
     */
    @Synchronized
    override fun injectMouseMove(x: Float, y: Float, buttonDown: Boolean) {
        val now = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = if (buttonDown) 1f else 0f })

        val action = when {
            buttonDown && !mouseButtonWasDown -> MotionEvent.ACTION_DOWN
            buttonDown -> MotionEvent.ACTION_MOVE
            else -> MotionEvent.ACTION_HOVER_MOVE
        }
        val buttonState = if (buttonDown) MotionEvent.BUTTON_PRIMARY else 0

        val event = MotionEvent.obtain(
            now, now, action,
            1, props, coords,
            0, buttonState, 1f, 1f, 0, 0,
            InputDevice.SOURCE_MOUSE, 0
        )
        try {
            inject(event)
        } finally {
            event.recycle()
        }

        if (buttonDown != mouseButtonWasDown && !buttonDown) {
            // Button was just released - send the matching ACTION_UP so
            // whatever received the DOWN doesn't think the click is stuck.
            sendMouseUp(x, y)
        }
        mouseButtonWasDown = buttonDown
    }

    private fun sendMouseUp(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y })
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        try { inject(event) } finally { event.recycle() }
    }

    /** A discrete tap-to-click, separate from a press-and-drag - a quick DOWN+UP at the current cursor position. */
    override fun injectMouseClick(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f })

        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 1, props, coords, 0, MotionEvent.BUTTON_PRIMARY, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        val up = MotionEvent.obtain(now, now + 1, MotionEvent.ACTION_UP, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        try {
            inject(down)
            inject(up)
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    private fun androidKeyCodeToLinux(code: Int): Int = when (code) {
        KeyEvent.KEYCODE_ENTER -> 28
        KeyEvent.KEYCODE_SPACE -> 57
        KeyEvent.KEYCODE_TAB -> 15
        KeyEvent.KEYCODE_BACK -> 158
        KeyEvent.KEYCODE_ESCAPE -> 1
        else -> code
    }

    private fun androidGamepadCodeToLinux(code: Int): Int = when (code) {
        KeyEvent.KEYCODE_BUTTON_A -> 304
        KeyEvent.KEYCODE_BUTTON_B -> 305
        KeyEvent.KEYCODE_BUTTON_X -> 307
        KeyEvent.KEYCODE_BUTTON_Y -> 308
        KeyEvent.KEYCODE_BUTTON_L1 -> 310
        KeyEvent.KEYCODE_BUTTON_R1 -> 311
        KeyEvent.KEYCODE_BUTTON_L2 -> 312
        KeyEvent.KEYCODE_BUTTON_R2 -> 313
        KeyEvent.KEYCODE_BUTTON_SELECT -> 314
        KeyEvent.KEYCODE_BUTTON_START -> 315
        KeyEvent.KEYCODE_BUTTON_MODE -> 316
        KeyEvent.KEYCODE_BUTTON_THUMBL -> 317
        KeyEvent.KEYCODE_BUTTON_THUMBR -> 318
        // D-pad handled separately as arrow keys in injectGamepadAxes
        KeyEvent.KEYCODE_DPAD_UP -> KEY_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> KEY_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> KEY_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> KEY_RIGHT
        else -> code
    }

    override fun ping(): Int = 1

    override fun destroy() {
        synchronized(uinputLock) {
            if (uinputInitialized && uinputFd >= 0) {
                nativeCloseUinput(uinputFd)
                uinputFd = -1
                uinputInitialized = false
            }
        }
    }

    // Native uinput interface
    private external fun nativeOpenUinput(): Int
    private external fun nativeCloseUinput(fd: Int): Int
    private external fun nativeSetupGamepad(fd: Int): Int
    private external fun nativeWriteEvent(fd: Int, type: Int, code: Int, value: Int): Int

    private companion object {
        // Linux evdev key codes for arrow keys
        private const val KEY_UP = 103
        private const val KEY_DOWN = 108
        private const val KEY_LEFT = 105
        private const val KEY_RIGHT = 106

        init {
            try {
                System.loadLibrary("uinput-jni")
            } catch (e: UnsatisfiedLinkError) {
                Log.w("InputInjectorService", "Failed to load uinput-jni native library", e)
            }
        }
    }
}
