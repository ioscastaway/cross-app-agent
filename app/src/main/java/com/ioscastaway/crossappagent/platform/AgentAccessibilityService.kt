package com.ioscastaway.crossappagent.platform

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * The system-bound service that gives this app eyes and hands on other apps.
 *
 * Lifecycle: the user flips the toggle in Settings > Accessibility; system_server binds us and keeps
 * the process alive for as long as the toggle is on. We cannot start or stop this from code.
 *
 * Everything here is a thin wrapper over the platform API. Policy (what to tap, when to ask the user)
 * lives in the agent layer, not here.
 */
class AgentAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AgentA11y"

        private val _instance = MutableStateFlow<AgentAccessibilityService?>(null)
        /** Non-null while the service is bound. Observed by the UI to show the enable/disable state. */
        val instance: StateFlow<AgentAccessibilityService?> = _instance.asStateFlow()
    }

    @Volatile private var lastEventAt: Long = 0L
    @Volatile private var lastPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "connected; flags=${serviceInfo?.flags}")
        _instance.value = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        lastEventAt = SystemClock.uptimeMillis()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { lastPackage = it }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        _instance.value = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- reading

    /** Snapshot of the active window's tree. Cheap enough to call after every action. */
    fun snapshot(): ScreenSnapshot {
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString() ?: lastPackage
        return AccessibilityTreeReader.read(root, pkg, isKeyboardVisible())
    }

    private fun isKeyboardVisible(): Boolean =
        try {
            windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        } catch (t: Throwable) {
            false
        }

    /**
     * Wait until the target app has stopped emitting accessibility events for [quietMs], or until
     * [timeoutMs] passes. A crude but effective "page loaded" signal.
     */
    suspend fun waitForIdle(quietMs: Long = 500, timeoutMs: Long = 4000) {
        delay(250) // give the first event after an action a chance to arrive
        val start = SystemClock.uptimeMillis()
        while (true) {
            val now = SystemClock.uptimeMillis()
            val sinceEvent = now - lastEventAt
            if (sinceEvent >= quietMs || now - start >= timeoutMs) return
            delay(minOf(quietMs - sinceEvent, 100L).coerceAtLeast(20L))
        }
    }

    // ---------------------------------------------------------------- gestures

    suspend fun tapAt(x: Int, y: Int, durationMs: Long = 60): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return dispatch(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
        )
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        return dispatch(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
        )
    }

    private suspend fun dispatch(gesture: GestureDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            val accepted = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(g: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null,
            )
            if (!accepted && cont.isActive) cont.resume(false)
        }

    // ---------------------------------------------------------------- screenshot

    /**
     * JPEG screenshot of the default display, long edge scaled to [maxEdge] px to keep the multimodal
     * request small. Returns null on failure — including FLAG_SECURE windows and the platform's
     * minimum interval between screenshots.
     */
    suspend fun screenshotJpeg(maxEdge: Int = 1280, quality: Int = 80): ByteArray? =
        suspendCancellableCoroutine { cont ->
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val buffer = result.hardwareBuffer
                        val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        buffer.close()
                        if (hw == null) {
                            if (cont.isActive) cont.resume(null); return
                        }
                        // Hardware bitmaps can't be read back directly; copy to software memory first.
                        val sw = hw.copy(Bitmap.Config.ARGB_8888, false)
                        hw.recycle()
                        val scale = maxEdge.toFloat() / maxOf(sw.width, sw.height)
                        val scaled = if (scale < 1f) {
                            Bitmap.createScaledBitmap(
                                sw, (sw.width * scale).toInt(), (sw.height * scale).toInt(), true
                            ).also { sw.recycle() }
                        } else sw
                        val out = ByteArrayOutputStream()
                        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                        scaled.recycle()
                        if (cont.isActive) cont.resume(out.toByteArray())
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "takeScreenshot failed: $errorCode")
                        if (cont.isActive) cont.resume(null)
                    }
                },
            )
        }
}
