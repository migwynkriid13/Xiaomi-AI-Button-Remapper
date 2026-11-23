package io.github.xiaomibutton.remapper.runtime

import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import io.github.xiaomibutton.remapper.AppConfig
import io.github.xiaomibutton.remapper.AppLog
import io.github.xiaomibutton.remapper.ConfigStore
import io.github.xiaomibutton.remapper.LogArea
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Keeps the app-side session alive. The Shizuku UserService owns the leased input descriptors. */
class RemapService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var notices: SessionNotifications
    private var mode: String? = null
    private var generation = 0
    private var ready = false
    private var config: AppConfig? = null
    private var configJob: Job? = null
    private var heartbeatJob: Job? = null
    private var helper: IInputHelper? = null
    private var connection: ServiceConnection? = null
    private var args: Shizuku.UserServiceArgs? = null
    private var exited = CountDownLatch(1)
    private var lastPulse = 0L
    private var session: RemapSession? = null

    override fun onCreate() {
        super.onCreate()
        RemapperRuntime.initialize(this)
        RemapperRuntime.inputService = this
        notices = SessionNotifications(this).also { it.createChannels() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requested = intent?.action
        if (requested == null || !RemapperRuntime.takeRequest(requested)) {
            // A user can cancel before Android delivers startForegroundService's command.
            if (mode == null) {
                runCatching { foreground("Stopping input session…", testing = requested == TEST) }
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }
        try { foreground("Connecting to the AI button…", testing = requested == TEST) } catch (e: RuntimeException) {
            finish("Android refused to start the input service: ${e.message}", remapError = requested == REMAP)
            return START_NOT_STICKY
        }

        closeSession()
        mode = requested
        AppLog.add(LogArea.BUTTON, if (requested == REMAP) "Remap service started" else "Read-only test service started")
        RemapperRuntime.refresh(this)
        if (!RemapperRuntime.status.value.shizukuAuthorized) {
            shizukuDisconnected()
            return START_NOT_STICKY
        }
        val remapping = requested == REMAP
        RemapperRuntime.change { it.copy(starting = true, startingRemap = remapping, running = false, observing = false) }
        val ticket = generation
        configJob = scope.launch {
            try {
                if (!remapping) {
                    bindHelper(ticket, remapping = false, intercept = false)
                    return@launch
                }
                ConfigStore(this@RemapService).config.collect { current ->
                    if (ticket != generation) return@collect
                    if (!current.enabled) {
                        finish("Remapping off. The original Xiaomi button action is available.")
                        return@collect
                    }
                    val previous = config
                    if (previous != null && previous.keepOriginalAction != current.keepOriginalAction) {
                        finish("Button routing changed. Tap Resume remapping to apply it.")
                        return@collect
                    }
                    config = current
                    if (previous == null) bindHelper(ticket, remapping = true, intercept = !current.keepOriginalAction)
                    else session?.configure(current)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == ticket) finish("Could not start the input session: ${e.message}", remapError = remapping)
            }
        }
        return START_NOT_STICKY
    }

    private fun bindHelper(ticket: Int, remapping: Boolean, intercept: Boolean) {
        AppLog.add(LogArea.BUTTON, when {
            intercept -> "Binding input helper for safe interception"
            remapping -> "Binding input helper to run alongside Xiaomi"
            else -> "Binding input helper for the physical button test"
        })
        val stopped = exited
        val client = object : IInputClient.Stub() {
            private fun deliver(block: () -> Unit) { main.post { if (ticket == generation) block() } }
            override fun status(message: String) = deliver {
                ready = message.startsWith(if (intercept) "Active:" else "Observing:")
                RemapperRuntime.change { it.copy(starting = !ready,
                    startingRemap = if (remapping) !ready else it.startingRemap, running = remapping && ready,
                    observing = !remapping && ready, diagnostics = message) }
                if (ready) {
                    foreground(if (remapping) {
                        if (intercept) "Remapping active · Stop restores the original input" else "Remapping active · Original action also runs"
                    } else "Read-only button test · ends after one minute", testing = !remapping)
                    if (!remapping) main.postDelayed({
                        if (ticket == generation) finish("Button test finished. Recent events remain available.")
                    }, 60_000)
                }
            }

            override fun key(value: Int, uptimeMillis: Long) = deliver {
                if (ready) session?.receive(value, uptimeMillis) ?: RemapperRuntime.recordEvent(value, uptimeMillis)
            }

            override fun pulse() = deliver { lastPulse = SystemClock.uptimeMillis() }

            override fun ended(reason: String) {
                stopped.countDown()
                deliver { finish(reason, remapError = remapping) }
            }
        }
        val options = Shizuku.UserServiceArgs(ComponentName(this, InputUserService::class.java))
            .daemon(false).tag("gpio-${UUID.randomUUID()}").processNameSuffix("gpio_helper").version(2)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                main.post {
                    if (ticket != generation || binder == null) return@post
                    val remote = IInputHelper.Stub.asInterface(binder)
                    helper = remote
                    AppLog.add(LogArea.BUTTON, "Input helper connected")
                    try { binder.linkToDeath({ main.post { helperDied(ticket) } }, 0) }
                    catch (_: Exception) { helperDied(ticket); return@post }
                    if (remapping) session = RemapSession(this@RemapService, scope, notices, remote).also {
                        it.configure(checkNotNull(config))
                    }
                    lastPulse = SystemClock.uptimeMillis()
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { remote.start(intercept, remapping, client) }
                            if (ticket == generation) heartbeat.run()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if (ticket == generation) finish("Input helper could not start: ${e.message}", remapError = remapping)
                        }
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) { main.post { helperDied(ticket) } }
        }
        args = options
        connection = conn
        control.execute {
            try { Shizuku.bindUserService(options, conn) }
            catch (e: Exception) {
                main.post { if (generation == ticket) finish("Shizuku could not connect to the input helper: ${e.message}", remapError = remapping) }
            }
        }
        main.postDelayed({
            if (ticket == generation && !ready) finish("The input helper did not start. Check Shizuku and try again.", remapError = remapping)
        }, 20_000)
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            val remote = helper ?: return
            val ticket = generation
            if (SystemClock.uptimeMillis() - lastPulse > 5500) {
                finish("Input helper stopped responding. Stock button input was restored.", remapError = mode == REMAP)
                return
            }
            if (heartbeatJob?.isActive != true) heartbeatJob = scope.launch(Dispatchers.IO) {
                try {
                    if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                        main.post {
                            if (ticket == generation) {
                                RemapperRuntime.refresh(this@RemapService)
                                shizukuDisconnected()
                            }
                        }
                    } else remote.heartbeat()
                } catch (_: Exception) { main.post { helperDied(ticket) } }
            }
            main.postDelayed(this, 750)
        }
    }

    private fun helperDied(ticket: Int) {
        if (ticket == generation) finish("Input helper disconnected. Check Shizuku and try again.", remapError = mode == REMAP)
    }

    internal fun shizukuDisconnected() {
        if (mode != null) finish("Shizuku disconnected. Restart it and resume from the Button page.", remapError = mode == REMAP)
    }

    internal fun endTest() {
        if (mode == TEST) finish("Button test stopped.")
    }

    internal fun stopSession(reason: String) = finish(reason)

    private fun foreground(detail: String, testing: Boolean) {
        val notification = notices.session(detail, testing)
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(SessionNotifications.SESSION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(SessionNotifications.SESSION_ID, notification)
    }

    private fun finish(reason: String, remapError: Boolean = false) {
        val wasRemapping = mode == REMAP || remapError
        closeSession()
        RemapperRuntime.change { it.copy(starting = !wasRemapping && it.startingRemap, running = false, observing = false,
            startingRemap = !wasRemapping && it.startingRemap, diagnostics = reason,
            remapIssue = if (remapError) reason else it.remapIssue) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun closeSession() {
        generation++
        mode = null
        ready = false
        config = null
        configJob?.cancel()
        heartbeatJob?.cancel()
        main.removeCallbacksAndMessages(null)
        session?.close()
        session = null
        notices.clearOffer()
        val oldHelper = helper
        val oldArgs = args
        val oldConnection = connection
        val oldExited = exited
        helper = null
        args = null
        connection = null
        exited = CountDownLatch(1)
        if (oldArgs != null) control.execute {
            if (oldHelper != null) {
                runCatching { oldHelper.stop() }
                oldExited.await(800, TimeUnit.MILLISECONDS)
            }
            runCatching { Shizuku.unbindUserService(oldArgs, oldConnection, true) }
        }
    }

    override fun onDestroy() {
        val hadSession = mode != null
        closeSession()
        if (RemapperRuntime.inputService === this) {
            RemapperRuntime.inputService = null
            if (hadSession) RemapperRuntime.change {
                it.copy(starting = pendingRemap(), startingRemap = pendingRemap(), running = false, observing = false)
            }
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun pendingRemap() = RemapperRuntime.pendingAction == REMAP

    companion object {
        internal const val REMAP = "remap"
        internal const val TEST = "test"
        private val control = Executors.newSingleThreadExecutor { Thread(it, "Shizuku-session-control") }
    }
}
