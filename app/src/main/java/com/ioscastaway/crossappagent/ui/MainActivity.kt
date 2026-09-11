package com.ioscastaway.crossappagent.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                AgentScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(vm: AgentViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }

    // Re-check the Settings toggle every time we come back to the foreground.
    LaunchedEffect(Unit) { vm.refreshServiceStatus() }

    Scaffold(topBar = { TopAppBar(title = { Text("Cross-App Agent") }) }) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 16.dp).fillMaxSize()) {

            StatusCard(
                bound = state.serviceBound,
                enabledInSettings = state.serviceEnabledInSettings,
                apiKeySource = state.apiKeySource,
                onOpenSettings = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
                onRefresh = vm::refreshServiceStatus,
                onSaveKey = vm::saveApiKey,
                onClearKey = vm::clearApiKey,
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = state.task,
                onValueChange = vm::setTask,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Task") },
                placeholder = { Text("Open Messages and search for \"dinner\"") },
                minLines = 2,
                enabled = !state.running,
            )

            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.running) {
                    Button(onClick = vm::stop) { Text("Stop") }
                } else {
                    Button(
                        onClick = vm::run,
                        enabled = state.serviceBound && state.apiKeyPresent && state.task.isNotBlank(),
                    ) { Text("Run") }
                }
                OutlinedButton(onClick = { vm.dumpScreen() }, enabled = state.serviceBound) { Text("Dump") }
                OutlinedButton(onClick = { vm.dumpScreen(5000) }, enabled = state.serviceBound) { Text("Dump in 5s") }
            }

            Spacer(Modifier.height(12.dp))

            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Log") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Screen") })
            }

            when (tab) {
                0 -> LogList(state.log)
                else -> Text(
                    text = state.lastScreen.ifBlank { "Tap \"Dump screen\", then switch to another app and come back — or run a task." },
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                )
            }
        }
    }

    state.pendingQuestion?.let { q ->
        var answer by remember(q) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { /* the agent is waiting; force an explicit answer */ },
            title = { Text("Agent asks") },
            text = {
                Column {
                    Text(q.question)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = answer, onValueChange = { answer = it }, singleLine = true)
                }
            },
            confirmButton = { TextButton(onClick = { vm.answer(answer.ifBlank { "yes" }) }) { Text("Answer") } },
            dismissButton = { TextButton(onClick = { vm.answer("no") }) { Text("No") } },
        )
    }
}

@Composable
private fun StatusCard(
    bound: Boolean,
    enabledInSettings: Boolean,
    apiKeySource: ApiKeySource,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
    onSaveKey: (String) -> Unit,
    onClearKey: () -> Unit,
) {
    var editingKey by remember { mutableStateOf(false) }
    var keyDraft by remember { mutableStateOf("") }
    val showKeyField = apiKeySource == ApiKeySource.NONE || editingKey

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                when {
                    bound -> "Accessibility service: connected"
                    enabledInSettings -> "Accessibility service: enabled, waiting for the system to bind"
                    else -> "Accessibility service: OFF — enable \"Cross-App Agent\" in Settings"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Row {
                Text(
                    when (apiKeySource) {
                        ApiKeySource.NONE -> "Anthropic API key: missing"
                        ApiKeySource.BUILD -> "Anthropic API key: from local.properties"
                        ApiKeySource.IN_APP -> "Anthropic API key: saved in app"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (apiKeySource != ApiKeySource.NONE) {
                    TextButton(onClick = { editingKey = !editingKey }) { Text(if (editingKey) "Cancel" else "Change") }
                }
            }
            if (showKeyField) {
                OutlinedTextField(
                    value = keyDraft,
                    onValueChange = { keyDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Paste ANTHROPIC_API_KEY") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Row {
                    Button(
                        onClick = { onSaveKey(keyDraft); keyDraft = ""; editingKey = false },
                        enabled = keyDraft.isNotBlank(),
                    ) { Text("Save key") }
                    if (apiKeySource == ApiKeySource.IN_APP) {
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { onClearKey(); editingKey = false }) { Text("Remove saved key") }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row {
                OutlinedButton(onClick = onOpenSettings) { Text("Open accessibility settings") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onRefresh) { Text("Refresh") }
            }
        }
    }
}

@Composable
private fun LogList(log: List<LogLine>) {
    val listState = rememberLazyListState()
    LaunchedEffect(log.size) { if (log.isNotEmpty()) listState.animateScrollToItem(log.size - 1) }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(log) { line ->
            val prefix = when (line.kind) {
                LogLine.Kind.THOUGHT -> "💭 "
                LogLine.Kind.TOOL -> "→ "
                LogLine.Kind.RESULT -> "   "
                LogLine.Kind.ERROR -> "⚠ "
                LogLine.Kind.DONE -> "■ "
                LogLine.Kind.SCREEN -> ""
            }
            Text(
                prefix + line.text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = if (line.kind == LogLine.Kind.TOOL) FontFamily.Monospace else FontFamily.Default,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
}
