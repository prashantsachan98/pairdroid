package org.pairdroid.pairdroid

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent

/**
 * Shown in Settings > Accessibility as "System Services". The guardian enables it
 * once on the child phone; afterwards live view needs no MediaProjection consent
 * dialog. Requests no accessibility events — used only for takeScreenshot() (API 30+).
 */
class MirrorService : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** One screenshot of the default display as a software bitmap; null on failure. */
    fun screenshot(done: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) { done(null); return }
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val wrapped = Bitmap.wrapHardwareBuffer(result.bitmap, result.colorSpace)
                result.bitmap.recycle()
                val copy = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                wrapped?.recycle()
                done(copy)
            }
            override fun onFailure(errorCode: Int) { done(null) }
        })
    }

    companion object {
        @Volatile var instance: MirrorService? = null; private set
    }
}
