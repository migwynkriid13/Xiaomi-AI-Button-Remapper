package io.github.xiaomibutton.remapper.runtime

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import io.github.xiaomibutton.remapper.ConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ButtonEvent(val value: Int, val timeMs: Long, val wallTimeMs: Long)

data class RuntimeStatus(
    val accessibilityConnected: Boolean = false,
    val uiActionsEnabled: Boolean = false,
    val calibrationPrompt: String = "",
    val calibrationCandidate: String? = null,
    val calibratedKey: String? = null,
    val running: Boolean = false,
    val observing: Boolean = false,
    val diagnostics: String = "Finish accessibility setup, then start the live tester.",
    val lastAction: String = "",
    val events: List<ButtonEvent> = emptyList(),
    val androidKeyCode: Int? = null,
    val frameworkInput: String? = null,
    val frameworkVolumeSeen: Boolean = false,
)

object RemapperRuntime {
    private val mutable = MutableStateFlow(RuntimeStatus())
    val status: StateFlow<RuntimeStatus> = mutable
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var initialized = false

    @Synchronized fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        change { it.copy(uiActionsEnabled = settings(context).getBoolean("ui_actions", false)) }
    }

    fun openAccessibility(context: Context) {
        runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { change { it.copy(diagnostics = "Open Android Settings → Accessibility → Xiaomi AI Button Remapper.") } }
    }

    fun beginCalibration() { ButtonAccessibilityService.connected?.beginCalibration() }
    fun confirmCalibration() { ButtonAccessibilityService.connected?.confirmCalibration() }
    fun cancelCalibration() { ButtonAccessibilityService.connected?.cancelCalibration() }
    fun clearCalibration() { ButtonAccessibilityService.connected?.clearCalibration() }

    private fun settings(context: Context) =
        context.applicationContext.getSharedPreferences("device_input", Context.MODE_PRIVATE)

    fun enableUiActions(context: Context, enabled: Boolean) {
        initialize(context)
        settings(context).edit().putBoolean("ui_actions", enabled).apply()
        change { it.copy(uiActionsEnabled = enabled) }
        ButtonAccessibilityService.connected?.refreshFlags()
    }

    fun test() {
        ButtonAccessibilityService.connected?.startObservation()
    }

    fun resumeAfterUserEnable() {
        ButtonAccessibilityService.connected?.resumeAfterUserEnable()
    }

    fun endTest() {
        ButtonAccessibilityService.connected?.stopObservation()
        cancelCalibration()
    }

    fun stop(context: Context, reason: String = "Remapping off. The AI button uses Xiaomi's original behavior.") {
        endTest()
        ButtonAccessibilityService.connected?.pauseRemapping()
        change { it.copy(diagnostics = reason) }
        val store = ConfigStore(context.applicationContext)
        scope.launch {
            runCatching { store.update { it.copy(enabled = false) } }
                .onFailure { error -> change { it.copy(diagnostics = "Remapping stopped. Could not save the off setting: ${error.message}") } }
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
