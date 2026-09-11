package com.ioscastaway.crossappagent.bubble

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.ioscastaway.crossappagent.R
import com.ioscastaway.crossappagent.agent.AgentEvent
import com.ioscastaway.crossappagent.agent.ApiKeyStore
import com.ioscastaway.crossappagent.agent.ClaudeAgent
import com.ioscastaway.crossappagent.BuildConfig
import com.ioscastaway.crossappagent.platform.AccessibilityDeviceController
import com.ioscastaway.crossappagent.platform.AgentAccessibilityService
import com.ioscastaway.crossappagent.platform.VoiceRecognizer
import com.ioscastaway.crossappagent.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The product surface: a draggable circle that floats over every app. Tap it, say what you want,
 * and the agent drives the phone while the bubble reports what it is doing.
 *
 * This is the capability iOS has no third-party equivalent for. `TYPE_APPLICATION_OVERLAY` plus
 * `SYSTEM_ALERT_WINDOW` lets an ordinary app draw on top of the whole system; the closest iOS gets
 * is Picture-in-Picture, which only carries video and only for the app that started it.
 *
 * Plain Views on purpose. An overlay window has no Activity behind it, so a ComposeView needs its
 * lifecycle, saved-state and ViewModelStore owners wired by hand — a well-known source of crashes
 * for a UI this small. The rest of the app stays Compose.
 */
class BubbleService : Service() {

