package com.idk.touchoverlay

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku

class ShizukuInputEngine(private val ctx: Context) {

    private var service: IInputInjector? = null
    private var pendingReady: (() -> Unit)? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
            service = binder?.takeIf { it.pingBinder() }?.let { IInputInjector.Stub.asInterface(it) }
            pendingReady?.invoke()
            pendingReady = null
        }
        override fun onServiceDisconnected(name: ComponentName) { service = null }
    }

    fun isReady(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun requestPermission(requestCode: Int, onResult: (Boolean) -> Unit) {
        if (isReady()) { onResult(true); return }
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(code: Int, grantResult: Int) {
                if (code == requestCode) {
                    Shizuku.removeRequestPermissionResultListener(this)
                    onResult(grantResult == PackageManager.PERMISSION_GRANTED)
                }
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        Shizuku.requestPermission(requestCode)
    }

    fun bind(onReady: () -> Unit) {
        if (service?.asBinder()?.pingBinder() == true) { onReady(); return }
        pendingReady = onReady
        runCatching {
            Shizuku.bindUserService(userServiceArgs(), connection)
        }.onFailure { Log.w(TAG, "bindUserService failed", it) }
    }

    fun unbind() {
        runCatching { Shizuku.unbindUserService(userServiceArgs(), connection, true) }
        service = null
    }

    fun pointer(pointerId: Int, action: Int, x: Float, y: Float) {
        runCatching { service?.injectPointer(pointerId, action, x, y) }
            .onFailure { Log.w(TAG, "injectPointer failed", it) }
    }

    fun key(keyCode: Int, down: Boolean) {
        runCatching { service?.injectKey(keyCode, down) }
            .onFailure { Log.w(TAG, "injectKey failed", it) }
    }

    fun gamepadButton(keyCode: Int, down: Boolean) {
        runCatching { service?.injectGamepadButton(keyCode, down) }
            .onFailure { Log.w(TAG, "injectGamepadButton failed", it) }
    }

    fun gamepadAxes(leftX: Float, leftY: Float, rightX: Float, rightY: Float, triggerL: Float, triggerR: Float, hatX: Float = 0f, hatY: Float = 0f) {
        runCatching { service?.injectGamepadAxes(leftX, leftY, rightX, rightY, triggerL, triggerR, hatX, hatY) }
            .onFailure { Log.w(TAG, "injectGamepadAxes failed", it) }
    }

    fun mouseMove(x: Float, y: Float, buttonDown: Boolean) {
        runCatching { service?.injectMouseMove(x, y, buttonDown) }
            .onFailure { Log.w(TAG, "injectMouseMove failed", it) }
    }

    fun mouseClick(x: Float, y: Float) {
        runCatching { service?.injectMouseClick(x, y) }
            .onFailure { Log.w(TAG, "injectMouseClick failed", it) }
    }

    private fun userServiceArgs(): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(ComponentName(ctx.packageName, InputInjectorService::class.java.name))
            .daemon(false)
            .processNameSuffix("input")
            .debuggable(false)
            .version(1)

    companion object {
        private const val TAG = "ShizukuInputEngine"
        const val ACTION_DOWN = 0
        const val ACTION_MOVE = 1
        const val ACTION_UP = 2
    }
}
