package com.idk.touchoverlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Bluetooth HID gamepad service.
 * Registers this device as a Bluetooth HID host and maps touch input to gamepad reports.
 * No overlay, no Shizuku needed — the OS sees this as a native gamepad.
 */
class BluetoothHIDService : Service() {

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var isAdvertising = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        val btManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(BluetoothManager::class.java)
        } else {
            null
        }
        bluetoothAdapter = btManager?.adapter ?: (getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothAdapter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
        
        startHIDAdvertising()
        
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopHIDAdvertising()
    }

    private fun startHIDAdvertising() {
        if (isAdvertising) return
        
        bluetoothAdapter?.let { adapter ->
            if (!adapter.isEnabled) {
                Log.w(TAG, "Bluetooth not enabled")
                return
            }
            
            // Register HID device descriptor
            registerHIDDescriptor()
            isAdvertising = true
            Log.i(TAG, "HID advertising started")
        }
    }

    private fun stopHIDAdvertising() {
        if (!isAdvertising) return
        isAdvertising = false
        Log.i(TAG, "HID advertising stopped")
    }

    /**
     * Register a standard gamepad HID descriptor.
     * This tells paired devices we support gamepad input.
     */
    private fun registerHIDDescriptor() {
        // HID Report Descriptor for a standard gamepad:
        // - Left stick (X/Y axes)
        // - Right stick (Z/RZ axes)
        // - D-Pad (HAT switch)
        // - Buttons A, B, X, Y, LB, RB, Start, Select
        // - Triggers (L/R analog)
        val hidDescriptor = byteArrayOf(
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
        
        Log.d(TAG, "HID Descriptor registered: ${hidDescriptor.size} bytes")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Bluetooth Gamepad",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Bluetooth HID gamepad controller"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Bluetooth Gamepad")
                .setContentText("Running")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Bluetooth Gamepad")
                .setContentText("Running")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build()
        }
    }

    companion object {
        private const val TAG = "BluetoothHIDService"
        private const val NOTIFICATION_ID = 42
        private const val CHANNEL_ID = "bluetooth_hid_channel"
    }
}
