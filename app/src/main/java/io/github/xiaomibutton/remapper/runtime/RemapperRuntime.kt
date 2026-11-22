package io.github.xiaomibutton.remapper.runtime

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import io.github.xiaomibutton.remapper.ConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

data class ButtonEvent(val value: Int, val timeMs: Long, val wallTimeMs: Long)

enum class InputMethod(val label: String) {
    ACCESSIBILITY("Accessibility"),
    SHIZUKU("Shizuku")
}

data class RuntimeStatus(
    val available: Boolean = false,
    val authorized: Boolean = false,
    val accessibilityConnected: Boolean = false,
    val inputMethod: InputMethod = InputMethod.ACCESSIBILITY,
    val activeInput: InputMethod? = null,
    val uiActionsEnabled: Boolean = false,
    val calibrationPrompt: String = "",
    val calibrationCandidate: String? = null,
    val calibratedKey: String? = null,
    val shizuku: String = "Disconnected",
    val helper: String = "Stopped",
    val running: Boolean = false,
    val observing: Boolean = false,
    val diagnostics: String = "Open the live tester to inspect the physical button.",
    val lastAction: String = "",
    val events: List<ButtonEvent> = emptyList(),
    val androidKeyCode: Int? = null,
    val frameworkInput: String? = null,
    val frameworkVolumeSeen: Boolean = false,
) {
    val backendReady: Boolean get() = when (inputMethod) {
        InputMethod.ACCESSIBILITY -> accessibilityConnected
        InputMethod.SHIZUKU -> authorized
    }
}

object RemapperRuntime {
    private val mutable = MutableStateFlow(RuntimeStatus())
    val status: StateFlow<RuntimeStatus> = mutable
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var initialized = false
    internal var onShizukuLost: (() -> Unit)? = null
    internal var testRequested = false
        private set
    internal var onEndTest: (() -> Unit)? = null
    internal var onStop: (() -> Unit)? = null
    internal var visible = false
        private set

