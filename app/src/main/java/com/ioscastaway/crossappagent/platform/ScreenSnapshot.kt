package com.ioscastaway.crossappagent.platform

import android.view.accessibility.AccessibilityNodeInfo

/**
 * One reading of the screen: the flattened nodes plus the live AccessibilityNodeInfo behind each ref.
 * Refs are only valid against the snapshot they came from; the controller re-reads after every action.
 */
class ScreenSnapshot(
    val packageName: String?,
    val nodes: List<UiNode>,
    val refs: Map<Int, AccessibilityNodeInfo>,
    val keyboardVisible: Boolean,
    val capturedAt: Long,
) {
    fun toPromptText(): String = ScreenSerializer.format(packageName, nodes, keyboardVisible)

    fun node(ref: Int): AccessibilityNodeInfo? = refs[ref]
    fun uiNode(ref: Int): UiNode? = nodes.firstOrNull { it.ref == ref }

    companion object {
        fun empty(packageName: String? = null) =
            ScreenSnapshot(packageName, emptyList(), emptyMap(), false, System.currentTimeMillis())
    }
}
