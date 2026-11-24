package com.example.javbrowser

import android.app.Dialog
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle

/** Late async results must not add a window to an inactive or detached Activity. */
object ActivityDialogGuard {
    fun canShow(activity: ComponentActivity): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper() || activity.isFinishing || activity.isDestroyed ||
            !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return false
        val decor = activity.window?.decorView ?: return false
        return decor.isAttachedToWindow && decor.windowToken != null
    }
    fun tryShow(activity: ComponentActivity, dialog: Dialog): Boolean =
        showWindow({ canShow(activity) }, { dialog.show() })

    internal fun showWindow(allowed: () -> Boolean, show: () -> Unit): Boolean {
        if (!allowed()) return false
        return try {
            show()
            true
        } catch (_: WindowManager.BadTokenException) {
            android.util.Log.w("ActivityDialog", "Dialog skipped: window token became invalid")
            false
        } catch (_: WindowManager.InvalidDisplayException) {
            android.util.Log.w("ActivityDialog", "Dialog skipped: display became unavailable")
            false
        }
    }
}
