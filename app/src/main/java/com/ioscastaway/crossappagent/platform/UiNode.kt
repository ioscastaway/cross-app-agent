package com.ioscastaway.crossappagent.platform

/** Screen-space rectangle in pixels. Kept free of android.graphics so serializer tests run on the JVM. */
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

/**
 * A flattened, model-friendly view of one AccessibilityNodeInfo.
 *
 * `ref` is the handle the model uses to act on the node (the Android equivalent of a DOM `ref_N`).
 * `depth` is the indentation level after container-collapsing, not the raw tree depth.
 */
data class UiNode(
    val ref: Int,
    val depth: Int,
    val className: String,
    val text: String?,
    val contentDescription: String?,
    val resourceId: String?,
    val bounds: Bounds,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val focused: Boolean = false,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val password: Boolean = false,
) {
    val interactive: Boolean
        get() = clickable || longClickable || editable || scrollable || checkable
}
