package io.github.xiaomibutton.remapper.runtime

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import io.github.xiaomibutton.remapper.AppLog
import io.github.xiaomibutton.remapper.ConfigStore
import io.github.xiaomibutton.remapper.LogArea
import io.github.xiaomibutton.remapper.xiaomi17ModelLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

data class ButtonEvent(val value: Int, val timeMs: Long, val wallTimeMs: Long)

data class RuntimeStatus(
    val shizukuInstalled: Boolean = false,
    val shizukuRunning: Boolean = false,
    val shizukuAuthorized: Boolean = false,
    val shizukuPermissionPending: Boolean = false,
    val accessibilityConnected: Boolean = false,
    val uiActionsEnabled: Boolean = false,
    val starting: Boolean = false,
    val startingRemap: Boolean = false,
    val running: Boolean = false,
    val observing: Boolean = false,
    val diagnostics: String = "Start the button test to check the physical AI button.",
    val lastAction: String = "",
    val remapIssue: String? = null,
    val events: List<ButtonEvent> = emptyList(),
)

object RemapperRuntime {
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val PERMISSION_REQUEST = 814
    private val mutable = MutableStateFlow(RuntimeStatus())
    val status: StateFlow<RuntimeStatus> = mutable
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var initialized = false
    private var testAfterGrant = false
    private var requestGeneration = 0
    internal var uiVisible = false
        private set
    internal var inputService: RemapService? = null
    internal var pendingAction: String? = null
        private set