    @Synchronized fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext as Application
        val settings = settings(app)
        change {
            it.copy(inputMethod = if (settings.getBoolean("shizuku_input", false)) InputMethod.SHIZUKU else InputMethod.ACCESSIBILITY,
                uiActionsEnabled = settings.getBoolean("ui_actions", false))
        }
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(a: Activity) { started++; visible = true }
            override fun onActivityStopped(a: Activity) { started--; visible = started > 0 }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) { refresh() }
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        Shizuku.addBinderReceivedListenerSticky { refresh() }
        Shizuku.addBinderDeadListener {
            refresh()
            onShizukuLost?.invoke()
        }
        Shizuku.addRequestPermissionResultListener { _, _ -> refresh() }
        refresh()
    }

    private fun refresh() {
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val version = if (alive) runCatching { Shizuku.getVersion() }.getOrDefault(0) else 0
        val authorized = version >= 13 && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        change { it.copy(available = alive, authorized = authorized, shizuku = when {
            !alive -> "Disconnected"
            version < 13 -> "Shizuku v13 or later required"
            authorized -> "Authorized"
            else -> "Permission required"
        }) }
    }

    fun requestPermission() {
        refresh()
        if (!status.value.available || status.value.authorized) return
        runCatching {
            if (Shizuku.getVersion() < 13) {
                change { it.copy(diagnostics = "Install Shizuku v13 or later for the privileged helper.") }
            } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                change { it.copy(diagnostics = "Permission was denied. Allow the remapper in Shizuku’s Authorized applications.") }
            } else Shizuku.requestPermission(814)
        }
            .onFailure { error -> change { it.copy(diagnostics = error.message ?: "Permission request failed") } }
    }

    fun openAccessibility(context: Context) {
        runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure {
            change { it.copy(diagnostics = "Open Android Settings → Accessibility → Xiaomi AI Button Remapper.") }
        }
    }

    fun beginCalibration(context: Context) { initialize(context); ButtonAccessibilityService.connected?.beginCalibration() }
    fun confirmCalibration(context: Context) { initialize(context); ButtonAccessibilityService.connected?.confirmCalibration() }
    fun cancelCalibration() { ButtonAccessibilityService.connected?.cancelCalibration() }
    fun clearCalibration(context: Context) { initialize(context); ButtonAccessibilityService.connected?.clearCalibration() }

    private fun settings(context: Context) = context.applicationContext.getSharedPreferences("device_input", Context.MODE_PRIVATE)

    fun selectInput(context: Context, method: InputMethod) {
        initialize(context)
        if (method == status.value.inputMethod || status.value.running || status.value.helper == "Connecting" ||
            status.value.helper == "Preparing") return
        endTest(context)
        cancelCalibration()
        clearTesterEvents()
        settings(context).edit().putBoolean("shizuku_input", method == InputMethod.SHIZUKU).apply()
        change { it.copy(inputMethod = method, diagnostics = "Selected ${method.label}. Start the live tester to check the button.") }
        ButtonAccessibilityService.connected?.refreshFlags()
    }

    fun enableUiActions(context: Context, enabled: Boolean) {
        initialize(context)
        settings(context).edit().putBoolean("ui_actions", enabled).apply()
        change { it.copy(uiActionsEnabled = enabled) }
        ButtonAccessibilityService.connected?.refreshFlags()
    }

    fun start(context: Context) {
        endTest(context)
        begin(context, RemapService.REMAP, status.value.inputMethod == InputMethod.SHIZUKU)
    }

    fun test(context: Context) {
        initialize(context)
        if (status.value.activeInput != null) return
        if (status.value.inputMethod == InputMethod.SHIZUKU) begin(context, RemapService.TEST, true)
        else {
            val service = ButtonAccessibilityService.connected
            if (service == null) change { it.copy(diagnostics = "Enable Xiaomi AI Button Remapper in Android Accessibility settings.") }
            else service.startObservation()
        }
    }

    private fun begin(context: Context, action: String, expert: Boolean) {
        initialize(context)
        if (expert) refresh()
        if (if (expert) !status.value.authorized else !status.value.accessibilityConnected) {
            change { it.copy(diagnostics = if (expert) "Authorize Shizuku to use Expert mode."
                else "Enable Xiaomi AI Button Remapper in Android Accessibility settings.") }
            return
        }
        clearTesterEvents()
        testRequested = action == RemapService.TEST
        runCatching {
            context.startForegroundService(Intent(context, RemapService::class.java).setAction(action).putExtra(RemapService.EXPERT, expert))
        }.onFailure { error -> change { it.copy(diagnostics = "Unable to start service: ${error.message}") } }
    }

    fun endTest(context: Context) {
        initialize(context)
        ButtonAccessibilityService.connected?.stopObservation()
        testRequested = false
        onEndTest?.invoke()
    }

    fun stop(context: Context) {
        initialize(context)
        ButtonAccessibilityService.connected?.stopObservation()
        ButtonAccessibilityService.connected?.cancelCalibration()
        testRequested = false
        val stopSession = onStop
        if (stopSession != null) stopSession() else {
            context.stopService(Intent(context, RemapService::class.java))
            disableConfig(context)
        }
    }

    internal fun disableConfig(context: Context) {
        val store = ConfigStore(context.applicationContext)
        scope.launch {
            runCatching { store.update { it.copy(enabled = false) } }
                .onFailure { error -> change { it.copy(diagnostics = "Input stopped. Could not save disabled state: ${error.message}") } }
        }
    }

    fun recordAndroidKey(code: Int) = change { it.copy(androidKeyCode = code) }

    internal fun recordEvent(value: Int, uptime: Long) {
        val event = ButtonEvent(value, uptime, System.currentTimeMillis() - (SystemClock.uptimeMillis() - uptime))
        change { it.copy(events = (listOf(event) + it.events).take(40)) }
    }

    internal fun clearTesterEvents() {
        change { it.copy(events = emptyList(), androidKeyCode = null, frameworkInput = null, frameworkVolumeSeen = false) }
    }

    internal fun change(update: (RuntimeStatus) -> RuntimeStatus) = mutable.update(update)
}
