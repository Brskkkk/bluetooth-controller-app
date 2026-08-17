package com.idk.touchoverlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.SparseArray
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.view.ViewCompat
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

class ControlOverlayView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var elements: MutableList<ControlElement> = mutableListOf()
    var editMode: Boolean = false
        set(value) { field = value; selectedElement = null; requestLayout(); invalidate() }
    var injector: ShizukuInputEngine? = null
    var onLayoutChanged: (() -> Unit)? = null
    var onStopRequested: (() -> Unit)? = null

    // --- Rendering ---
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(140, 40, 40, 40) }
    private val circlePaintActive = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(180, 90, 200, 140) }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 3f }
    private val selectedStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(255, 255, 200, 60); style = Paint.Style.STROKE; strokeWidth = 6f }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 32f }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 255, 255) }
    private val stopPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(160, 200, 60, 60) }

    private val chevronUp = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_chevron_up)
    private val chevronDown = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_chevron_down)
    private val chevronLeft = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_chevron_left)
    private val chevronRight = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_chevron_right)

    private fun dp(v: Float) = v * resources.displayMetrics.density

    // --- Play-mode per-pointer state ---
    private data class TouchBinding(
        val element: ControlElement,
        var lockedCenterX: Float = 0f,
        var lockedCenterY: Float = 0f,
        var lastRawX: Float = 0f,
        var lastRawY: Float = 0f,
        var downTimeMs: Long = 0L,
        var moved: Boolean = false,
        var anchorXPercent: Float = 0f,
        var anchorYPercent: Float = 0f,
        var teleported: Boolean = false,
        var pressedDpadKey: Int = 0,
        var pressedHatX: Float = 0f,
        var pressedHatY: Float = 0f
    )
    private val activeTouches = SparseArray<TouchBinding>()

    // Persistent combined gamepad axis state (one injected event carries all axes at once).
    private var leftX = 0f; private var leftY = 0f
    private var rightX = 0f; private var rightY = 0f
    private var hatX = 0f; private var hatY = 0f
    private var triggerL = 0f; private var triggerR = 0f

    // Gyro-driven right-stick state.
    private var gyroEnabled = false
    private var gyroRightX = 0f
    private var gyroRightY = 0f

    // Persistent virtual mouse cursor position for TRACKPAD, survives across touch sessions.
    private var cursorX = 0f
    private var cursorY = 0f
    private var cursorInitialized = false

    // --- Edit-mode state ---
    private var selectedElement: ControlElement? = null
    private var dragLastRawX = 0f
    private var dragLastRawY = 0f
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val el = selectedElement ?: return false
            el.scale = (el.scale * detector.scaleFactor).coerceIn(0.4f, 3f)
            invalidate()
            return true
        }
    })

    init {
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            insets
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!cursorInitialized) { cursorX = width / 2f; cursorY = height / 2f; cursorInitialized = true }

        for (el in elements) {
            val cx = el.xPercent * width
            val cy = el.yPercent * height
            val r = dp(el.radiusDp) * el.scale

            if (el.type == ElementType.DPAD) {
                drawDpad(canvas, cx, cy, r, el)
                continue
            }

            val held = activeTouchesHolding(el)
            val fillPaint = when {
                el.type == ElementType.STOP_OVERLAY -> stopPaint
                held -> circlePaintActive
                else -> circlePaint
            }
            canvas.drawCircle(cx, cy, r, fillPaint)
            canvas.drawCircle(cx, cy, r, if (selectedElement === el) selectedStrokePaint else strokePaint)

            when (el.type) {
                ElementType.JOYSTICK_TOUCH, ElementType.JOYSTICK_GAMEPAD -> {
                    val binding = findBindingFor(el)
                    val kx = if (binding != null) clampToRadius(binding.lastRawX, binding.lockedCenterX, r) else cx
                    val ky = if (binding != null) clampToRadius(binding.lastRawY, binding.lockedCenterY, r) else cy
                    canvas.drawCircle(kx, ky, r * 0.4f, knobPaint)
                }
                ElementType.TRACKPAD -> {
                    val savedSize = textPaint.textSize
                    textPaint.textSize = dp(12f)
                    canvas.drawText("TRACKPAD", cx, cy, textPaint)
                    textPaint.textSize = savedSize
                }
                else -> canvas.drawText(el.label, cx, cy + dp(6f), textPaint)
            }
        }
    }

    private fun clampToRadius(v: Float, center: Float, r: Float): Float = v.coerceIn(center - r, center + r)

    private fun drawDpad(canvas: Canvas, cx: Float, cy: Float, r: Float, el: ControlElement) {
        val held = activeTouchesHolding(el)
        canvas.drawCircle(cx, cy, r, if (held) circlePaintActive else circlePaint)
        canvas.drawCircle(cx, cy, r, if (selectedElement === el) selectedStrokePaint else strokePaint)
        val iconSize = (r * 0.5f).toInt()
        fun place(d: android.graphics.drawable.Drawable?, x: Float, y: Float) {
            d ?: return
            d.setBounds((x - iconSize / 2).toInt(), (y - iconSize / 2).toInt(), (x + iconSize / 2).toInt(), (y + iconSize / 2).toInt())
            d.draw(canvas)
        }
        place(chevronUp, cx, cy - r * 0.5f)
        place(chevronDown, cx, cy + r * 0.5f)
        place(chevronLeft, cx - r * 0.5f, cy)
        place(chevronRight, cx + r * 0.5f, cy)
    }

    private fun activeTouchesHolding(el: ControlElement): Boolean {
        for (i in 0 until activeTouches.size()) {
            if (activeTouches.valueAt(i).element === el) return true
        }
        return false
    }

    private fun findBindingFor(el: ControlElement): TouchBinding? {
        for (i in 0 until activeTouches.size()) {
            val b = activeTouches.valueAt(i)
            if (b.element === el) return b
        }
        return null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (editMode) {
            scaleDetector.onTouchEvent(event)
            return handleEditTouch(event)
        }
        return handlePlayTouch(event)
    }

    // ---------------- Edit mode: drag (raw-delta) + pinch handled by scaleDetector above ----------------
    private fun handleEditTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val el = hitTest(event.x, event.y)
                selectedElement = el
                dragLastRawX = event.rawX
                dragLastRawY = event.rawY
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val el = selectedElement ?: return true
                if (event.pointerCount > 1) return true // pinch is driving size, not position
                // Raw delta THIS step, not from the original down point - this
                // is the actual fix for the 1px-stuck bug: computing delta
                // from a stale/truncated reference each time is what caused
                // it, not the coordinate system itself.
                val dx = event.rawX - dragLastRawX
                val dy = event.rawY - dragLastRawY
                dragLastRawX = event.rawX
                dragLastRawY = event.rawY
                if (width > 0 && height > 0) {
                    el.xPercent = (el.xPercent + dx / width).coerceIn(0f, 1f)
                    el.yPercent = (el.yPercent + dy / height).coerceIn(0f, 1f)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (selectedElement != null) onLayoutChanged?.invoke()
            }
        }
        return true
    }

    fun shouldHandleTouch(event: MotionEvent, pointerIndex: Int): Boolean {
        val pointerId = event.getPointerId(pointerIndex)
        if (activeTouches.get(pointerId) != null) return true
        return hitTest(event.getX(pointerIndex), event.getY(pointerIndex)) != null
    }

    fun hasActivePointer(pointerId: Int): Boolean = activeTouches.get(pointerId) != null

    private fun hasActiveRightStickTouch(): Boolean {
        for (i in 0 until activeTouches.size()) {
            val binding = activeTouches.valueAt(i)
            if (binding.element.type == ElementType.JOYSTICK_GAMEPAD && binding.element.isRightStick) return true
        }
        return false
    }

    fun setGyroEnabled(enabled: Boolean) {
        gyroEnabled = enabled
        if (!gyroEnabled && !hasActiveRightStickTouch()) {
            rightX = 0f
            rightY = 0f
            injector?.gamepadAxes(leftX, leftY, rightX, rightY, triggerL, triggerR, hatX, hatY)
        } else {
            updateGyroAxes()
        }
    }

    fun setGyroValues(x: Float, y: Float) {
        gyroRightX = x
        gyroRightY = y
        updateGyroAxes()
    }

    private fun updateGyroAxes() {
        if (!gyroEnabled || hasActiveRightStickTouch()) return
        rightX = gyroRightX
        rightY = gyroRightY
        injector?.gamepadAxes(leftX, leftY, rightX, rightY, triggerL, triggerR, hatX, hatY)
    }

    /** Union of every element's bounding box, in this view's local coordinates. Used to tell the
     * window manager which parts of the fullscreen overlay window should actually receive touches -
     * everywhere else passes straight through to whatever app is underneath. */
    fun computeTouchableRegion(): android.graphics.Region {
        val region = android.graphics.Region()
        for (el in elements) {
            val cx = el.xPercent * width
            val cy = el.yPercent * height
            val r = dp(el.radiusDp) * el.scale
            region.op(
                android.graphics.Rect((cx - r).toInt(), (cy - r).toInt(), (cx + r).toInt(), (cy + r).toInt()),
                android.graphics.Region.Op.UNION
            )
        }
        return region
    }

    private fun hitTest(x: Float, y: Float): ControlElement? {
        var best: ControlElement? = null
        var bestDist = Float.MAX_VALUE
        for (el in elements) {
            val cx = el.xPercent * width
            val cy = el.yPercent * height
            val r = dp(el.radiusDp) * el.scale
            val d = hypot(x - cx, y - cy)
            if (d <= r && d < bestDist) { best = el; bestDist = d }
        }
        return best
    }

    // ---------------- Play mode: real input dispatch ----------------
    private fun handlePlayTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val pid = event.getPointerId(idx)
                val x = event.getX(idx)
                val y = event.getY(idx)
                val el = hitTest(x, y) ?: return false
                onElementDown(pid, el, x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeTouches.size() == 0) return false
                for (i in 0 until event.pointerCount) {
                    val pid = event.getPointerId(i)
                    val binding = activeTouches.get(pid) ?: continue
                    onElementMove(pid, binding, event.getX(i), event.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val idx = event.actionIndex
                val pid = event.getPointerId(idx)
                val binding = activeTouches.get(pid) ?: return false
                onElementUp(pid, binding)
            }
            MotionEvent.ACTION_CANCEL -> {
                for (i in 0 until activeTouches.size()) onElementUp(activeTouches.keyAt(i), activeTouches.valueAt(i))
                activeTouches.clear()
            }
        }
        invalidate()
        return true
    }

    private fun onElementDown(pointerId: Int, el: ControlElement, x: Float, y: Float) {
        val binding = TouchBinding(el, x, y, x, y, System.currentTimeMillis())
        if (el.type == ElementType.JOYSTICK_TOUCH || el.type == ElementType.JOYSTICK_GAMEPAD) {
            binding.anchorXPercent = el.xPercent
            binding.anchorYPercent = el.yPercent
            el.xPercent = (x / width).coerceIn(0f, 1f)
            el.yPercent = (y / height).coerceIn(0f, 1f)
            binding.teleported = true
            binding.lockedCenterX = x
            binding.lockedCenterY = y
        }
        activeTouches.put(pointerId, binding)

        when (el.type) {
            ElementType.TOUCH_BUTTON -> injector?.pointer(pointerId, ShizukuInputEngine.ACTION_DOWN, x, y)
            ElementType.GAMEPAD_BUTTON -> injector?.gamepadButton(el.keyCode, true)
            ElementType.DPAD -> {
                val key = dpadKeyFor(el, x, y)
                val hat = dpadHatFor(el, x, y)
                if (key != null && hat != null) {
                    binding.pressedDpadKey = key
                    binding.pressedHatX = hat.first
                    binding.pressedHatY = hat.second
                    hatX = hat.first
                    hatY = hat.second
                    injector?.gamepadButton(key, true)
                    injector?.gamepadAxes(leftX, leftY, rightX, rightY, triggerL, triggerR, hatX, hatY)
                }
            }
            ElementType.JOYSTICK_TOUCH -> injector?.pointer(pointerId, ShizukuInputEngine.ACTION_DOWN, x, y)
            ElementType.JOYSTICK_GAMEPAD -> {
                if (el.isRightStick) { rightX = 0f; rightY = 0f } else { leftX = 0f; leftY = 0f }
                injector?.gamepadAxes(leftX, leftY, rightX, rightY, triggerL, triggerR, hatX, hatY)
            }
            ElementType.TRACKPAD -> { /* movement handled on MOVE; a quick tap-with-no-move is a click, sent on UP */ }
            ElementType.STOP_OVERLAY -> onStopRequested?.invoke()
        }
    }

    private fun onElementMove(pointerId: Int, binding: TouchBinding, x: Float, y: Float) {
        val el = binding.element
        val dx = x - binding.lastRawX
        val dy = y - binding.lastRawY
        if (abs(dx) > 2 || abs(dy) > 2) binding.moved = true

        when (el.type) {
            ElementType.TOUCH_BUTTON, ElementType.GAMEPAD_BUTTON, ElementType.STOP_OVERLAY -> { /* no drag behavior */ }
            ElementType.DPAD -> {
                val hat = dpadHatFor(el, x, y)
                val newKey = dpadKeyFor(el, x, y)
                val newHatX = hat?.first ?: 0f
                val newHatY = hat?.second ?: 0f
                val changed = newKey != binding.pressedDpadKey || newHatX != binding.pressedHatX || newHatY != binding.pressedHatY
                if (changed) {
                    if (binding.pressedDpadKey != 0 && binding.pressedDpadKey != newKey) {
                        injector?.gamepadButton(binding.pressedDpadKey, false)
                    }
                    if (newKey != null && newKey != binding.pressedDpadKey) {
                        injector?.gamepadButton(newKey, true)
                    }
                    binding.pressedDpadKey = newKey ?: 0
                    binding.pressedHatX = newHatX
                    binding.pressedHatY = newHatY
                    hatX = newHatX
                    hatY = newHatY
                    injector?.gamepadAxes(leftX, leftY, rightX, rightY, triggerL, triggerR, hatX, hatY)
                }
            }

            ElementType.JOYSTICK_TOUCH -> {
                val r = dp(el.radiusDp) * el.scale
                val cx = clampToRadius(x, binding.lockedCenterX, r)
                val cy = clampToRadius(y, binding.lockedCenterY, r)
                binding.lastRawX = x; binding.lastRawY = y
                injector?.pointer(pointerId, ShizukuInputEngine.ACTION_MOVE, cx, cy)
                return
            }

            ElementType.JOYSTICK_GAMEPAD -> {
                val r = dp(el.radiusDp) * el.scale
                var nx = (x - binding.lockedCenterX) / r
                var ny = (y - binding.lockedCenterY) / r
                val mag = hypot(nx, ny)
                if (mag < el.deadzonePercent) { nx = 0f; ny = 0f } else {
                    val clamped = min(mag, 1f) / mag
                    nx *= clamped; ny *= clamped
                }
                if (el.isRightStick) { rightX = nx; rightY = ny } else { leftX = nx; leftY = ny }
                injector?.gamepadAxes(leftX, leftY, rightX, rightY, triggerL, triggerR, hatX, hatY)
            }

            ElementType.TRACKPAD -> {
                cursorX = (cursorX + dx * el.sensitivity).coerceIn(0f, width.toFloat())
                cursorY = (cursorY + dy * el.sensitivity).coerceIn(0f, height.toFloat())
                injector?.mouseMove(cursorX, cursorY, false)
            }
        }
        binding.lastRawX = x
        binding.lastRawY = y
    }

    private fun onElementUp(pointerId: Int, binding: TouchBinding) {
        val el = binding.element
        when (el.type) {
            ElementType.TOUCH_BUTTON -> injector?.pointer(pointerId, ShizukuInputEngine.ACTION_UP, binding.lastRawX, binding.lastRawY)
            ElementType.GAMEPAD_BUTTON -> injector?.gamepadButton(el.keyCode, false)
            ElementType.DPAD -> {
                val key = if (binding.pressedDpadKey != 0) binding.pressedDpadKey else dpadKeyFor(el, binding.lastRawX, binding.lastRawY) ?: 0
                if (key != 0) injector?.gamepadButton(key, false)
                binding.pressedDpadKey = 0
                binding.pressedHatX = 0f
                binding.pressedHatY = 0f
                hatX = 0f
                hatY = 0f
                injector?.gamepadAxes(leftX, leftY, rightX, rightY, triggerL, triggerR, hatX, hatY)
            }
            ElementType.JOYSTICK_TOUCH -> injector?.pointer(pointerId, ShizukuInputEngine.ACTION_UP, binding.lastRawX, binding.lastRawY)
            ElementType.JOYSTICK_GAMEPAD -> {
                if (el.isRightStick) { rightX = 0f; rightY = 0f } else { leftX = 0f; leftY = 0f }
                injector?.gamepadAxes(leftX, leftY, rightX, rightY, triggerL, triggerR, hatX, hatY)
            }
            ElementType.TRACKPAD -> {
                // A tap with negligible movement is a click; an actual drag was already relayed as cursor movement.
                if (!binding.moved) injector?.mouseClick(cursorX, cursorY)
            }
            ElementType.STOP_OVERLAY -> { /* handled on down, nothing to do on release */ }
        }
        if (binding.teleported && (el.type == ElementType.JOYSTICK_TOUCH || el.type == ElementType.JOYSTICK_GAMEPAD)) {
            el.xPercent = binding.anchorXPercent
            el.yPercent = binding.anchorYPercent
        }
        activeTouches.remove(pointerId)
        if (el.type == ElementType.JOYSTICK_GAMEPAD && el.isRightStick && gyroEnabled) {
            updateGyroAxes()
        }
    }

    private fun dpadKeyFor(el: ControlElement, x: Float, y: Float): Int? {
        val cx = el.xPercent * width
        val cy = el.yPercent * height
        val angle = Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble()))
        return when {
            angle in -45.0..45.0 -> KeyEvent.KEYCODE_DPAD_RIGHT
            angle in 45.0..135.0 -> KeyEvent.KEYCODE_DPAD_DOWN
            angle in -135.0..-45.0 -> KeyEvent.KEYCODE_DPAD_UP
            else -> KeyEvent.KEYCODE_DPAD_LEFT
        }
    }

    private fun dpadHatFor(el: ControlElement, x: Float, y: Float): Pair<Float, Float>? {
        val cx = el.xPercent * width
        val cy = el.yPercent * height
        val angle = Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble()))
        return when {
            angle in -45.0..45.0 -> Pair(1f, 0f)
            angle in 45.0..135.0 -> Pair(0f, 1f)
            angle in -135.0..-45.0 -> Pair(0f, -1f)
            else -> Pair(-1f, 0f)
        }
    }
}