    @Synchronized fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        change { it.copy(uiActionsEnabled = settings(app).getBoolean("ui_actions", false)) }
        Shizuku.addBinderReceivedListenerSticky { scope.launch {
            refresh(app)
            restoreConfigured(app)
        } }
        Shizuku.addBinderDeadListener {
            scope.launch {
                refresh(app)
                requestGeneration++
                pendingAction = null
                testAfterGrant = false
                change { it.copy(shizukuPermissionPending = false, starting = false, startingRemap = false,
                    remapIssue = if (it.startingRemap) "Shizuku disconnected while remapping was starting." else it.remapIssue) }
                inputService?.shizukuDisconnected()
            }
        }
        Shizuku.addRequestPermissionResultListener { code, result ->
            if (code == PERMISSION_REQUEST) scope.launch {
                refresh(app)
                val authorized = result == PackageManager.PERMISSION_GRANTED && status.value.shizukuAuthorized
                change { it.copy(shizukuPermissionPending = false, diagnostics =
                    if (authorized) "Shizuku access granted. Starting the button test…"
                    else "Shizuku access was declined. Allow access to read the AI button.") }
                testAfterGrant = authorized
                if (uiVisible) {
                    startPendingTest(app)
                    restoreConfigured(app)
                }
            }
        }
        refresh(app)
    }

    fun refresh(context: Context) {
        val installed = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE) != null
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val authorized = running && runCatching {
            Shizuku.getVersion() >= 13 && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        val previous = status.value
        if (previous.shizukuInstalled != installed || previous.shizukuRunning != running ||
            previous.shizukuAuthorized != authorized) {
            AppLog.add(LogArea.SHIZUKU, when {
                authorized -> "Connected and authorized"
                running -> "Connected · authorization required"
                installed -> "Installed and disconnected"
                else -> "Disconnected"
            })
        }
        change { it.copy(shizukuInstalled = installed, shizukuRunning = running, shizukuAuthorized = authorized) }
    }

    fun uiResumed(context: Context) {
        uiVisible = true
        refresh(context)
        startPendingTest(context)
        scope.launch { restoreConfigured(context.applicationContext) }
    }

    fun uiPaused() { uiVisible = false }

    private fun startPendingTest(context: Context) {
        if (!testAfterGrant) return
        testAfterGrant = false
        test(context)
    }

    /** Restore a saved remap once Shizuku is ready; rear-display features have their own keeper. */
    private suspend fun restoreConfigured(context: Context) {
        if (!status.value.shizukuAuthorized || status.value.running || status.value.startingRemap) return
        if (!ConfigStore(context).config.first().enabled) return
        AppLog.add(LogArea.BUTTON, "Restoring saved remapping")
        start(context)
    }

    fun requestShizuku(context: Context) {
        initialize(context)
        refresh(context)
        if (status.value.shizukuAuthorized) {
            test(context)
            return
        }
        if (!status.value.shizukuRunning || status.value.shizukuPermissionPending) return
        runCatching {
            when {
                Shizuku.getVersion() < 13 -> change { it.copy(diagnostics = "Update Shizuku to version 13 or later.") }
                Shizuku.shouldShowRequestPermissionRationale() ->
                    change { it.copy(diagnostics = "Open Authorized applications in Shizuku and allow Xiaomi AI Button Remapper.") }
                else -> {
                    AppLog.add(LogArea.SHIZUKU, "Requesting authorization")
                    change { it.copy(shizukuPermissionPending = true) }
                    Shizuku.requestPermission(PERMISSION_REQUEST)
                }
            }
        }.onFailure { error ->
            change { it.copy(shizukuPermissionPending = false, diagnostics = "Shizuku could not ask for access: ${error.message}") }
        }
    }

    fun openAccessibility(context: Context) {
        runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { change { it.copy(lastAction = "Open Android Settings → Accessibility → Xiaomi AI Button Remapper.") } }
    }

    private fun settings(context: Context) =
        context.applicationContext.getSharedPreferences("device_input", Context.MODE_PRIVATE)

    fun enableUiActions(context: Context, enabled: Boolean) {
        initialize(context)
        settings(context).edit().putBoolean("ui_actions", enabled).apply()
        change { it.copy(uiActionsEnabled = enabled) }
        ButtonAccessibilityService.connected?.refreshFlags()
    }

    fun start(context: Context, runAlongsideXiaomi: Boolean = false) {
        if (xiaomi17ModelLabel(Build.MODEL, Build.DEVICE) != null) return
        val app = context.applicationContext
        initialize(app)
        refresh(app)
        if (status.value.running || status.value.startingRemap) return
        if (!status.value.shizukuAuthorized) {
            remapFailed("Start Shizuku and grant it access before enabling remapping.")
            return
        }
        testAfterGrant = false
        val ticket = ++requestGeneration
        change { it.copy(starting = true, startingRemap = true, remapIssue = null,
            diagnostics = "Starting remapping…") }
        scope.launch {
            val failure = runCatching {
                ConfigStore(app).update { config ->
                    config.copy(enabled = true, keepOriginalAction = runAlongsideXiaomi || config.keepOriginalAction)
                }
            }.exceptionOrNull()
            if (ticket != requestGeneration) return@launch
            if (failure == null) begin(app, RemapService.REMAP)
            else remapFailed("Could not save remapping settings (${failure.javaClass.simpleName}).")
        }
    }

    fun test(context: Context) {
        if (!status.value.running && !status.value.starting && !status.value.observing) begin(context, RemapService.TEST)
    }

    private fun begin(context: Context, action: String) {
        if (xiaomi17ModelLabel(Build.MODEL, Build.DEVICE) != null) return
        initialize(context)
        refresh(context)
        if (!status.value.shizukuAuthorized) {
            if (action == RemapService.REMAP) remapFailed("Shizuku disconnected while remapping was starting.")
            else change { it.copy(diagnostics = "Start Shizuku and grant it access first.") }
            return
        }
        pendingAction = action
        change { it.copy(starting = true, events = emptyList(), diagnostics = "Connecting to the physical button…") }
        runCatching {
            context.startForegroundService(Intent(context, RemapService::class.java).setAction(action))
        }.onFailure { error ->
            pendingAction = null
            val message = "Android refused to start the input service: ${error.message ?: error.javaClass.simpleName}"
            if (action == RemapService.REMAP) remapFailed(message)
            else change { it.copy(starting = false, diagnostics = message) }
        }
    }

    internal fun takeRequest(action: String): Boolean {
        if (pendingAction != action) return false
        pendingAction = null
        return true
    }

    fun endTest() {
        // Let a queued foreground start satisfy Android's foreground deadline, then discard it.
        if (pendingAction == RemapService.TEST) {
            pendingAction = null
            change { it.copy(starting = it.startingRemap) }
        }
        inputService?.endTest()
    }

    fun stop(context: Context, reason: String = "Remapping off. The original Xiaomi button action is available.") {
        requestGeneration++
        pendingAction = null
        inputService?.stopSession(reason)
        change { it.copy(starting = false, startingRemap = false, remapIssue = null, diagnostics = reason) }
        disableConfig(context)
    }

    internal fun disableConfig(context: Context) {
        val store = ConfigStore(context.applicationContext)
        scope.launch {
            runCatching { store.update { it.copy(enabled = false) } }
                .onFailure { error -> change { it.copy(diagnostics = "Remapping stopped. Could not save the off setting: ${error.message}") } }
        }
    }

    internal fun recordEvent(value: Int, uptime: Long) {
        val event = ButtonEvent(value, uptime, System.currentTimeMillis() - (SystemClock.uptimeMillis() - uptime))
        change { it.copy(events = (listOf(event) + it.events).take(40)) }
        if (value != 2) AppLog.add(LogArea.BUTTON, "Physical button ${if (value == 1) "down" else "up"}")
    }

    private fun remapFailed(message: String) {
        change { it.copy(starting = false, startingRemap = false, diagnostics = message, remapIssue = message) }
    }

    internal fun change(update: (RuntimeStatus) -> RuntimeStatus) {
        val before = mutable.getAndUpdate(update)
        val after = mutable.value
        if (before.diagnostics != after.diagnostics) AppLog.add(LogArea.BUTTON, after.diagnostics)
    }
}
