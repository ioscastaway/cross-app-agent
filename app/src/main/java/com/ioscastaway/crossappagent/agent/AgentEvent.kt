package com.ioscastaway.crossappagent.agent

import kotlinx.coroutines.CompletableDeferred

/** What the UI (or a log) sees while the agent runs. */
sealed interface AgentEvent {
    data class Thought(val text: String) : AgentEvent
    data class ToolCall(val name: String, val input: String) : AgentEvent
    data class ToolResult(val name: String, val ok: Boolean, val summary: String) : AgentEvent
    data class Screen(val text: String) : AgentEvent

    /**
     * The agent is blocked until [answer] is completed by the user.
     *
     * [options] are the answers the model expects, when it knows them ("yes"/"no", a list of
     * recipients). Empty means the question is open and the answer has to be spoken or typed.
     */
    data class Question(
        val question: String,
        val options: List<String>,
        val answer: CompletableDeferred<String>,
    ) : AgentEvent

    data class Finished(val success: Boolean, val summary: String) : AgentEvent
    data class Error(val message: String) : AgentEvent
}
