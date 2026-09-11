package com.ioscastaway.crossappagent.platform

data class ActionResult(val ok: Boolean, val message: String) {
    companion object {
        fun ok(msg: String) = ActionResult(true, msg)
        fun fail(msg: String) = ActionResult(false, msg)
    }
}

data class AppEntry(val label: String, val packageName: String)

enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }

/**
 * What the agent needs from the device. Kept as an interface so the agent loop can later be driven
 * by a fake (JVM tests) or, for the iOS comparison experiment, by a WebDriverAgent bridge.
 */
interface DeviceController {
    /** Whether the underlying accessibility service is bound right now. */
    fun isAvailable(): Boolean

    suspend fun readScreen(): ScreenSnapshot

    /** The most recent snapshot returned by [readScreen], without re-reading (refs stay valid). */
    fun lastScreen(): ScreenSnapshot?
    suspend fun waitForIdle()

    fun listApps(): List<AppEntry>
    suspend fun launchApp(query: String): ActionResult

    suspend fun tap(ref: Int): ActionResult
    suspend fun longPress(ref: Int): ActionResult
    suspend fun tapAt(x: Int, y: Int): ActionResult
    suspend fun typeText(ref: Int, text: String, submit: Boolean): ActionResult
    suspend fun scroll(ref: Int?, direction: ScrollDirection): ActionResult

    suspend fun back(): ActionResult
    suspend fun home(): ActionResult

    /** JPEG bytes or null if the platform refused (secure window, rate limit, no service). */
    suspend fun screenshot(): ByteArray?
}
