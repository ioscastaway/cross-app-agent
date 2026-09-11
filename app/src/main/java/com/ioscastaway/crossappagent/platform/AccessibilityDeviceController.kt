package com.ioscastaway.crossappagent.platform

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import kotlinx.coroutines.delay

/**
 * [DeviceController] backed by [AgentAccessibilityService].
 *
 * Action strategy, in order of preference:
 *  1. Node actions (`performAction`) — no coordinates, survives layout changes.
 *  2. Walk up to the nearest clickable ancestor — Compose and RecyclerView items often put the
 *     click handler on a parent of the node that carries the text.
 *  3. Coordinate gesture at the node's center — the universal fallback.
 */
class AccessibilityDeviceController(
    private val appContext: Context,
    private val service: () -> AgentAccessibilityService?,
) : DeviceController {

    @Volatile private var last: ScreenSnapshot = ScreenSnapshot.empty()

    private fun svc(): AgentAccessibilityService =
        service() ?: throw IllegalStateException(
            "Accessibility service is not enabled. Turn on 'Cross-App Agent' in Settings > Accessibility."
        )

    override fun isAvailable(): Boolean = service() != null

    override suspend fun readScreen(): ScreenSnapshot = svc().snapshot().also { last = it }

    override fun lastScreen(): ScreenSnapshot? = last.takeIf { it.nodes.isNotEmpty() }

    override suspend fun waitForIdle() = svc().waitForIdle()

    // ---------------------------------------------------------------- apps

    @Suppress("DEPRECATION")
    override fun listApps(): List<AppEntry> {
        val pm = appContext.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        // Visible only because of the <queries> block in the manifest (API 30+ package visibility).
        return pm.queryIntentActivities(intent, 0)
            .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    override suspend fun launchApp(query: String): ActionResult {
        val apps = listApps()
        val q = query.trim().lowercase()
        val match = apps.firstOrNull { it.label.lowercase() == q }
            ?: apps.firstOrNull { it.packageName.lowercase() == q }
            ?: apps.firstOrNull { it.label.lowercase().contains(q) }
            ?: apps.firstOrNull { q.contains(it.label.lowercase()) && it.label.length >= 3 }
            ?: apps.firstOrNull { it.packageName.lowercase().contains(q) }
            ?: return ActionResult.fail(
                "No installed app matches \"$query\". Installed: " + apps.joinToString { it.label }
            )
        val intent = appContext.packageManager.getLaunchIntentForPackage(match.packageName)
            ?: return ActionResult.fail("${match.label} has no launchable activity")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        // Starting an activity from the background is normally blocked since Android 10. Apps with a
        // system-bound service (an enabled AccessibilityService counts) are exempt — one more reason
        // this whole experiment hangs off the a11y service.
        svc().startActivity(intent)
        return ActionResult.ok("Launched ${match.label} (${match.packageName})")
    }

    // ---------------------------------------------------------------- node actions

    private fun resolve(ref: Int): Pair<AccessibilityNodeInfo, UiNode>? {
        val node = last.node(ref) ?: return null
        val ui = last.uiNode(ref) ?: return null
        return node to ui
    }

    override suspend fun tap(ref: Int): ActionResult {
        val (node, ui) = resolve(ref) ?: return staleRef(ref)
        node.refresh()
        var target: AccessibilityNodeInfo? = node
        var hops = 0
        while (target != null && !target.isClickable && hops < 4) {
            target = target.parent; hops++
        }
        if (target != null && target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return ActionResult.ok("Tapped [$ref] ${describe(ui)}" + if (hops > 0) " (via clickable ancestor)" else "")
        }
        val ok = svc().tapAt(ui.bounds.centerX, ui.bounds.centerY)
        return if (ok) ActionResult.ok("Tapped [$ref] ${describe(ui)} by coordinates")
        else ActionResult.fail("Could not tap [$ref] ${describe(ui)}")
    }

    override suspend fun longPress(ref: Int): ActionResult {
        val (node, ui) = resolve(ref) ?: return staleRef(ref)
        node.refresh()
        if (node.isLongClickable && node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
            return ActionResult.ok("Long-pressed [$ref] ${describe(ui)}")
        }
        val ok = svc().tapAt(ui.bounds.centerX, ui.bounds.centerY, durationMs = 600)
        return if (ok) ActionResult.ok("Long-pressed [$ref] by coordinates")
        else ActionResult.fail("Could not long-press [$ref]")
    }

    override suspend fun tapAt(x: Int, y: Int): ActionResult =
        if (svc().tapAt(x, y)) ActionResult.ok("Tapped at ($x, $y)") else ActionResult.fail("Gesture rejected at ($x, $y)")

    override suspend fun typeText(ref: Int, text: String, submit: Boolean): ActionResult {
        val (node, ui) = resolve(ref) ?: return staleRef(ref)
        node.refresh()
        if (!node.isEditable) {
            // Some Compose text fields only report editable after focus; give it one chance.
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            delay(150)
            node.refresh()
        }
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        var how = "ACTION_SET_TEXT"
        var ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!ok) {
            // WebViews and some custom fields ignore SET_TEXT; paste is the next most reliable path.
            val cm = appContext.getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("agent", text))
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            delay(100)
            ok = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            how = "ACTION_PASTE"
        }
        if (!ok) return ActionResult.fail("Could not enter text into [$ref] ${describe(ui)}")

        if (submit) {
            delay(100)
            val entered = node.performAction(AccessibilityAction.ACTION_IME_ENTER.id)
            how += if (entered) " + IME_ENTER" else " (IME_ENTER not supported by this field)"
        }
        return ActionResult.ok("Typed \"${text.take(40)}\" into [$ref] via $how")
    }

    override suspend fun scroll(ref: Int?, direction: ScrollDirection): ActionResult {
        val target: AccessibilityNodeInfo? = when {
            ref != null -> last.node(ref)?.also { it.refresh() }
            else -> last.nodes.firstOrNull { it.scrollable }?.let { last.node(it.ref) }?.also { it.refresh() }
        }
        val action = when (direction) {
            ScrollDirection.DOWN -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            ScrollDirection.UP -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            ScrollDirection.LEFT -> AccessibilityAction.ACTION_SCROLL_LEFT.id
            ScrollDirection.RIGHT -> AccessibilityAction.ACTION_SCROLL_RIGHT.id
        }
        if (target != null && target.isScrollable && target.performAction(action)) {
            return ActionResult.ok("Scrolled ${direction.name.lowercase()} via node action")
        }
        // Fallback: swipe in the middle of the screen (or of the node if we have one).
        val b = (ref?.let { last.uiNode(it)?.bounds })
            ?: last.nodes.maxByOrNull { it.bounds.width.toLong() * it.bounds.height }?.bounds
            ?: return ActionResult.fail("Nothing to scroll")
        val cx = b.centerX; val cy = b.centerY
        val dx = b.width / 3; val dy = b.height / 3
        val ok = when (direction) {
            ScrollDirection.DOWN -> svc().swipe(cx, cy + dy, cx, cy - dy)
            ScrollDirection.UP -> svc().swipe(cx, cy - dy, cx, cy + dy)
            ScrollDirection.LEFT -> svc().swipe(cx + dx, cy, cx - dx, cy)
            ScrollDirection.RIGHT -> svc().swipe(cx - dx, cy, cx + dx, cy)
        }
        return if (ok) ActionResult.ok("Scrolled ${direction.name.lowercase()} via swipe gesture")
        else ActionResult.fail("Swipe gesture rejected")
    }

    override suspend fun back(): ActionResult =
        if (svc().performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) ActionResult.ok("Pressed back")
        else ActionResult.fail("Back failed")

    override suspend fun home(): ActionResult =
        if (svc().performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)) ActionResult.ok("Went home")
        else ActionResult.fail("Home failed")

    override suspend fun screenshot(): ByteArray? = svc().screenshotJpeg()

    // ---------------------------------------------------------------- helpers

    private fun staleRef(ref: Int) = ActionResult.fail(
        "Ref [$ref] is not on the current screen. Call read_screen and use a ref from the new result."
    )

    private fun describe(ui: UiNode): String =
        ui.text?.let { "\"${it.take(30)}\"" } ?: ui.contentDescription?.let { "\"${it.take(30)}\"" }
        ?: ui.resourceId?.let { "id=$it" } ?: ScreenSerializer.role(ui.className)
}
