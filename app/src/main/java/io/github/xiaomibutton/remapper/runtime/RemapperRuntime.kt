package io.github.xiaomibutton.remapper.runtime

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import io.github.xiaomibutton.remapper.ConfigStore
import io.github.xiaomibutton.remapper.xiaomi17ModelLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
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
    val running: Boolean = false,
    val observing: Boolean = false,
    val diagnostics: String = "Start the button test to check the physical AI button.",
    val lastAction: String = "",
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
        Shizuku.addBinderReceivedListenerSticky { scope.launch { refresh(app) } }
        Shizuku.addBinderDeadListener {
            scope.launch {
                refresh(app)
                pendingAction = null
                testAfterGrant = false
                change { it.copy(shizukuPermissionPending = false, starting = false) }
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
                if (uiVisible) startPendingTest(app)
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
        change { it.copy(shizukuInstalled = installed, shizukuRunning = running, shizukuAuthorized = authorized) }
    }

    fun uiResumed(context: Context) {
        uiVisible = true
        refresh(context)
        startPendingTest(context)
    }

    fun uiPaused() { uiVisible = false }

    private fun startPendingTest(context: Context) {
        if (!testAfterGrant) return
        testAfterGrant = false
        test(context)
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

    fun start(context: Context) = begin(context, RemapService.REMAP)
    fun test(context: Context) {
        if (!status.value.running && !status.value.starting && !status.value.observing) begin(context, RemapService.TEST)
    }

    private fun begin(context: Context, action: String) {
        if (xiaomi17ModelLabel(Build.MODEL, Build.DEVICE) != null) return
        initialize(context)
        refresh(context)
        if (!status.value.shizukuAuthorized) {
            change { it.copy(diagnostics = "Start Shizuku and grant it access first.") }
            return
        }
        pendingAction = action
        change { it.copy(starting = true, events = emptyList(), diagnostics = "Connecting to the physical button…") }
        runCatching {
            context.startForegroundService(Intent(context, RemapService::class.java).setAction(action))
        }.onFailure { error ->
            pendingAction = null
            change { it.copy(starting = false, diagnostics = "Could not start listening. Return to the remapper and tap Test button. ${error.message.orEmpty()}") }
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
            change { it.copy(starting = false) }
        }
        inputService?.endTest()
    }

    fun stop(context: Context, reason: String = "Remapping off. The original Xiaomi button action is available.") {
        pendingAction = null
        inputService?.stopSession(reason)
        change { it.copy(starting = false, diagnostics = reason) }
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
    }

    internal fun change(update: (RuntimeStatus) -> RuntimeStatus) = mutable.update(update)
}
