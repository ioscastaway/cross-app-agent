package com.ioscastaway.crossappagent.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenSerializerTest {

    private fun node(
        ref: Int,
        depth: Int = 0,
        cls: String = "android.widget.TextView",
        text: String? = null,
        desc: String? = null,
        id: String? = null,
        clickable: Boolean = false,
        editable: Boolean = false,
        password: Boolean = false,
    ) = UiNode(
        ref = ref, depth = depth, className = cls, text = text, contentDescription = desc, resourceId = id,
        bounds = Bounds(0, 0, 100, 40), clickable = clickable, editable = editable, password = password,
    )

    @Test
    fun `formats header, indentation, role, flags and center`() {
        val out = ScreenSerializer.format(
            "com.example.app",
            listOf(
                node(1, cls = "androidx.recyclerview.widget.RecyclerView", id = "list"),
                node(2, depth = 1, text = "Hello", clickable = true),
            ),
        )
        val lines = out.trimEnd().lines()
        assertEquals("app: com.example.app", lines[0])
        assertEquals("[1] List id=list @50,20", lines[1])
        assertEquals("  [2] Text \"Hello\" {clickable} @50,20", lines[2])
    }

    @Test
    fun `redacts password fields before anything leaves the device`() {
        val out = ScreenSerializer.format(
            "com.bank",
            listOf(node(1, cls = "android.widget.EditText", text = "hunter2", editable = true, password = true)),
        )
        assertFalse(out.contains("hunter2"))
        assertTrue(out.contains("{editable,password}"))
    }

    @Test
    fun `truncates long text and drops duplicate description`() {
        val long = "x".repeat(200)
        val out = ScreenSerializer.format("p", listOf(node(1, text = long, desc = long)))
        assertTrue(out.contains("…"))
        assertFalse(out.contains("desc="))
        assertTrue(out.lines()[1].length < 120)
    }

    @Test
    fun `empty tree tells the model to use a screenshot`() {
        val out = ScreenSerializer.format("p", emptyList())
        assertTrue(out.contains("screenshot"))
    }

    @Test
    fun `role mapping collapses framework class names`() {
        assertEquals("Input", ScreenSerializer.role("android.widget.EditText"))
        assertEquals("Button", ScreenSerializer.role("com.google.android.material.button.MaterialButton"))
        assertEquals("Group", ScreenSerializer.role("android.widget.FrameLayout"))
        assertEquals("Node", ScreenSerializer.role(""))
    }
}
