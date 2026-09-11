package com.ioscastaway.crossappagent.platform

/**
 * Turns a list of [UiNode]s into the compact text the model reads.
 *
 * Design goals, in order: small token footprint, stable across identical screens (so prompt caching
 * has a chance), and no coordinates unless they are useful for a gesture fallback.
 *
 * Example output:
 * ```
 * app: com.google.android.apps.messaging
 * [1] Input id=search_box hint="Search conversations" {editable}
 * [2] List id=conversation_list {scrollable}
 *   [3] View "Mom · Are you coming for dinner?" {clickable}
 * [4] Button "Start chat" {clickable}
 * ```
 */
object ScreenSerializer {

    private const val MAX_TEXT = 80

    fun format(packageName: String?, nodes: List<UiNode>, keyboardVisible: Boolean = false): String {
        val sb = StringBuilder()
        sb.append("app: ").append(packageName ?: "unknown").append('\n')
        if (keyboardVisible) sb.append("keyboard: visible\n")
        if (nodes.isEmpty()) {
            sb.append("(no accessible nodes — the app may draw its own UI; use screenshot)\n")
            return sb.toString()
        }
        for (n in nodes) {
            repeat(n.depth) { sb.append("  ") }
            sb.append('[').append(n.ref).append("] ").append(role(n.className))
            val text = n.text?.let { if (n.password) "••••••" else truncate(it) }
            if (text != null) sb.append(" \"").append(text).append('"')
            n.contentDescription?.let { d ->
                if (d != n.text) sb.append(" desc=\"").append(truncate(d)).append('"')
            }
            n.resourceId?.let { sb.append(" id=").append(it) }
            val flags = flags(n)
            if (flags.isNotEmpty()) sb.append(" {").append(flags.joinToString(",")).append('}')
            sb.append(" @").append(n.bounds.centerX).append(',').append(n.bounds.centerY)
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun flags(n: UiNode): List<String> = buildList {
        if (n.clickable) add("clickable")
        if (n.longClickable && !n.clickable) add("long-clickable")
        if (n.editable) add("editable")
        if (n.scrollable) add("scrollable")
        if (n.checkable) add(if (n.checked) "checked" else "unchecked")
        if (n.selected) add("selected")
        if (n.focused) add("focused")
        if (!n.enabled) add("disabled")
        if (n.password) add("password")
    }

    private fun truncate(s: String): String {
        val one = s.replace('\n', ' ').replace("\"", "'").trim()
        return if (one.length <= MAX_TEXT) one else one.take(MAX_TEXT - 1) + "…"
    }

    /** Collapse verbose Android class names into short roles the model already understands. */
    fun role(className: String): String {
        val simple = className.substringAfterLast('.')
        return when {
            simple == "EditText" || simple.endsWith("AutoCompleteTextView") || simple == "SearchView" -> "Input"
            simple == "TextView" -> "Text"
            simple == "Button" || simple == "ImageButton" || simple.endsWith("Button") -> "Button"
            simple == "ImageView" -> "Image"
            simple == "CheckBox" || simple == "Switch" || simple.endsWith("CompoundButton") -> "Toggle"
            simple == "RecyclerView" || simple == "ListView" || simple == "ScrollView" ||
                simple == "HorizontalScrollView" || simple == "ViewPager" || simple.endsWith("ViewPager2") -> "List"
            simple == "WebView" -> "Web"
            simple == "FrameLayout" || simple == "LinearLayout" || simple == "RelativeLayout" ||
                simple == "ConstraintLayout" || simple == "ViewGroup" -> "Group"
            simple == "View" -> "View"
            simple.isEmpty() -> "Node"
            else -> simple
        }
    }
}