    companion object {
        private const val CHANNEL_ID = "bubble"
        private const val NOTIFICATION_ID = 1

        const val ACTION_START = "com.ioscastaway.crossappagent.BUBBLE_START"
        const val ACTION_STOP = "com.ioscastaway.crossappagent.BUBBLE_STOP"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val i = Intent(context, BubbleService::class.java).setAction(ACTION_START)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, BubbleService::class.java).setAction(ACTION_STOP))
        }
    }

    private enum class State { IDLE, LISTENING, WORKING, ASKING, RESULT }

    private lateinit var windowManager: WindowManager
    private lateinit var bubble: View
    private lateinit var bubbleParams: WindowManager.LayoutParams

    private lateinit var panel: LinearLayout
    private lateinit var panelParams: WindowManager.LayoutParams
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var buttonRow: LinearLayout
    private var panelShown = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var work: Job? = null
    private var answerWork: Job? = null
    private var pendingQuestion: AgentEvent.Question? = null

    private var state = State.IDLE
        set(value) {
            field = value
            paintBubble()
        }

    private val device by lazy {
        AccessibilityDeviceController(applicationContext) { AgentAccessibilityService.instance.value }
    }
    private val voice by lazy { VoiceRecognizer(this) }

    // ---------------------------------------------------------------- lifecycle

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        createNotificationChannel()
        buildBubble()
        buildPanel()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification(), foregroundType())
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        work?.cancel()
        answerWork?.cancel()
        scope.cancel()
        runCatching { windowManager.removeView(bubble) }
        if (panelShown) runCatching { windowManager.removeView(panel) }
        super.onDestroy()
    }

    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14 demands a declared type. Microphone is the honest one: tapping the bubble
            // opens the mic while the service runs in the background.
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else 0

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.bubble_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.bubble_channel_description) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BubbleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.bubble_notification_title))
            .setContentText(getString(R.string.bubble_notification_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .addAction(0, getString(R.string.bubble_stop), stop)
            .setOngoing(true)
            .build()
    }

    // ---------------------------------------------------------------- bubble view

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun buildBubble() {
        val size = dp(56)
        bubble = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(COLOR_IDLE)
            }
            elevation = dp(6).toFloat()
            // Keep our own chrome out of the tree the agent reads.
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            contentDescription = getString(R.string.bubble_content_description)
        }

        bubbleParams = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_FOCUSABLE keeps the keyboard and focus with whatever app is underneath.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth() - size - dp(8)
            y = screenHeight() / 3
        }

        attachDragAndTap(size)
        windowManager.addView(bubble, bubbleParams)
    }

    private fun attachDragAndTap(size: Int) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f; var downY = 0f
        var startX = 0; var startY = 0
        var dragging = false
        var downAt = 0L

        bubble.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = bubbleParams.x; startY = bubbleParams.y
                    dragging = false
                    downAt = System.currentTimeMillis()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        bubbleParams.x = (startX + dx).roundToInt()
                            .coerceIn(0, screenWidth() - size)
                        bubbleParams.y = (startY + dy).roundToInt()
                            .coerceIn(0, screenHeight() - size)
                        windowManager.updateViewLayout(bubble, bubbleParams)
                        if (panelShown) positionPanel()
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        snapToEdge(size)
                    } else if (System.currentTimeMillis() - downAt > 600) {
                        stopSelf()   // long press dismisses the bubble
                    } else {
                        onTap()
                    }
                    true
                }

                else -> false
            }
        }
    }

    /** Slide to whichever side edge is nearer, the way every bubble UI on this platform behaves. */
    private fun snapToEdge(size: Int) {
        val target = if (bubbleParams.x + size / 2 < screenWidth() / 2) dp(8)
        else screenWidth() - size - dp(8)
        ValueAnimator.ofInt(bubbleParams.x, target).apply {
            duration = 180
            addUpdateListener {
                bubbleParams.x = it.animatedValue as Int
                runCatching { windowManager.updateViewLayout(bubble, bubbleParams) }
                if (panelShown) positionPanel()
            }
            start()
        }
    }

    private fun paintBubble() {
        val color = when (state) {
            State.IDLE -> COLOR_IDLE
            State.LISTENING -> COLOR_LISTENING
            State.WORKING -> COLOR_WORKING
            State.ASKING -> COLOR_ASKING
            State.RESULT -> COLOR_RESULT
        }
        (bubble.background as GradientDrawable).setColor(color)
    }

    private fun screenWidth() = resources.displayMetrics.widthPixels
    private fun screenHeight() = resources.displayMetrics.heightPixels

    // ---------------------------------------------------------------- panel view

    private fun buildPanel() {
        val ctx: Context = android.view.ContextThemeWrapper(this, android.R.style.Theme_Material)

        statusText = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(0, 0, 0, dp(6))
        }
        logText = TextView(ctx).apply {
            setTextColor(Color.parseColor("#B9BDC7"))
            textSize = 12f
        }
        logScroll = ScrollView(ctx).apply {
            addView(logText)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(120)
            )
        }
        buttonRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }

        panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#E6191C22"))
            }
            elevation = dp(8).toFloat()
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(statusText)
            addView(logScroll)
            addView(buttonRow)
        }

        panelParams = WindowManager.LayoutParams(
            (screenWidth() * 0.86f).roundToInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
    }

    private fun positionPanel() {
        panelParams.x = ((screenWidth() - panelParams.width) / 2).coerceAtLeast(dp(8))
        panelParams.y = (bubbleParams.y + dp(64)).coerceAtMost(screenHeight() - dp(220))
        runCatching { windowManager.updateViewLayout(panel, panelParams) }
    }

    private fun showPanel() {
        if (panelShown) return
        panelShown = true
        windowManager.addView(panel, panelParams)
        positionPanel()
    }

    private fun hidePanel() {
        if (!panelShown) return
        panelShown = false
        runCatching { windowManager.removeView(panel) }
    }

    private fun status(text: String) {
        showPanel()
        statusText.text = text
    }

    private fun log(line: String) {
        logText.append(if (logText.text.isEmpty()) line else "\n$line")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun clearLog() {
        logText.text = ""
        buttons()
    }

    /** Replaces the button row; no arguments hides it. */
    private fun buttons(vararg pairs: Pair<String, () -> Unit>) {
        buttonRow.removeAllViews()
        if (pairs.isEmpty()) {
            buttonRow.visibility = View.GONE
            return
        }
        val ctx: Context = android.view.ContextThemeWrapper(this, android.R.style.Theme_Material)
        val stacked = pairs.size > 2
        buttonRow.orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        for ((label, action) in pairs) {
            buttonRow.addView(
                Button(ctx).apply {
                    text = label
                    isAllCaps = false
                    setOnClickListener { action() }
                    layoutParams = if (stacked) {
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )
                    } else {
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                }
            )
        }
        buttonRow.visibility = View.VISIBLE
    }

    // ---------------------------------------------------------------- interaction

    private fun onTap() {
        when (state) {
            State.ASKING -> showPanel()                       // bring the question back
            State.LISTENING, State.WORKING -> cancelWork()    // tap again to abort
            else -> startListening()
        }
    }

    private fun cancelWork() {
        work?.cancel()
        answerWork?.cancel()
        answerWork = null
        pendingQuestion?.answer?.cancel()
        pendingQuestion = null
        state = State.IDLE
        status(getString(R.string.bubble_cancelled))
        scope.launch { delay(1200); if (state == State.IDLE) hidePanel() }
    }

    private fun startListening() {
        if (!device.isAvailable()) {
            state = State.RESULT
            clearLog()
            status(getString(R.string.bubble_need_accessibility))
            scope.launch { delay(3500); state = State.IDLE; hidePanel() }
            return
        }
        val client = ApiKeyStore.client()
        if (client == null) {
            state = State.RESULT
            clearLog()
            status(getString(R.string.bubble_need_key))
            scope.launch { delay(3500); state = State.IDLE; hidePanel() }
            return
        }

        state = State.LISTENING
        clearLog()
        status(getString(R.string.bubble_listening))

        work = scope.launch {
            var heard: String? = null
            voice.listen().collect { ev ->
                when (ev) {
                    is VoiceRecognizer.Event.Listening -> status(getString(R.string.bubble_listening))
                    is VoiceRecognizer.Event.Partial -> status(ev.text)
                    is VoiceRecognizer.Event.Final -> heard = ev.text
                    is VoiceRecognizer.Event.Failed -> {
                        state = State.RESULT
                        status(ev.reason)
                        delay(2500)
                        state = State.IDLE
                        hidePanel()
                    }
                }
            }
            heard?.let { runAgent(client, it) }
        }
    }

    private suspend fun runAgent(
        client: com.anthropic.client.AnthropicClient,
        task: String,
    ) {
        state = State.WORKING
        status("\"$task\"")
        log("· " + getString(R.string.bubble_thinking))

        ClaudeAgent(client, device, BuildConfig.CLAUDE_MODEL)
            .run(task)
            .flowOn(Dispatchers.Default)   // tree reads and HTTP stay off the UI thread
            .collect { ev ->
                when (ev) {
                    is AgentEvent.Thought -> log("· " + ev.text.lineSequence().first().take(90))
                    is AgentEvent.ToolCall -> log("→ " + ev.name)
                    is AgentEvent.ToolResult -> log("   " + (if (ev.ok) "✓ " else "✗ ") + ev.summary.take(90))
                    is AgentEvent.Screen -> Unit
                    is AgentEvent.Question -> ask(ev)
                    is AgentEvent.Finished -> finish(ev.success, ev.summary)
                    is AgentEvent.Error -> finish(false, ev.message)
                }
            }
    }

    private fun ask(q: AgentEvent.Question) {
        pendingQuestion = q
        state = State.ASKING
        showPanel()
        status(q.question)
        offerAnswers(q)
    }

    /**
     * One button per answer the model expects, plus a microphone for everything else.
     *
     * An open question ("which video?", "how much?") arrives with no options, so speaking is the only
     * way to answer it — which is the right default for a voice product anyway. A closed question
     * still keeps the microphone, because the real answer is sometimes none of the offered ones.
     */
    private fun offerAnswers(q: AgentEvent.Question) {
        val actions = buildList<Pair<String, () -> Unit>> {
            q.options.take(3).forEach { option -> add(option to { answer(option) }) }
            add(getString(R.string.bubble_speak) to { listenForAnswer(q) })
        }
        buttons(*actions.toTypedArray())
    }

    private fun listenForAnswer(q: AgentEvent.Question) {
        answerWork?.cancel()
        buttons()
        state = State.LISTENING
        status(getString(R.string.bubble_listening))
        answerWork = scope.launch {
            var heard: String? = null
            voice.listen().collect { ev ->
                when (ev) {
                    is VoiceRecognizer.Event.Listening -> status(getString(R.string.bubble_listening))
                    is VoiceRecognizer.Event.Partial -> status(ev.text)
                    is VoiceRecognizer.Event.Final -> heard = ev.text
                    is VoiceRecognizer.Event.Failed -> {
                        status(ev.reason)
                        delay(1500)
                        // Put the question back rather than answering something the user did not say.
                        if (pendingQuestion === q) {
                            state = State.ASKING
                            status(q.question)
                            offerAnswers(q)
                        }
                    }
                }
            }
            heard?.let { answer(it) }
        }
    }

    private fun answer(text: String) {
        answerWork?.cancel()
        answerWork = null
        pendingQuestion?.answer?.complete(text)
        pendingQuestion = null
        buttons()
        state = State.WORKING
        log("   " + getString(R.string.bubble_you) + ": " + text)
    }

    private fun finish(success: Boolean, summary: String) {
        state = State.RESULT
        buttons()
        status((if (success) "✓ " else "✗ ") + summary)
        scope.launch {
            delay(if (success) 4000 else 7000)
            if (state == State.RESULT) {
                state = State.IDLE
                hidePanel()
            }
        }
    }
}

private const val COLOR_IDLE = 0xFF6750A4.toInt()       // resting purple
private const val COLOR_LISTENING = 0xFFD32F2F.toInt()  // mic open
private const val COLOR_WORKING = 0xFF1976D2.toInt()    // driving the phone
private const val COLOR_ASKING = 0xFFF9A825.toInt()     // waiting on you
private const val COLOR_RESULT = 0xFF2E7D32.toInt()     // finished
