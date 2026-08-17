package com.idk.touchoverlay

import android.graphics.Region
import android.view.View
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * android.view.ViewTreeObserver.OnComputeInternalInsetsListener / InternalInsetsInfo are the
 * platform's real mechanism for telling the window manager "only these pixels of this window are
 * touchable, let taps everywhere else pass through to the window behind" - exactly what a
 * fullscreen click-through overlay needs. They're marked @hide, though, so they're stripped from
 * the public compileSdk stub jar and can't be referenced directly - doing so fails at compile time
 * with "Unresolved reference", not at runtime. The classes and methods are still there on the
 * device at runtime, so we reach them via reflection instead.
 *
 * If reflection ever fails (locked-down OEM build, future platform change, etc.) [register]
 * returns null and the overlay just falls back to being fully touchable everywhere - the app still
 * works, it just loses the pass-through behavior on that device.
 */
object TouchInsetsCompat {
    private const val TOUCHABLE_INSETS_FRAME = 0
    private const val TOUCHABLE_INSETS_REGION = 3

    private val insetsInfoClass = runCatching {
        Class.forName("android.view.ViewTreeObserver\$InternalInsetsInfo")
    }.getOrNull()

    private val listenerClass = runCatching {
        Class.forName("android.view.ViewTreeObserver\$OnComputeInternalInsetsListener")
    }.getOrNull()

    private val touchableRegionField = insetsInfoClass?.let {
        runCatching { it.getField("touchableRegion") }.getOrNull()
    }

    private val setTouchableInsetsMethod = insetsInfoClass?.let {
        runCatching { it.getMethod("setTouchableInsets", Int::class.javaPrimitiveType) }.getOrNull()
    }

    private val viewTreeObserverClass = android.view.ViewTreeObserver::class.java

    private val addListenerMethod = listenerClass?.let { lc ->
        runCatching { viewTreeObserverClass.getMethod("addOnComputeInternalInsetsListener", lc) }.getOrNull()
    }

    private val removeListenerMethod = listenerClass?.let { lc ->
        runCatching { viewTreeObserverClass.getMethod("removeOnComputeInternalInsetsListener", lc) }.getOrNull()
    }

    private var hiddenApiBypassAttempted = false

    /** Some Android versions restrict reflective access to non-SDK members. This lifts that
     * restriction for this process; harmless no-op if it's not supported or not needed. */
    private fun bypassHiddenApiRestrictionsIfNeeded() {
        if (hiddenApiBypassAttempted) return
        hiddenApiBypassAttempted = true
        runCatching {
            val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
            val getRuntime = vmRuntimeClass.getDeclaredMethod("getRuntime").apply { isAccessible = true }
            val runtime = getRuntime.invoke(null)
            vmRuntimeClass.getDeclaredMethod("setHiddenApiExemptions", Array<String>::class.java)
                .apply { isAccessible = true }
                .invoke(runtime, arrayOf("L"))
        }
    }

    /**
     * Registers a callback that decides, each time the window manager asks, which part of [view]'s
     * window should actually receive touches. Return a [Region] to make only that area touchable
     * (everything else passes through to the window behind); return null to make the whole window
     * touchable as normal.
     *
     * @return an opaque handle to pass to [unregister], or null if this device doesn't support it.
     */
    fun register(view: View, computeRegion: () -> Region?): Any? {
        val lc = listenerClass ?: return null
        val add = addListenerMethod ?: return null
        val touchableField = touchableRegionField ?: return null
        val setInsets = setTouchableInsetsMethod ?: return null
        bypassHiddenApiRestrictionsIfNeeded()

        val handler = InvocationHandler { _, method, args ->
            if (method.name == "onComputeInternalInsets" && args != null && args.isNotEmpty()) {
                val info = args[0]
                val region = computeRegion()
                if (region == null) {
                    setInsets.invoke(info, TOUCHABLE_INSETS_FRAME)
                } else {
                    setInsets.invoke(info, TOUCHABLE_INSETS_REGION)
                    (touchableField.get(info) as Region).set(region)
                }
                null
            } else when (method.name) {
                "hashCode" -> System.identityHashCode(args?.getOrNull(0) ?: this)
                "equals" -> false
                "toString" -> "TouchInsetsCompat.Listener"
                else -> null
            }
        }

        val proxy = Proxy.newProxyInstance(lc.classLoader, arrayOf(lc), handler)
        return runCatching {
            add.invoke(view.viewTreeObserver, proxy)
            proxy
        }.getOrNull()
    }

    fun unregister(view: View, handle: Any?) {
        handle ?: return
        val remove = removeListenerMethod ?: return
        runCatching { remove.invoke(view.viewTreeObserver, handle) }
    }
}
