package com.ioscastaway.crossappagent.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ioscastaway.crossappagent.bubble.BubbleService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MaterialTheme { AgentScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(vm: AgentViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }

    // These three are granted outside our process, so re-read them every time we come back.
    var canOverlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var hasMic by remember { mutableStateOf(context.hasPermission(Manifest.permission.RECORD_AUDIO)) }
    var hasNotifications by remember { mutableStateOf(context.hasNotificationPermission()) }
    var bubbleOn by remember { mutableStateOf(BubbleService.isRunning) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                vm.refreshServiceStatus()
                canOverlay = Settings.canDrawOverlays(context)
                hasMic = context.hasPermission(Manifest.permission.RECORD_AUDIO)
                hasNotifications = context.hasNotificationPermission()
                bubbleOn = BubbleService.isRunning
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasMic = it
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasNotifications = it
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Cross-App Agent") }) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .fillMaxSize()
        ) {
            BubbleCard(
                bubbleOn = bubbleOn,
                serviceBound = state.serviceBound,
                canOverlay = canOverlay,
                hasMic = hasMic,
                hasNotifications = hasNotifications,
                hasKey = state.apiKeyPresent,
                onGrantOverlay = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        )
                    )
                },
                onGrantMic = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                onGrantNotifications = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
                onStart = { BubbleService.start(context); bubbleOn = true },
                onStop = { BubbleService.stop(context); bubbleOn = false },
            )

            Spacer(Modifier.height(12.dp))

            StatusCard(
                bound = state.serviceBound,
                enabledInSettings = state.serviceEnabledInSettings,
                apiKeyPresent = state.apiKeyPresent,
                onOpenSettings = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                onRefresh = vm::refreshServiceStatus,
            )

            Spacer(Modifier.height(12.dp))

            Text("Debug console", style = MaterialTheme.typography.labelLarge)

            OutlinedTextField(
                value = state.task,
                onValueChange = vm::setTask,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Task") },
                placeholder = { Text("Open Settings and turn on dark theme") },
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

            Spacer(Modifier.height(8.dp))

            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Log") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Screen") })
            }

            when (tab) {
                0 -> LogList(state.log)
                else -> Text(
                    text = state.lastScreen.ifBlank { "Tap \"Dump in 5s\", switch to another app, and come back." },
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                )
            }
        }
    }

    state.pendingQuestion?.let { q ->
        var answer by remember(q) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { },
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

/** The actual product: one button, once everything it depends on is granted. */
@Composable
private fun BubbleCard(
    bubbleOn: Boolean,
    serviceBound: Boolean,
    canOverlay: Boolean,
    hasMic: Boolean,
    hasNotifications: Boolean,
    hasKey: Boolean,
    onGrantOverlay: () -> Unit,
    onGrantMic: () -> Unit,
    onGrantNotifications: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val ready = canOverlay && hasMic && serviceBound && hasKey

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Voice bubble", style = MaterialTheme.typography.titleMedium)
            Text(
                "A circle that floats over every app. Tap it, say what you want, and the agent does it.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))

            if (!canOverlay) NeedRow("Draw over other apps", "Grant", onGrantOverlay)
            if (!hasMic) NeedRow("Microphone", "Grant", onGrantMic)
            if (!hasNotifications) NeedRow("Notifications (bubble runs as a foreground service)", "Grant", onGrantNotifications)
            if (!serviceBound) Text("• Accessibility service is off — see below", style = MaterialTheme.typography.bodySmall)
            if (!hasKey) Text("• No API key in this build - see below", style = MaterialTheme.typography.bodySmall)

            Spacer(Modifier.height(8.dp))
            if (bubbleOn) {
                OutlinedButton(onClick = onStop) { Text("Stop bubble") }
            } else {
                Button(onClick = onStart, enabled = ready) { Text("Start bubble") }
            }
        }
    }
}

@Composable
private fun NeedRow(label: String, action: String, onClick: () -> Unit) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("• $label", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(onClick = onClick) { Text(action) }
    }
}

@Composable
private fun StatusCard(
    bound: Boolean,
    enabledInSettings: Boolean,
    apiKeyPresent: Boolean,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                when {
                    bound -> "Accessibility service: connected"
                    enabledInSettings -> "Accessibility service: enabled, waiting for the system to bind"
                    else -> "Accessibility service: OFF - enable \"Cross-App Agent\" in Settings"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                if (apiKeyPresent) "Anthropic API key: built in"
                else "Anthropic API key: missing - set ANTHROPIC_API_KEY in local.properties and rebuild",
                style = MaterialTheme.typography.bodyMedium,
            )
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
    androidx.compose.runtime.LaunchedEffect(log.size) {
        if (log.isNotEmpty()) listState.animateScrollToItem(log.size - 1)
    }
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

private fun android.content.Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun android.content.Context.hasNotificationPermission(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        hasPermission(Manifest.permission.POST_NOTIFICATIONS)
