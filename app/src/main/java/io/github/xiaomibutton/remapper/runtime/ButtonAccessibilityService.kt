package io.github.xiaomibutton.remapper.runtime

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import io.github.xiaomibutton.remapper.AppConfig
import io.github.xiaomibutton.remapper.ConfigStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

/** Hosts physical-button detection and user-initiated accessibility actions. */
class ButtonAccessibilityService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val filter = FrameworkKeyFilter()
    private var listener: ((Int, Long, Boolean) -> Unit)? = null
    private var intercept = false
    private var editorRequested = false
    private var learning: TwoPressCalibration? = null
    private var learned: LearnedKey? = null
    private var observing = false
    private var session: RemapSession? = null
    private var configJob: Job? = null
    private var latestConfig: AppConfig? = null
    private var pausedUntilDisabled = false
    internal val canInspectWindow: Boolean get() = serviceInfo?.let {
        (it.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT) != 0
    } == true

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            applicationContext.learnedButtons.data.catch { failure ->
                if (failure !is IOException) throw failure
                emit(emptyPreferences())
            }.collect { values ->
                val descriptor = values[DEVICE]
                val code = values[CODE]
                val scan = values[SCAN] ?: 0
                learned = if (!descriptor.isNullOrBlank() && code != null &&
                    (scan == 689 || scan == 0 && FrameworkKey.learnable(code, KeyEvent.getMaxKeyCode())))
                    LearnedKey(descriptor, code, scan) else null
                RemapperRuntime.change { it.copy(calibratedKey = learned?.let { k -> KeyEvent.keyCodeToString(k.code) }) }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        RemapperRuntime.initialize(this)
        connected = this
        refreshFlags()
        RemapperRuntime.change { it.copy(accessibilityConnected = true) }
        configJob?.cancel()
        configJob = scope.launch {
            ConfigStore(applicationContext).config.collect(::applyConfig)
        }
    }

    private fun applyConfig(config: AppConfig) {
        latestConfig = config
        if (!config.enabled) {
            pausedUntilDisabled = false
            closeRemapping()
            return
        }
        if (connected !== this || pausedUntilDisabled) return
        stopObservation()
        val active = session ?: RemapSession(this, scope).also {
            session = it
            RemapperRuntime.clearTesterEvents()
            attach(!config.keepOriginalAction, it::receive)
            RemapperRuntime.change { state -> state.copy(running = true,
                diagnostics = "Remapping enabled. Press the AI button to verify delivery.") }
        }
        intercept = !config.keepOriginalAction
        active.configure(config)
    }

    internal fun resumeAfterUserEnable() {
        pausedUntilDisabled = false
        latestConfig?.let(::applyConfig)
    }

    internal fun pauseRemapping() {
        pausedUntilDisabled = true
        closeRemapping()
    }

    private fun closeRemapping() {
        val active = session ?: return
        session = null
        detach()
        active.close()
        RemapperRuntime.change { it.copy(running = false) }
    }

    private fun attach(capture: Boolean, event: (Int, Long, Boolean) -> Unit) {
        check(connected === this)
        cancelCalibration()
        listener = event
        intercept = capture
        refreshFlags()
    }

    internal fun startObservation() {
        if (listener != null || connected !== this) return
        RemapperRuntime.clearTesterEvents()
        observing = true
        attach(false) { value, time, _ -> if (value >= 0) RemapperRuntime.recordEvent(value, time) }
        RemapperRuntime.change { it.copy(observing = true, diagnostics = "Listening through Accessibility for 60 seconds.") }
        main.postDelayed(observationTimeout, 60_000L)
    }

    private val observationTimeout = Runnable { stopObservation("Tester finished. The events remain below.") }

    internal fun stopObservation(reason: String = "Read-only tester stopped") {
        if (!observing) return
        observing = false
        main.removeCallbacks(observationTimeout)
        detach()
        RemapperRuntime.change { it.copy(observing = false, diagnostics = reason) }
    }

    private fun detach() {
        listener = null
        intercept = false
        if (filter.hasHeld) {
            main.removeCallbacks(clearStale)
            main.postDelayed(clearStale, 12_000)
        }
    }

    private val clearStale = Runnable {
        if (listener == null) filter.reset()
    }

    internal fun refreshFlags() {
        if (connected !== this) return
        val info = serviceInfo ?: return
        val viewIds = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        var flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        flags = if (RemapperRuntime.status.value.uiActionsEnabled) flags or viewIds else flags and viewIds.inv()
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

    @Suppress("DEPRECATION")
    internal fun activeAppForProfiles(): String? {
        if (getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false) return null
        return runCatching {
            val root = rootInActiveWindow ?: return null
            try { root.packageName?.toString() } finally { root.recycle() }
        }.getOrNull()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (listener == null && learning == null && !filter.hasHeld) return false
        val value = when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount > 0) 2 else 1
            KeyEvent.ACTION_UP -> 0
            else -> return false
        }
        val key = event.buttonIdentity()
        if (observing && value == 1 && event.keyCode in VOLUME_KEYS) {
            RemapperRuntime.change { it.copy(frameworkVolumeSeen = true) }
        }
        val learner = learning
        val target = key?.target(learned) == true
        if ((observing || learner != null) && !target && value == 1 &&
            (event.scanCode == 689 || key?.candidate() != null)) {
            val source = key?.name ?: "unidentified input"
            RemapperRuntime.change { it.copy(frameworkInput = "$source · scan ${event.scanCode} · Android code ${event.keyCode}") }
        }
        if (key == null) return false
        if (learner != null) {
            learner.key(key, value, event.isCanceled)
            val candidate = learner.candidate
            if (candidate != null && RemapperRuntime.status.value.calibrationCandidate == null) {
                val label = "${KeyEvent.keyCodeToString(candidate.code)} (${candidate.code}) on ${key.name}"
                RemapperRuntime.change { it.copy(calibrationPrompt = "Confirm the AI button you pressed twice.",
                    calibrationCandidate = label) }
            }
        }
        val consume = filter.consume(key, value, intercept, target)
        val receive = listener
        if (receive != null && target && (!intercept || consume)) {
            val time = event.eventTime
            val canceled = event.isCanceled
            // Return the synchronous filtering decision before dispatching gestures or launching activities.
            main.post {
                if (listener === receive) {
                    RemapperRuntime.change { it.copy(androidKeyCode = key.code, diagnostics =
                        "Android Accessibility · ${key.name} · scan ${key.scan} · ${KeyEvent.keyCodeToString(key.code)} (${key.code})") }
                    receive(value, time, canceled)
                }
            }
        }
        return consume
    }

    internal fun beginCalibration() {
        if (listener != null || connected !== this) return
        cancelCalibration()
        learning = TwoPressCalibration()
        RemapperRuntime.change { it.copy(calibrationPrompt = "Press and release only the AI button twice within 20 seconds.",
            calibrationCandidate = null) }
        main.postDelayed(calibrationTimeout, 20_000)
    }

    internal fun confirmCalibration() {
        val choice = learning?.candidate ?: return
        cancelCalibration()
        scope.launch {
            runCatching { applicationContext.learnedButtons.edit {
                it[DEVICE] = choice.descriptor
                it[CODE] = choice.code
                it[SCAN] = choice.scan
            } }
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
    }

    private val calibrationTimeout = Runnable {
        cancelCalibration()
        RemapperRuntime.change { it.copy(diagnostics = "No compatible button was learned. Check the live tester with the screen on.") }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {
        listener?.invoke(-1, SystemClock.uptimeMillis(), true)
    }

    private fun disconnect() {
        if (connected !== this) return
        connected = null
        configJob?.cancel()
        latestConfig = null
        stopObservation("Android disconnected Accessibility. Turn the remapper service off and back on in Settings.")
        closeRemapping()
        cancelCalibration()
        filter.reset()
        main.removeCallbacks(clearStale)
        RemapperRuntime.change { it.copy(accessibilityConnected = false,
            diagnostics = "Accessibility disconnected. Check the setup steps below.") }
    }

    override fun onUnbind(intent: Intent?): Boolean { disconnect(); return super.onUnbind(intent) }
    override fun onDestroy() { disconnect(); scope.cancel(); super.onDestroy() }

    internal companion object {
        var connected: ButtonAccessibilityService? = null
            private set
        private val DEVICE = stringPreferencesKey("descriptor")
        private val CODE = intPreferencesKey("android_key_code")
        private val SCAN = intPreferencesKey("scan_code")
        private val VOLUME_KEYS = setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN)
    }
}

private fun KeyEvent.buttonIdentity(): FrameworkKey? {
    val source = device ?: return null
    return FrameworkKey(source.id, source.descriptor, source.name, source.isExternal, source.isVirtual,
        scanCode, keyCode, downTime, KeyEvent.getMaxKeyCode())
}

internal fun KeyEvent.isDirectAiButton(): Boolean = buttonIdentity()?.target(null) == true
