package io.github.xiaomibutton.remapper.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.xiaomibutton.remapper.ActionKind
import io.github.xiaomibutton.remapper.ActionSpec
import io.github.xiaomibutton.remapper.AppConfig
import io.github.xiaomibutton.remapper.GestureSlot
import io.github.xiaomibutton.remapper.SequenceConfig
import io.github.xiaomibutton.remapper.TriggerCondition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

/** Recognizes and dispatches gestures for one physical input session. */
internal class RemapSession(
    private val service: Context,
    private val scope: CoroutineScope,
    notifications: SessionNotifications,
    helper: IInputHelper,
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val dispatcher = ActionDispatcher(service, notifications, helper)
    private val actions = Channel<ActionSpec>(4)
    private var sequenceJob: Deferred<String>? = null
    private var configuration: AppConfig? = null
    private var engine: GestureEngine? = null
    private var profile: Map<GestureSlot, ActionSpec>? = null
    private val triggerState = TriggerState(service)
    private val timer = Runnable { engine?.advance(SystemClock.uptimeMillis()) }
    private val emergencyHold = Runnable {
        RemapperRuntime.stop(service, "Emergency stop. AI button held eight seconds.")
    }

    private val actionJob: Job = scope.launch {
        for (action in actions) {
            val message = try {
                if (configuration?.haptics == true) dispatcher.haptic()
                if (action.kind == ActionKind.SEQUENCE) supervisorScope {
                    val sequence = SequenceConfig.fromAction(action)
                    val work = async {
                        withTimeoutOrNull(120_000L) { sequence.run({ step -> dispatcher.perform(step) }) }
                            ?: error("Sequence timed out")
                    }
                    sequenceJob = work
                    try { work.await() } finally { sequenceJob = null }
                } else dispatcher.perform(action)
            } catch (e: CancellationException) {
                if (!isActive) throw e
                "Sequence stopped after configuration changed"
            } catch (e: Exception) {
                "Action unavailable: ${e.message ?: e.javaClass.simpleName}"
            }
            RemapperRuntime.change { it.copy(lastAction = message) }
        }
    }

    fun configure(config: AppConfig) {
        if (configuration == config) return
        sequenceJob?.cancel()
        while (actions.tryReceive().isSuccess) { /* discard gestures bound to previous mappings */ }
        configuration = config
        main.removeCallbacks(timer)
        engine?.reset()
        engine = null
    }

    private fun refreshEngine(config: AppConfig) {
        val overrides = if (config.profiles.values.any { it.isNotEmpty() }) {
            ButtonAccessibilityService.connected?.activeAppForProfiles()?.let(config.profiles::get)
        } else null
        if (engine != null && overrides == profile) return
        profile = overrides
        fun action(slot: GestureSlot) = overrides?.get(slot) ?: config.action(slot)
        engine = GestureEngine(config.doubleMs.toLong(), config.longMs.toLong(), config.debounceMs.toLong(),
            action(GestureSlot.DOUBLE).kind != ActionKind.NONE, action(GestureSlot.TRIPLE).kind != ActionKind.NONE,
            action(GestureSlot.LONG).kind != ActionKind.NONE) { gesture ->
            val condition = config.conditions[gesture] ?: TriggerCondition.ALWAYS
            if (!runCatching { triggerState.allows(condition) }.getOrDefault(false)) {
                RemapperRuntime.change { it.copy(lastAction = "Skipped ${gesture.title.lowercase()}: ${condition.title}") }
            } else if (actions.trySend(action(gesture)).isFailure) {
                RemapperRuntime.change { it.copy(lastAction = "Action queue busy. Press again.") }
            }
        }
    }

    fun receive(value: Int, time: Long) {
        RemapperRuntime.recordEvent(value, time)
        main.removeCallbacks(timer)
        if (value != 2) {
            main.removeCallbacks(emergencyHold)
            if (value == 1) main.postAtTime(emergencyHold, time + 8000)
        }
        if (value == 1) {
            engine?.advance(time)
            if (engine?.inProgress != true) configuration?.let(::refreshEngine)
        }
        engine?.key(value, time)
        engine?.nextDeadline?.let { main.postAtTime(timer, it) }
    }

    override fun close() {
        main.removeCallbacks(emergencyHold)
        main.removeCallbacks(timer)
        engine?.reset()
        actionJob.cancel()
        actions.close()
        dispatcher.close()
    }
}
