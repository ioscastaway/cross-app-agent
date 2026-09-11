package com.ioscastaway.crossappagent.agent

import android.util.Base64
import com.anthropic.client.AnthropicClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUseBlock
import com.ioscastaway.crossappagent.platform.ActionResult
import com.ioscastaway.crossappagent.platform.DeviceController
import com.ioscastaway.crossappagent.platform.ScrollDirection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import kotlin.jvm.optionals.getOrNull

/**
 * The observe → think → act loop.
 *
 * This is a manual tool-use loop rather than the SDK's tool runner because each step has to suspend
 * for Android work (gesture callbacks, idle detection) and sometimes for the user (ask_user,
 * confirmation of risky taps). The loop is append-only: every assistant message is echoed back
 * verbatim and every tool_use gets exactly one tool_result.
 */
class ClaudeAgent(
    private val client: AnthropicClient,
    private val device: DeviceController,
    private val model: String,
    private val maxSteps: Int = 30,
    private val effort: OutputConfig.Effort = OutputConfig.Effort.MEDIUM,
) {

    fun run(task: String): Flow<AgentEvent> = channelFlow {
        try {
            runLoop(task)
        } catch (t: Throwable) {
            send(AgentEvent.Error(t.message ?: t.toString()))
        }
    }

    private suspend fun ProducerScope<AgentEvent>.runLoop(task: String) {
        if (!device.isAvailable()) {
            send(AgentEvent.Error("Accessibility service is not enabled."))
            return
        }

        val apps = device.listApps().joinToString(", ") { it.label }
        val firstScreen = device.readScreen().toPromptText()
        send(AgentEvent.Screen(firstScreen))

        val messages = mutableListOf<MessageParam>(
            MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(
                    "Task: $task\n\n" +
                        "Installed apps you can launch: $apps\n\n" +
                        "Current screen:\n$firstScreen"
                )
                .build()
        )

        repeat(maxSteps) { step ->
            val response = withContext(Dispatchers.IO) { client.messages().create(buildParams(messages)) }
            messages += response.toParam()

            response.content().forEach { block ->
                block.text().getOrNull()?.let { if (it.text().isNotBlank()) send(AgentEvent.Thought(it.text())) }
            }

            val stop = response.stopReason().getOrNull()
            if (stop == StopReason.REFUSAL) {
                send(AgentEvent.Error("The model declined to continue this task."))
                return
            }

            val toolUses = response.content().mapNotNull { it.toolUse().getOrNull() }
            if (toolUses.isEmpty()) {
                // No tool call and no `done`: treat the text as the final answer.
                val text = response.content().mapNotNull { it.text().getOrNull()?.text() }.joinToString("\n")
                send(AgentEvent.Finished(success = true, summary = text.ifBlank { "Finished." }))
                return
            }

            val results = mutableListOf<ContentBlockParam>()
            var finished: AgentEvent.Finished? = null
            for (tu in toolUses) {
                val outcome = execute(tu, step)
                results += ContentBlockParam.ofToolResult(outcome.block)
                if (outcome.finished != null) finished = outcome.finished
            }
            messages += MessageParam.builder()
                .role(MessageParam.Role.USER)
                .contentOfBlockParams(results)
                .build()

            if (finished != null) {
                send(finished)
                return
            }
        }
        send(AgentEvent.Error("Stopped after $maxSteps steps without calling done."))
    }

    private fun buildParams(messages: List<MessageParam>): MessageCreateParams {
        val b = MessageCreateParams.builder()
            .model(model)
            .maxTokens(4096L)
            // Stable prefix (system + tools) is cached; the growing message list comes after it.
            .systemOfTextBlockParams(
                listOf(
                    TextBlockParam.builder()
                        .text(SYSTEM_PROMPT)
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build()
                )
            )
            .outputConfig(OutputConfig.builder().effort(effort).build())
        AgentTools.all.forEach { b.addTool(it) }
        messages.forEach { b.addMessage(it) }
        return b.build()
    }

    // ---------------------------------------------------------------- tool execution

    private class Outcome(val block: ToolResultBlockParam, val finished: AgentEvent.Finished? = null)

    private suspend fun ProducerScope<AgentEvent>.execute(tu: ToolUseBlock, step: Int): Outcome {
        val name = tu.name()
        val input = tu._input().asObject().orElse(emptyMap())
        send(AgentEvent.ToolCall(name, tu._input().toString()))

        suspend fun text(ok: Boolean, body: String): Outcome {
            send(AgentEvent.ToolResult(name, ok, body.lineSequence().first().take(120)))
            return Outcome(
                ToolResultBlockParam.builder().toolUseId(tu.id()).content(body).isError(!ok).build()
            )
        }

        suspend fun withScreen(result: ActionResult): Outcome {
            if (!result.ok) return text(false, result.message)
            device.waitForIdle()
            val screen = device.readScreen().toPromptText()
            send(AgentEvent.Screen(screen))
            return text(true, result.message + "\n\nScreen after action:\n" + screen)
        }

        return try {
            when (name) {
                AgentTools.READ_SCREEN -> {
                    val screen = device.readScreen().toPromptText()
                    send(AgentEvent.Screen(screen))
                    text(true, screen)
                }

                AgentTools.LAUNCH_APP -> withScreen(device.launchApp(input.str("name") ?: ""))

                AgentTools.TAP -> {
                    val ref = input.int("ref") ?: return text(false, "ref is required")
                    if (!confirmIfRisky(ref)) return text(false, "User declined this tap. Ask what to do instead.")
                    withScreen(device.tap(ref))
                }

                AgentTools.LONG_PRESS -> withScreen(device.longPress(input.int("ref") ?: return text(false, "ref is required")))

                AgentTools.TAP_AT -> withScreen(
                    device.tapAt(
                        input.int("x") ?: return text(false, "x is required"),
                        input.int("y") ?: return text(false, "y is required"),
                    )
                )

                AgentTools.TYPE_TEXT -> withScreen(
                    device.typeText(
                        input.int("ref") ?: return text(false, "ref is required"),
                        input.str("text") ?: "",
                        input.bool("submit") ?: false,
                    )
                )

                AgentTools.SCROLL -> {
                    val dir = when (input.str("direction")?.lowercase()) {
                        "up" -> ScrollDirection.UP
                        "left" -> ScrollDirection.LEFT
                        "right" -> ScrollDirection.RIGHT
                        else -> ScrollDirection.DOWN
                    }
                    withScreen(device.scroll(input.int("ref"), dir))
                }

                AgentTools.PRESS_BACK -> withScreen(device.back())
                AgentTools.PRESS_HOME -> withScreen(device.home())

                AgentTools.WAIT -> {
                    delay((input.int("ms") ?: 1000).coerceIn(200, 5000).toLong())
                    val screen = device.readScreen().toPromptText()
                    send(AgentEvent.Screen(screen))
                    text(true, screen)
                }

                AgentTools.SCREENSHOT -> {
                    val jpeg = device.screenshot()
                        ?: return text(false, "Screenshot unavailable (secure window or rate limit). Rely on read_screen.")
                    send(AgentEvent.ToolResult(name, true, "screenshot ${jpeg.size / 1024} KB"))
                    val image = ImageBlockParam.builder()
                        .source(
                            Base64ImageSource.builder()
                                .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                                .data(Base64.encodeToString(jpeg, Base64.NO_WRAP))
                                .build()
                        )
                        .build()
                    Outcome(
                        ToolResultBlockParam.builder()
                            .toolUseId(tu.id())
                            .contentOfBlocks(
                                listOf(
                                    ToolResultBlockParam.Content.Block.ofText("Screenshot of the current screen:"),
                                    ToolResultBlockParam.Content.Block.ofImage(image),
                                )
                            )
                            .build()
                    )
                }

                AgentTools.ASK_USER -> {
                    val answer = ask(input.str("question") ?: "Please clarify.")
                    text(true, "User answered: $answer")
                }

                AgentTools.DONE -> {
                    val success = input.bool("success") ?: true
                    val summary = input.str("summary") ?: ""
                    val out = text(true, "Acknowledged.")
                    Outcome(out.block, AgentEvent.Finished(success, summary))
                }

                else -> text(false, "Unknown tool: $name")
            }
        } catch (t: IllegalStateException) {
            text(false, t.message ?: "Device unavailable")
        } catch (t: Exception) {
            text(false, "Tool $name failed: ${t.message}")
        }
    }

    private suspend fun ProducerScope<AgentEvent>.ask(question: String): String {
        val deferred = CompletableDeferred<String>()
        send(AgentEvent.Question(question, deferred))
        return deferred.await()
    }

    /**
     * Second line of defence behind the system prompt: if the node the model wants to tap looks like an
     * irreversible action, require an explicit yes from the user first.
     */
    private suspend fun ProducerScope<AgentEvent>.confirmIfRisky(ref: Int): Boolean {
        val node = device.lastScreen()?.uiNode(ref) ?: return true
        val label = listOfNotNull(node.text, node.contentDescription, node.resourceId).joinToString(" ").lowercase()
        if (label.isBlank() || RISKY.none { label.contains(it) }) return true
        val answer = ask("About to tap \"${label.take(40)}\". This may be irreversible. Proceed? (yes/no)")
        return answer.trim().lowercase().let { it.startsWith("y") || it == "ok" || it == "네" || it == "응" || it == "예" }
    }

    // ---------------------------------------------------------------- helpers

    private fun Map<String, JsonValue>.str(key: String): String? = this[key]?.asString()?.getOrNull()
    private fun Map<String, JsonValue>.int(key: String): Int? = this[key]?.asNumber()?.getOrNull()?.toInt()
    private fun Map<String, JsonValue>.bool(key: String): Boolean? = this[key]?.asBoolean()?.getOrNull()

    companion object {
        private val RISKY = listOf(
            "send", "pay", "purchase", "buy", "order", "delete", "remove", "confirm", "transfer", "post", "publish",
            "결제", "송금", "이체", "전송", "보내기", "삭제", "구매", "주문", "확인", "게시",
        )

        /** Frozen on purpose: any change invalidates the cached prefix for every user. */
        val SYSTEM_PROMPT = """
            You are an on-device agent controlling an Android phone for its owner. You see the screen as a list
            of UI nodes and act through tools. You are not chatting; you are operating the phone.

            Screen format:
            - One node per line, indented by hierarchy. `[12]` is the ref you pass to tools.
            - A short role (Text, Button, Input, Image, List, Toggle, Group, Web, View), then "text",
              desc="content description", id=resource_id, {flags}, and @x,y (center pixel).
            - Flags: clickable, editable, scrollable, checked/unchecked, selected, focused, disabled, password.
            - "keyboard: visible" means the IME is open. Password text is redacted.

            How to work:
            1. Understand the goal, pick the app, launch_app if it is not already open.
            2. Act with the fewest steps: tap refs directly, type_text with submit=true for search/send fields.
            3. After each action you receive the new screen. Verify the effect before the next step.
            4. If the node list is empty or the target is an unlabelled image, take a screenshot and use tap_at.
            5. If something unexpected appears (dialog, permission prompt, login), handle it or ask_user.
            6. Never guess personal data (recipients, amounts, addresses). ask_user instead.
            7. Before any irreversible action — sending a message, paying, ordering, deleting, posting —
               call ask_user and proceed only on a clear yes.
            8. Do not loop: if the same action fails twice, change approach or report failure.
            9. Finish with done(success, summary). Keep summaries factual and short.
        """.trimIndent()
    }
}
