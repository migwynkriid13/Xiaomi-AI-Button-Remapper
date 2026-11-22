package io.github.xiaomibutton.remapper.runtime

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import io.github.xiaomibutton.remapper.SequenceConfig
import io.github.xiaomibutton.remapper.ActionSpec
import io.github.xiaomibutton.remapper.ActionKind
import io.github.xiaomibutton.remapper.AppConfig
import io.github.xiaomibutton.remapper.ConfigStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Owns one visible session. Device access and privileged command execution live in a different process. */
class RemapService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var notices: SessionNotifications
    private lateinit var store: ConfigStore
    private var mode: String? = null
    private var expert = false
    private var accessibility: ButtonAccessibilityService? = null
    private var generation = 0
    private var configuration: AppConfig? = null
    private var engine: GestureEngine? = null
    private var configJob: Job? = null
    private var actionJob: Job? = null
    private var sequenceJob: Deferred<String>? = null
    private var actions: Channel<ActionSpec>? = null
    private var dispatcher: ActionDispatcher? = null
    private var helper: IInputHelper? = null
    private var connection: ServiceConnection? = null
    private var args: Shizuku.UserServiceArgs? = null
    private var exited = CountDownLatch(1)
    private var lastPulse = 0L
    private var heartbeatJob: Job? = null
    private var ready = false

    override fun onCreate() {
        super.onCreate()
        RemapperRuntime.initialize(this)
        store = ConfigStore(this)
        notices = SessionNotifications(this).also { it.createChannels() }
        RemapperRuntime.onShizukuLost = { if (expert) finish("Shizuku disconnected. Restart Shizuku, then resume Expert mode.") }
        RemapperRuntime.onEndTest = { if (mode == TEST) finish("Read-only tester stopped") }
        RemapperRuntime.onStop = { finish("Disabled. The physical button uses Xiaomi's default action.", disable = true) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requested = intent?.action ?: REMAP
        val useExpert = intent?.getBooleanExtra(EXPERT, false) ?: false
        if (requested == STOP) {
            if (mode == TEST) finish("Read-only tester stopped from notification")
            else finish("Emergency stop. Xiaomi's default action is restored.", disable = true)
            return START_NOT_STICKY
        }
        try {
            foreground("Connecting to input helper…")
        } catch (e: RuntimeException) {
            finish("Android refused foreground service: ${e.message}")
            return START_NOT_STICKY
        }
        if (requested == TEST && !RemapperRuntime.testRequested && mode != REMAP) {
            finish("Read-only tester closed")
            return START_NOT_STICKY
        }
        if ((requested == mode && useExpert == expert) || (requested == TEST && mode == REMAP)) {
            foreground(if (ready && mode == REMAP) "Remapping active" else if (ready) "Read-only tester" else "Connecting to input helper…")
            return if (mode == REMAP) START_REDELIVER_INTENT else START_NOT_STICKY
        }
        closeSession()
        mode = requested
        expert = useExpert
        if (!notices.sessionVisible()) {
            finish("Enable the Button remapping notification channel so Stop now is accessible.", disable = requested == REMAP)
            return START_NOT_STICKY
        }
        RemapperRuntime.change { it.copy(helper = "Connecting", running = false, observing = false, diagnostics = if (useExpert) "Waiting for Shizuku…" else "Waiting for Android Accessibility…") }
        val ticket = generation
        configJob = scope.launch {
            try {
                val authorized = withTimeoutOrNull(3500) { RemapperRuntime.status.first { if (useExpert) it.authorized else it.accessibilityConnected } }
                if (ticket != generation) return@launch
                if (authorized == null) {
                    finish(if (useExpert) "Authorize Shizuku to resume Expert mode." else "Enable the remapper service in Accessibility settings, then resume.")
                    return@launch
                }
                store.config.collect { config ->
                    if (ticket != generation) return@collect
                    if (requested == REMAP && !config.enabled) {
                        finish("Remapping disabled in settings")
                        return@collect
                    }
                    val first = configuration == null
                    if (configuration != config) configure(config)
                    if (first) {
                        if (useExpert) bindHelper(ticket, requested == REMAP) else attachAccessibility(ticket, requested == REMAP)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (ticket == generation) finish("Configuration or binding failed: ${e.message}", disable = requested == REMAP)
            }
        }
        return if (requested == REMAP) START_REDELIVER_INTENT else START_NOT_STICKY
    }

    private fun configure(config: AppConfig) {
        if (configuration != null) {
            sequenceJob?.cancel()
            val queue = actions
            if (queue != null) while (queue.tryReceive().isSuccess) { /* discard gestures bound to previous mappings */ }
        }
        configuration = config
        main.removeCallbacks(timer)
        engine = GestureEngine(config.doubleMs.toLong(), config.longMs.toLong(), config.debounceMs.toLong(),
            config.double.kind != ActionKind.NONE, config.triple.kind != ActionKind.NONE, config.longPress.kind != ActionKind.NONE) { gesture ->
            if (actions?.trySend(config.action(gesture))?.isFailure == true) {
                RemapperRuntime.change { it.copy(lastAction = "Action queue busy. Press again.") }
            }
        }
    }

    private val timer = Runnable { engine?.advance(SystemClock.uptimeMillis()) }

    private val emergencyHold = Runnable { finish("Emergency stop. AI button held eight seconds.", disable = true) }
    private val checkAccessibleNotification = object : Runnable {
        override fun run() {
            if (accessibility == null) return
            if (!notices.sessionVisible()) finish("Session stopped because its notification was disabled.", disable = mode == REMAP)
            else main.postDelayed(this, 3000)
        }
    }

    private fun receiveKey(value: Int, time: Long, intercept: Boolean, canceled: Boolean) {
        if (value >= 0) {
            val event = ButtonEvent(value, time, System.currentTimeMillis() - (SystemClock.uptimeMillis() - time))
            RemapperRuntime.change { it.copy(events = (listOf(event) + it.events).take(40)) }
        }
        if (!ready || !intercept) return
        main.removeCallbacks(timer)
        if (canceled) {
            main.removeCallbacks(emergencyHold)
            engine?.reset()
            return
        }
        if (accessibility != null && value != 2) {
            main.removeCallbacks(emergencyHold)
            if (value == 1) main.postAtTime(emergencyHold, time + 8000)
        }
        engine?.key(value, time)
        engine?.nextDeadline?.let { main.postAtTime(timer, it) }
    }

    private fun attachAccessibility(ticket: Int, intercept: Boolean) {
        val service = checkNotNull(ButtonAccessibilityService.connected) { "Accessibility service disconnected" }
        accessibility = service
        if (intercept) startActions(null, service)
        service.attach(intercept, { if (ticket == generation) finish("Android disconnected Accessibility. Reconnect and resume.") }) {
            value, time, canceled -> if (ticket == generation) receiveKey(value, time, intercept, canceled)
        }
        ready = true
        RemapperRuntime.change { it.copy(helper = "Accessibility", running = intercept, observing = !intercept,
            diagnostics = "Listening for Android key events from the internal AI button. Press the button to verify delivery.") }
        foreground(if (intercept) "Accessibility remapping active" else "Accessibility tester · stops after one minute")
        main.postDelayed(checkAccessibleNotification, 3000)
        if (!intercept) main.postDelayed({
            if (ticket == generation && mode == TEST) finish("One-minute tester finished. Recent events remain available.")
        }, 60_000)
    }

    private fun bindHelper(ticket: Int, intercept: Boolean) {
        val ended = exited
        val client = object : IInputClient.Stub() {
            private fun deliver(block: () -> Unit) { main.post { if (ticket == generation) block() } }
            override fun status(message: String) = deliver {
                ready = message.startsWith(if (intercept) "Active:" else "Observing:")
                val label = if (ready && intercept) "Active" else if (ready) "Read-only" else "Preparing"
                RemapperRuntime.change { it.copy(helper = label, running = intercept && ready, observing = !intercept && ready, diagnostics = message) }
                if (ready) {
                    foreground(if (intercept) "Remapping active · Stop now restores the original key" else "Read-only tester · stops after one minute")
                    if (!intercept) main.postDelayed({
                        if (ticket == generation && mode == TEST) finish("One-minute tester finished. Recent events remain available.")
                    }, 60_000)
                }
            }
            override fun key(value: Int, uptimeMillis: Long) = deliver { receiveKey(value, uptimeMillis, intercept, false) }
            override fun pulse() = deliver { lastPulse = SystemClock.uptimeMillis() }
            override fun ended(reason: String) {
                ended.countDown()
                deliver { finish(reason, disable = intercept) }
            }
        }
        val options = Shizuku.UserServiceArgs(ComponentName(this, InputUserService::class.java))
            .daemon(false).tag("gpio-${UUID.randomUUID()}").processNameSuffix("gpio_helper").version(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                main.post {
                    if (generation != ticket || binder == null) return@post
                    val remote = IInputHelper.Stub.asInterface(binder)
                    helper = remote
                    val death = IBinder.DeathRecipient { main.post { helperDied(ticket) } }
                    try {
                        binder.linkToDeath(death, 0)
                    } catch (_: Exception) {
                        helperDied(ticket)
                        return@post
                    }
                    if (intercept) startActions(remote)
                    lastPulse = SystemClock.uptimeMillis()
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { remote.start(intercept, client) }
                            if (generation == ticket) heartbeat.run()
                        } catch (e: Exception) {
                            if (generation == ticket) finish("Helper start failed: ${e.message}", disable = intercept)
                        }
                    }
                }
            }
            override fun onServiceDisconnected(name: ComponentName?) { main.post { helperDied(ticket) } }
        }
        args = options
        connection = conn
        control.execute {
            try {
                Shizuku.bindUserService(options, conn)
            } catch (e: Exception) {
                main.post { if (generation == ticket) finish("Shizuku binding failed: ${e.message}", disable = intercept) }
            }
        }
        main.postDelayed({
            if (generation == ticket && !ready) finish("Input setup timed out. Capture has been stopped.", disable = intercept)
        }, 20_000)
    }

    private fun helperDied(ticket: Int) {
        if (generation == ticket) finish("Input helper terminated. Verify Shizuku and enable again.",
            disable = mode == REMAP && RemapperRuntime.status.value.available)
    }

    private fun startActions(remote: IInputHelper?, actionContext: Context = this) {
        val executor = ActionDispatcher(actionContext, notices).also { dispatcher = it }
        val queue = Channel<ActionSpec>(4).also { actions = it }
        actionJob = scope.launch {
            for (action in queue) {
                val message = try {
                    if (configuration?.haptics == true) executor.haptic()
                    if (action.kind == ActionKind.SEQUENCE) supervisorScope {
                        val sequence = SequenceConfig.fromAction(action)
                        val work = async {
                            withTimeoutOrNull(120_000L) { sequence.run({ step -> executor.perform(step, remote) }) }
                                ?: error("Sequence timed out")
                        }
                        sequenceJob = work
                        try { work.await() } finally { sequenceJob = null }
                    } else executor.perform(action, remote)
                } catch (e: CancellationException) {
                    if (!isActive) throw e
                    "Sequence stopped after configuration changed"
                } catch (e: Exception) {
                    "Action unavailable: ${e.message ?: e.javaClass.simpleName}"
                }
                RemapperRuntime.change { it.copy(lastAction = message) }
            }
        }
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            val remote = helper ?: return
            val ticket = generation
            if (SystemClock.uptimeMillis() - lastPulse > 5500) {
                finish("Helper stopped responding. Input capture was stopped.", disable = mode == REMAP)
                return
            }
            if (!notices.sessionVisible()) {
                finish("Remapping notification disabled. Restored default input.", disable = mode == REMAP)
                return
            }
            if (heartbeatJob?.isActive != true) heartbeatJob = scope.launch(Dispatchers.IO) {
                try { remote.heartbeat() } catch (_: Exception) { main.post { helperDied(ticket) } }
            }
            main.postDelayed(this, 750)
        }
    }

    private fun foreground(detail: String) {
        val notice = notices.session(detail)
        if (Build.VERSION.SDK_INT >= 34) startForeground(SessionNotifications.SESSION_ID, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(SessionNotifications.SESSION_ID, notice)
    }

    private fun finish(reason: String, disable: Boolean = false) {
        if (disable) RemapperRuntime.disableConfig(this)
        closeSession()
        RemapperRuntime.change { it.copy(helper = "Stopped", running = false, observing = false, diagnostics = reason) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun closeSession() {
        generation++
        mode = null
        expert = false
        accessibility?.detach()
        accessibility = null
        main.removeCallbacks(emergencyHold)
        main.removeCallbacks(checkAccessibleNotification)
        configJob?.cancel()
        actionJob?.cancel()
        heartbeatJob?.cancel()
        actions?.close()
        actions = null
        dispatcher?.close()
        dispatcher = null
        engine?.reset()
        engine = null
        configuration = null
        main.removeCallbacks(heartbeat)
        main.removeCallbacks(timer)
        ready = false
        val oldRemote = helper
        val oldConnection = connection
        val oldArgs = args
        val oldLatch = exited
        helper = null
        connection = null
        args = null
        exited = CountDownLatch(1)
        if (oldArgs != null) control.execute {
            if (oldRemote != null) {
                runCatching { oldRemote.stop() }
                oldLatch.await(800, TimeUnit.MILLISECONDS)
            }
            runCatching { Shizuku.unbindUserService(oldArgs, oldConnection, true) }
        }
    }

    override fun onDestroy() {
        closeSession()
        notices.clearOffer()
        RemapperRuntime.onShizukuLost = null
        RemapperRuntime.onEndTest = null
        RemapperRuntime.onStop = null
        RemapperRuntime.change { it.copy(running = false, observing = false, helper = "Stopped") }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        internal const val REMAP = "remap"
        internal const val TEST = "test"
        internal const val EXPERT = "expert"
        internal const val STOP = "stop"
        private val control = Executors.newSingleThreadExecutor { Thread(it, "Shizuku-session-control") }
    }
}
