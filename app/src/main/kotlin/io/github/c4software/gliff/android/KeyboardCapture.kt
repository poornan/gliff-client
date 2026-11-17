package io.github.c4software.gliff.android

import android.app.Activity
import android.content.ComponentName
import android.util.Log
import android.view.WindowManager
import java.lang.reflect.Method

/**
 * Asks the system to hand a window the keys it would otherwise keep for
 * itself: Meta (Super) alone, Meta+Tab, Alt+Tab and the like. Without it, a
 * session never sees Super, and Hyprland's shortcuts cannot be reached from a
 * physical keyboard.
 *
 * Two ways in, both asked every time, since asking one that is missing does
 * nothing and only the system knows which one takes effect:
 * - The platform's `WindowManager.LayoutParams.setKeyboardCaptureEnabled`,
 *   added after API 36 (Android 16 QPR2), checked at key delivery against the
 *   `CAPTURE_KEYBOARD` permission (normal protection level, granted at
 *   install). See developer.android.com/reference/android/view/WindowManager.LayoutParams.
 * - Samsung's `com.samsung.android.view.SemWindowManager.requestMetaKeyEvent`,
 *   DeX included: not a public API, the same call Moonlight
 *   (app/src/main/java/com/limelight/Game.java), AVNC and Termux-X11 make.
 *
 * Both are found by reflection, once, so the app still builds against SDK 36;
 * a device with neither works as before.
 */
object KeyboardCapture {
    private const val TAG = "KeyboardCapture"

    private val platformSetter: Method? by lazy {
        runCatching {
            WindowManager.LayoutParams::class.java
                .getMethod("setKeyboardCaptureEnabled", Boolean::class.javaPrimitiveType)
        }.getOrNull().also { if (it == null) Log.d(TAG, "no platform keyboard capture") }
    }

    private val samsung: Pair<Any, Method>? by lazy {
        runCatching {
            val managerClass = Class.forName("com.samsung.android.view.SemWindowManager")
            val manager = managerClass.getMethod("getInstance").invoke(null) ?: return@runCatching null
            val request = managerClass.getDeclaredMethod(
                "requestMetaKeyEvent", ComponentName::class.java, Boolean::class.javaPrimitiveType,
            )
            manager to request
        }.getOrNull().also { if (it == null) Log.d(TAG, "no Samsung meta key capture") }
    }

    fun set(activity: Activity, enabled: Boolean) {
        platformSetter?.let { setter ->
            runCatching {
                val window = activity.window
                // Changed on a copy, committed whole: the window's own
                // attributes are only touched by the commit, never half-set.
                val attributes = WindowManager.LayoutParams().apply { copyFrom(window.attributes) }
                setter.invoke(attributes, enabled)
                window.attributes = attributes
            }.onFailure { Log.w(TAG, "platform keyboard capture refused: $it") }
        }
        samsung?.let { (manager, request) ->
            runCatching { request.invoke(manager, activity.componentName, enabled) }
                .onFailure { Log.w(TAG, "Samsung meta key capture refused: $it") }
        }
    }
}
