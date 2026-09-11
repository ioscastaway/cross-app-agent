package com.ioscastaway.crossappagent.platform

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Walks an AccessibilityNodeInfo tree and produces a [ScreenSnapshot].
 *
 * How this works under the hood (the part that is interesting coming from iOS): every `getChild()` /
 * property read here is a Binder call into the *target app's* process, answered by its
 * ViewRootImpl -> AccessibilityInteractionController on the target's UI thread. There is no
 * cross-process view hierarchy API like this on iOS for third-party apps.
 *
 * Collapsing rule: a node is emitted if it is visible and has text, a content description, an
 * interactive flag, or is a leaf with a resource id. Pure layout containers are skipped, but their
 * children keep the parent's indentation so structure is still readable.
 */
object AccessibilityTreeReader {

    private const val MAX_NODES = 400

    fun read(
        root: AccessibilityNodeInfo?,
        packageName: String?,
        keyboardVisible: Boolean,
    ): ScreenSnapshot {
        val nodes = ArrayList<UiNode>(128)
        val refs = HashMap<Int, AccessibilityNodeInfo>(128)
        if (root != null) {
            var nextRef = 1
            val rect = Rect()

            fun walk(node: AccessibilityNodeInfo, depth: Int) {
                if (nodes.size >= MAX_NODES) return
                if (!node.isVisibleToUser) return   // off-screen subtrees are noise for the model

                node.getBoundsInScreen(rect)
                val text = node.text?.toString()?.takeIf { it.isNotBlank() }
                val desc = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                val resId = node.viewIdResourceName?.substringAfter(":id/", missingDelimiterValue = "")
                    ?.takeIf { it.isNotBlank() }
                val interactive = node.isClickable || node.isLongClickable || node.isEditable ||
                    node.isScrollable || node.isCheckable
                val emit = text != null || desc != null || interactive || (resId != null && node.childCount == 0)

                @Suppress("DEPRECATION") // API 36 adds tri-state getChecked(); minSdk 30 needs the boolean
                val checked = node.isChecked
                var childDepth = depth
                if (emit) {
                    val ref = nextRef++
                    nodes += UiNode(
                        ref = ref,
                        depth = depth,
                        className = node.className?.toString() ?: "",
                        text = text,
                        contentDescription = desc,
                        resourceId = resId,
                        bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom),
                        clickable = node.isClickable,
                        longClickable = node.isLongClickable,
                        editable = node.isEditable,
                        scrollable = node.isScrollable,
                        checkable = node.isCheckable,
                        checked = checked,
                        focused = node.isFocused,
                        selected = node.isSelected,
                        enabled = node.isEnabled,
                        password = node.isPassword,
                    )
                    refs[ref] = node
                    childDepth = depth + 1
                }
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    walk(child, childDepth)
                }
            }

            walk(root, 0)
        }
        return ScreenSnapshot(packageName, nodes, refs, keyboardVisible, System.currentTimeMillis())
    }
}
