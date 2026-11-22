package io.github.xiaomibutton.remapper.runtime

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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

data class RuntimeStatus(
    val available: Boolean = false,
    val authorized: Boolean = false,
    val shizuku: String = "Disconnected",
    val helper: String = "Stopped",
    val running: Boolean = false,
    val observing: Boolean = false,
    val diagnostics: String = "Open the live tester to inspect the physical button.",
    val lastAction: String = "",
    val events: List<ButtonEvent> = emptyList(),
    val androidKeyCode: Int? = null,
)

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
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(a: Activity) { started++; visible = true }
            override fun onActivityStopped(a: Activity) { started--; visible = started > 0 }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
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
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                change { it.copy(diagnostics = "Permission was denied. Allow the remapper in Shizuku’s Authorized applications.") }
            } else Shizuku.requestPermission(814)
        }
            .onFailure { error -> change { it.copy(diagnostics = error.message ?: "Permission request failed") } }
    }

    fun start(context: Context) = begin(context, RemapService.REMAP)
    fun test(context: Context) = begin(context, RemapService.TEST)

    private fun begin(context: Context, action: String) {
        initialize(context)
        refresh()
        if (!status.value.authorized) {
            change { it.copy(diagnostics = "Start Shizuku and authorize the remapper first.") }
            return
        }
        testRequested = action == RemapService.TEST
        runCatching {
            context.startForegroundService(Intent(context, RemapService::class.java).setAction(action))
        }.onFailure { error -> change { it.copy(diagnostics = "Unable to start service: ${error.message}") } }
    }

    fun endTest(context: Context) {
        initialize(context)
        testRequested = false
        onEndTest?.invoke()
    }

    fun stop(context: Context) {
        initialize(context)
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

    internal fun change(update: (RuntimeStatus) -> RuntimeStatus) = mutable.update(update)
}
