package com.idk.touchoverlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat

class OverlayService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "touchoverlay_channel"
        const val ACTION_STOP = "com.idk.touchoverlay.STOP_OVERLAY"
        const val ACTION_SET_DEADZONE = "com.idk.touchoverlay.SET_DEADZONE"
        const val EXTRA_DEADZONE = "deadzone"
        const val ACTION_SET_GYRO_ENABLED = "com.idk.touchoverlay.SET_GYRO_ENABLED"
        const val EXTRA_GYRO_ENABLED = "gyro_enabled"
        @Volatile var isRunning = false
    }

    private var windowManager: WindowManager? = null
    private var root: FrameLayout? = null
    private var controlView: ControlOverlayView? = null
    private var editBadge: TextView? = null

    private val injectorEngine by lazy { ShizukuInputEngine(this) }

    private var editModeOn = false
    private val toggleEditReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            editModeOn = !editModeOn
            setEditMode(editModeOn)
        }
    }

    private var sensorManager: SensorManager? = null
    private var rotationVectorSensor: Sensor? = null
    private val gyroListener = object : SensorEventListener {
        private val rotationMatrix = FloatArray(9)
        private val orientation = FloatArray(3)

        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            SensorManager.getOrientation(rotationMatrix, orientation)
            val roll = orientation[2].coerceIn(-Math.PI.toFloat() / 2f, Math.PI.toFloat() / 2f) / (Math.PI.toFloat() / 2f)
            val pitch = orientation[1].coerceIn(-Math.PI.toFloat() / 2f, Math.PI.toFloat() / 2f) / (Math.PI.toFloat() / 2f)
            controlView?.setGyroValues(roll, pitch)
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    private val setDeadzoneReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val value = intent.getFloatExtra(EXTRA_DEADZONE, -1f)
            if (value < 0f) return
            val view = controlView ?: return
            view.elements.forEach { if (it.type == ElementType.JOYSTICK_GAMEPAD) it.deadzonePercent = value }
            view.invalidate()
        }
    }

    private val gyroToggleReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val enabled = intent.getBooleanExtra(EXTRA_GYRO_ENABLED, false)
            controlView?.setGyroEnabled(enabled)
            if (enabled) startGyro() else stopGyro()
        }
    }

    private var insetsHandle: Any? = null

    // Marks only the areas over actual controls as touchable; everywhere else on this
    // fullscreen overlay window passes clicks straight through to the app underneath.
    // In edit mode the whole window is touchable (returning null) so elements can be
    // dragged from anywhere; an empty region means nothing is touchable yet.
    private fun computeTouchableInsets(): android.graphics.Region? {
        val view = controlView ?: return android.graphics.Region()
        if (view.width == 0 || view.height == 0) return android.graphics.Region()
        if (view.editMode) return null
        return view.computeTouchableRegion()
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForegroundNotification()
        addOverlay()

        val filter = android.content.IntentFilter(MainActivity.ACTION_TOGGLE_EDIT)
        val deadzoneFilter = android.content.IntentFilter(ACTION_SET_DEADZONE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(toggleEditReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(setDeadzoneReceiver, deadzoneFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(gyroToggleReceiver, android.content.IntentFilter(ACTION_SET_GYRO_ENABLED), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(toggleEditReceiver, filter)
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(setDeadzoneReceiver, deadzoneFilter)
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(gyroToggleReceiver, android.content.IntentFilter(ACTION_SET_GYRO_ENABLED))
        }

        injectorEngine.requestPermission(6001) { granted ->
            if (granted) injectorEngine.bind { /* ready */ }
        }

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        rotationVectorSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val gyroEnabled = ControlsRepository.isGyroEnabled(this)
        controlView?.setGyroEnabled(gyroEnabled)
        if (gyroEnabled) startGyro()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun addOverlay() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )

        val rootLayout = FrameLayout(this)
        root = rootLayout

        val overlayView = ControlOverlayView(this)
        overlayView.elements = ControlsRepository.load(this)
        overlayView.injector = injectorEngine
        overlayView.onLayoutChanged = { ControlsRepository.save(this, overlayView.elements) }
        overlayView.onStopRequested = { stopSelf() }
        overlayView.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        controlView = overlayView
        rootLayout.addView(overlayView)

        insetsHandle = TouchInsetsCompat.register(rootLayout) { computeTouchableInsets() }

        wm.addView(rootLayout, params)
    }

    fun setEditMode(enabled: Boolean) {
        controlView?.editMode = enabled
        // Edit mode changes how much of the window is touchable - force the window manager
        // to re-ask for insets right away instead of waiting for some unrelated layout pass.
        root?.requestLayout()
    }

    private fun startGyro() {
        sensorManager?.registerListener(gyroListener, rotationVectorSensor, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun stopGyro() {
        sensorManager?.unregisterListener(gyroListener)
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Touch Overlay", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        val stopIntent = Intent(this, OverlayService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Touch Overlay running")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        runCatching { unregisterReceiver(toggleEditReceiver) }
        runCatching { unregisterReceiver(setDeadzoneReceiver) }
        runCatching { unregisterReceiver(gyroToggleReceiver) }
        stopGyro()
        controlView?.let { ControlsRepository.save(this, it.elements) }
        injectorEngine.unbind()
        root?.let {
            runCatching { TouchInsetsCompat.unregister(it, insetsHandle) }
            runCatching { windowManager?.removeView(it) }
        }
        root = null
        controlView = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
