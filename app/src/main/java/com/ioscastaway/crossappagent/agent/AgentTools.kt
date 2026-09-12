package com.ioscastaway.crossappagent.agent

import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Tool

/**
 * The agent's hands, described as Claude tool definitions.
 *
 * Every action tool returns the fresh screen after the action, so the model rarely needs a separate
 * `read_screen` call — one round trip per step instead of two.
 */
object AgentTools {

    const val READ_SCREEN = "read_screen"
    const val LAUNCH_APP = "launch_app"
    const val TAP = "tap"
    const val LONG_PRESS = "long_press"
    const val TAP_AT = "tap_at"
    const val TYPE_TEXT = "type_text"
    const val SCROLL = "scroll"
    const val PRESS_BACK = "press_back"
    const val PRESS_HOME = "press_home"
    const val WAIT = "wait"
    const val SCREENSHOT = "screenshot"
    const val ASK_USER = "ask_user"
    const val DONE = "done"

    /** Order is fixed on purpose: the tool list is part of the cached prompt prefix. */
    val all: List<Tool> = listOf(
        tool(
            READ_SCREEN,
            "Read the current screen as a list of UI nodes with [ref] handles. Use when you need a fresh view " +
                "without acting, e.g. after wait.",
        ),
        tool(
            LAUNCH_APP,
            "Open an installed app by its name (as shown on the launcher) or package name. Returns the app's " +
                "first screen.",
            mapOf("name" to prop("string", "App label or package name, e.g. \"Messages\" or \"com.kakao.talk\"")),
            required = listOf("name"),
        ),
        tool(
            TAP,
            "Tap a node by its [ref] from the latest screen. Prefer this over tap_at.",
            mapOf("ref" to prop("integer", "The [ref] number of the node")),
            required = listOf("ref"),
        ),
        tool(
            LONG_PRESS,
            "Long-press a node by [ref] (context menus, selection).",
            mapOf("ref" to prop("integer", "The [ref] number of the node")),
            required = listOf("ref"),
        ),
        tool(
            TAP_AT,
            "Tap at absolute screen pixel coordinates. Only when no [ref] covers the target (canvas, games, " +
                "unlabelled icons). Coordinates come from a node's @x,y suffix or from a screenshot.",
            mapOf("x" to prop("integer", "X in pixels"), "y" to prop("integer", "Y in pixels")),
            required = listOf("x", "y"),
        ),
        tool(
            TYPE_TEXT,
            "Replace the text of an editable node [ref] with the given text. Set submit=true to press the " +
                "keyboard's action key (search/send) afterwards.",
            mapOf(
                "ref" to prop("integer", "The [ref] of an {editable} node"),
                "text" to prop("string", "Text to enter"),
                "submit" to prop("boolean", "Press the IME action key after typing. Default false."),
            ),
            required = listOf("ref", "text"),
        ),
        tool(
            SCROLL,
            "Scroll a {scrollable} node, or the main scrollable area if ref is omitted.",
            mapOf(
                "ref" to prop("integer", "Optional [ref] of a scrollable node"),
                "direction" to mapOf(
                    "type" to "string",
                    "enum" to listOf("up", "down", "left", "right"),
                    "description" to "Content direction: 'down' reveals content further down the list.",
                ),
            ),
            required = listOf("direction"),
        ),
        tool(PRESS_BACK, "Press the system Back button."),
        tool(PRESS_HOME, "Go to the home screen."),
        tool(
            WAIT,
            "Wait for content to load, then read the screen.",
            mapOf("ms" to prop("integer", "Milliseconds to wait, 200–5000")),
            required = listOf("ms"),
        ),
        tool(
            SCREENSHOT,
            "Take a screenshot and look at it. Use when the node list is empty or ambiguous (custom-drawn UI, " +
                "images, maps), or to verify a visual state. Costs more than read_screen.",
        ),
        tool(
            ASK_USER,
            "Ask the user a question and wait for the answer. Required before any irreversible action " +
                "(sending, paying, deleting, posting) and whenever the task is ambiguous. Ask in the " +
                "language the user used.",
            mapOf(
                "question" to prop("string", "A short, specific question"),
                "options" to mapOf(
                    "type" to "array",
                    "items" to mapOf("type" to "string"),
                    "description" to "The answers you expect, when the answer is a small closed set: " +
                        "a confirmation, or a choice between things already on screen. Each option is a " +
                        "button, so keep them under about 20 characters and in the user's language. " +
                        "Omit this entirely when the answer is open — a name, an amount, a search term — " +
                        "and the user will speak or type it instead. Never invent options for an open question.",
                ),
            ),
            required = listOf("question"),
        ),
        tool(
            DONE,
            "Finish the task. Call this exactly once when the goal is reached or cannot be reached.",
            mapOf(
                "success" to prop("boolean", "Whether the task was completed"),
                "summary" to prop("string", "One or two sentences on what was done or why it failed"),
            ),
            required = listOf("success", "summary"),
        ),
    )

    private fun prop(type: String, description: String): Map<String, Any> =
        mapOf("type" to type, "description" to description)

    private fun tool(
        name: String,
        description: String,
        properties: Map<String, Map<String, Any>> = emptyMap(),
        required: List<String> = emptyList(),
    ): Tool {
        val props = Tool.InputSchema.Properties.builder()
        for ((key, schema) in properties) props.putAdditionalProperty(key, JsonValue.from(schema))
        return Tool.builder()
            .name(name)
            .description(description)
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(props.build())
                    .required(required)
                    .build()
            )
            .build()
    }
}
