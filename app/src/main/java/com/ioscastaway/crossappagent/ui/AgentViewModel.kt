package com.ioscastaway.crossappagent.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.ioscastaway.crossappagent.BuildConfig
import com.ioscastaway.crossappagent.agent.AgentEvent
import com.ioscastaway.crossappagent.agent.ClaudeAgent
import com.ioscastaway.crossappagent.platform.AccessibilityDeviceController
import com.ioscastaway.crossappagent.platform.AgentAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LogLine(val kind: Kind, val text: String) {
    enum class Kind { THOUGHT, TOOL, RESULT, ERROR, DONE, SCREEN }
}

enum class ApiKeySource { NONE, BUILD, IN_APP }

data class AgentUiState(
    val serviceBound: Boolean = false,
    val serviceEnabledInSettings: Boolean = false,
    val apiKeySource: ApiKeySource = ApiKeySource.NONE,
    val task: String = "",
    val running: Boolean = false,
    val log: List<LogLine> = emptyList(),
    val lastScreen: String = "",
    val pendingQuestion: AgentEvent.Question? = null,
) {
    val apiKeyPresent: Boolean get() = apiKeySource != ApiKeySource.NONE
}

class AgentViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(AgentUiState())
    val state: StateFlow<AgentUiState> = _state.asStateFlow()

    private val device = AccessibilityDeviceController(app) { AgentAccessibilityService.instance.value }
    private var job: Job? = null

    // Dev-console convenience: the key can be pasted into the app instead of local.properties.
    // Plain SharedPreferences on a debug build of an experiment; not a pattern for a shipping app.
    private val prefs = app.getSharedPreferences("agent", Context.MODE_PRIVATE)

    init {
        viewModelScope.launch {
            AgentAccessibilityService.instance.collect { svc ->
                _state.update { it.copy(serviceBound = svc != null) }
            }
        }
        refreshServiceStatus()
        _state.update { it.copy(apiKeySource = apiKeySource()) }
    }

    // ---------------------------------------------------------------- api key

    private fun inAppKey(): String? = prefs.getString(PREF_API_KEY, null)?.trim()?.takeIf { it.isNotBlank() }

    private fun apiKeySource(): ApiKeySource = when {
        inAppKey() != null -> ApiKeySource.IN_APP
        BuildConfig.ANTHROPIC_API_KEY.isNotBlank() -> ApiKeySource.BUILD
        else -> ApiKeySource.NONE
    }

    private fun effectiveKey(): String = inAppKey() ?: BuildConfig.ANTHROPIC_API_KEY

    fun saveApiKey(key: String) {
        prefs.edit().putString(PREF_API_KEY, key.trim()).apply()
        _state.update { it.copy(apiKeySource = apiKeySource()) }
    }

    fun clearApiKey() {
        prefs.edit().remove(PREF_API_KEY).apply()
        _state.update { it.copy(apiKeySource = apiKeySource()) }
    }

    private fun newClient(): AnthropicClient =
        AnthropicOkHttpClient.builder().apiKey(effectiveKey()).build()

    // ---------------------------------------------------------------- service status

    fun refreshServiceStatus() {
        val app = getApplication<Application>()
        val enabled = Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')
            ?.any { it.equals(ComponentName(app, AgentAccessibilityService::class.java).flattenToString(), true) }
            ?: false
        _state.update { it.copy(serviceEnabledInSettings = enabled) }
    }

    fun setTask(text: String) = _state.update { it.copy(task = text) }

    /** Dump after a delay so you can switch to another app first — the cross-app smoke test. */
    fun dumpScreen(delayMs: Long = 0) {
        viewModelScope.launch(Dispatchers.Default) {
            if (delayMs > 0) delay(delayMs)
            val text = try {
                device.readScreen().toPromptText()
            } catch (t: IllegalStateException) {
                t.message ?: "unavailable"
            }
            _state.update { it.copy(lastScreen = text) }
        }
    }

    // ---------------------------------------------------------------- agent

    fun run() {
        val task = _state.value.task.trim()
        if (task.isEmpty() || _state.value.running || !_state.value.apiKeyPresent) return
        _state.update { it.copy(running = true, log = emptyList(), pendingQuestion = null) }

        val agent = ClaudeAgent(newClient(), device, BuildConfig.CLAUDE_MODEL)
        job = viewModelScope.launch(Dispatchers.Default) {
            agent.run(task).collect { ev ->
                when (ev) {
                    is AgentEvent.Thought -> log(LogLine.Kind.THOUGHT, ev.text)
                    is AgentEvent.ToolCall -> log(LogLine.Kind.TOOL, "${ev.name} ${ev.input}")
                    is AgentEvent.ToolResult -> log(LogLine.Kind.RESULT, (if (ev.ok) "✓ " else "✗ ") + ev.summary)
                    is AgentEvent.Screen -> _state.update { it.copy(lastScreen = ev.text) }
                    is AgentEvent.Question -> _state.update { it.copy(pendingQuestion = ev) }
                    is AgentEvent.Finished -> log(LogLine.Kind.DONE, (if (ev.success) "Done: " else "Failed: ") + ev.summary)
                    is AgentEvent.Error -> log(LogLine.Kind.ERROR, ev.message)
                }
            }
            _state.update { it.copy(running = false, pendingQuestion = null) }
        }
    }

    fun answer(text: String) {
        val q = _state.value.pendingQuestion ?: return
        q.answer.complete(text)
        log(LogLine.Kind.RESULT, "You: $text")
        _state.update { it.copy(pendingQuestion = null) }
    }

    fun stop() {
        job?.cancel()
        _state.value.pendingQuestion?.answer?.cancel()
        _state.update { it.copy(running = false, pendingQuestion = null) }
        log(LogLine.Kind.ERROR, "Stopped by user")
    }

    private fun log(kind: LogLine.Kind, text: String) =
        _state.update { it.copy(log = it.log + LogLine(kind, text)) }

    private companion object {
        const val PREF_API_KEY = "anthropic_api_key"
    }
}
