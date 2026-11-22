package io.github.xiaomibutton.remapper.runtime

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.IOException

private val Context.learnedButtons by preferencesDataStore(
    name = "local_framework_key",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

/** Processes hardware-key metadata only, on Android's accessibility main thread. */
class ButtonAccessibilityService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val filter = FrameworkKeyFilter()
    private var listener: ((Int, Long, Boolean) -> Unit)? = null
    private var intercept = false
    private var editorRequested = false
    private var learning: TwoPressCalibration? = null
    private var learned: LearnedKey? = null
    private var onLost: (() -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            applicationContext.learnedButtons.data.catch { failure ->
                if (failure !is IOException) throw failure
                emit(emptyPreferences())
            }.collect { values ->
                val descriptor = values[DEVICE]
                val code = values[CODE]
                learned = if (!descriptor.isNullOrBlank() && code != null && FrameworkKey.learnable(code, KeyEvent.getMaxKeyCode()))
                    LearnedKey(descriptor, code) else null
                RemapperRuntime.change { it.copy(calibratedKey = learned?.let { k -> KeyEvent.keyCodeToString(k.code) }) }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = this
        refreshFlags()
        RemapperRuntime.change { it.copy(accessibilityConnected = true) }
    }

    internal fun attach(capture: Boolean, lost: () -> Unit, event: (Int, Long, Boolean) -> Unit) {
        check(connected === this)
        cancelCalibration()
        listener = event
        onLost = lost
        intercept = capture
        refreshFlags()
    }

    internal fun detach() {
        listener = null
        onLost = null
        intercept = false
        refreshFlags()
        if (filter.hasHeld) {
            main.removeCallbacks(clearStale)
            main.postDelayed(clearStale, 12_000)
        }
    }

    private val clearStale = Runnable {
        if (listener == null) { filter.reset(); refreshFlags() }
    }

    private fun refreshFlags() {
        if (connected !== this) return
        val info = serviceInfo ?: return
        val flag = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        val needKeys = listener != null || learning != null || filter.hasHeld
        var flags = if (needKeys) info.flags or flag else info.flags and flag.inv()
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val ime = AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR
            flags = if (editorRequested) flags or ime else flags and ime.inv()
        }
        if (flags != info.flags) {
            info.flags = flags
            serviceInfo = info
        }
    }

    internal fun requestEditor(requested: Boolean) {
        editorRequested = requested
        refreshFlags()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val key = event.gpioIdentity() ?: return false
        val value = when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount > 0) 2 else 1
            KeyEvent.ACTION_UP -> 0
            else -> return false
        }
        val learner = learning
        if (learner != null) {
            learner.key(key, value, event.isCanceled)
            val candidate = learner.candidate
            if (candidate != null && RemapperRuntime.status.value.calibrationCandidate == null) {
                val label = "${KeyEvent.keyCodeToString(candidate.code)} (${candidate.code}) on gpio-keys"
                RemapperRuntime.change { it.copy(calibrationPrompt = "Confirm the AI button you pressed twice.",
                    calibrationCandidate = label) }
            }
        }
        val target = key.target(learned)
        val consume = filter.consume(key, value, intercept, target)
        val receive = listener
        if (receive != null && target && (!intercept || consume)) {
            val time = event.eventTime
            val canceled = event.isCanceled
            // Return the synchronous filtering decision before dispatching gestures or launching activities.
            main.post {
                if (listener === receive) {
                    RemapperRuntime.change { it.copy(androidKeyCode = key.code, diagnostics =
                        "Android Accessibility · gpio-keys · scan ${key.scan} · ${KeyEvent.keyCodeToString(key.code)} (${key.code})") }
                    receive(value, time, canceled)
                }
            }
        }
        if (consume && value == 0 && receive == null) refreshFlags()
        return consume
    }

    internal fun beginCalibration() {
        if (listener != null || connected !== this || !RemapperRuntime.visible) return
        cancelCalibration()
        learning = TwoPressCalibration()
        RemapperRuntime.change { it.copy(calibrationPrompt = "Press and release only the AI button twice within 20 seconds.",
            calibrationCandidate = null) }
        refreshFlags()
        main.postDelayed(calibrationTimeout, 20_000)
    }

    internal fun confirmCalibration() {
        val choice = learning?.candidate ?: return
        if (!RemapperRuntime.visible) return
        cancelCalibration()
        scope.launch {
            runCatching { applicationContext.learnedButtons.edit { it[DEVICE] = choice.descriptor; it[CODE] = choice.code } }
                .onFailure { error -> RemapperRuntime.change { it.copy(diagnostics = "Could not save calibration: ${error.message}") } }
        }
    }

    internal fun clearCalibration() {
        cancelCalibration()
        scope.launch {
            runCatching { applicationContext.learnedButtons.edit { it.clear() } }
                .onFailure { error -> RemapperRuntime.change { it.copy(diagnostics = "Could not clear calibration: ${error.message}") } }
        }
    }

    internal fun cancelCalibration() {
        main.removeCallbacks(calibrationTimeout)
        learning = null
        RemapperRuntime.change { it.copy(calibrationPrompt = "", calibrationCandidate = null) }
        refreshFlags()
    }

    private val calibrationTimeout = Runnable {
        cancelCalibration()
        RemapperRuntime.change { it.copy(diagnostics = "Calibration expired. Android must deliver a dedicated key from internal gpio-keys.") }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {
        listener?.invoke(-1, android.os.SystemClock.uptimeMillis(), true)
    }

    private fun disconnect() {
        if (connected !== this) return
        connected = null
        val lost = onLost
        detach()
        cancelCalibration()
        filter.reset()
        main.removeCallbacks(clearStale)
        RemapperRuntime.change { it.copy(accessibilityConnected = false) }
        lost?.invoke()
    }

    override fun onUnbind(intent: Intent?): Boolean { disconnect(); return super.onUnbind(intent) }
    override fun onDestroy() { disconnect(); scope.cancel(); super.onDestroy() }

    internal companion object {
        var connected: ButtonAccessibilityService? = null
            private set
        private val DEVICE = stringPreferencesKey("descriptor")
        private val CODE = intPreferencesKey("android_key_code")
    }
}

private fun KeyEvent.gpioIdentity(): FrameworkKey? {
    val source = device ?: return null
    if (source.name != "gpio-keys" || source.isVirtual || source.isExternal) return null
    return FrameworkKey(source.id, source.descriptor, source.name, source.isExternal, source.isVirtual,
        scanCode, keyCode, downTime, KeyEvent.getMaxKeyCode())
}

internal fun KeyEvent.isDirectAiButton(): Boolean = gpioIdentity()?.target(null) == true
