package io.github.xiaomibutton.remapper.backscreen.dashboard

import android.content.*
import android.os.*
import io.github.xiaomibutton.remapper.*
import io.github.xiaomibutton.remapper.backscreen.core.PhoneLock
import io.github.xiaomibutton.remapper.runtime.*
import java.util.UUID
import kotlinx.coroutines.*
import rikka.shizuku.Shizuku

/** A short-lived action session. It never opens the input device or interrupts button remapping. */
internal suspend fun <T> withDashboardHelper(
    context: Context,
    task: suspend (IInputHelper) -> T,
): T =
    withContext(Dispatchers.Main.immediate) {
        check(
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED,
        ) {
            "Grant Shizuku access first"
        }
        val app = context.applicationContext
        val args =
            Shizuku.UserServiceArgs(ComponentName(app, InputUserService::class.java))
                .daemon(false)
                .tag("dashboard-" + UUID.randomUUID())
                .processNameSuffix("rear_action")
                .version(3)
        val deferred = CompletableDeferred<IInputHelper>()
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    if (binder != null) deferred.complete(IInputHelper.Stub.asInterface(binder))
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    deferred.completeExceptionally(IllegalStateException("Shizuku disconnected"))
                }
            }
        Shizuku.bindUserService(args, connection)
        try {
            val helper = withTimeout(8000) { deferred.await() }
            val owner = Binder()
            helper.dashboardSession(owner)
            task(helper)
        } finally {
            runCatching { Shizuku.unbindUserService(args, connection, true) }
        }
    }

internal fun lockEligible(spec: ActionSpec): Boolean =
    spec.kind in setOf(ActionKind.FLASHLIGHT, ActionKind.SILENT, ActionKind.DND) ||
        spec.kind == ActionKind.CATALOG &&
            (spec.value in
                setOf(
                    "media.toggle",
                    "media.next",
                    "media.previous",
                    "media.pause",
                    "media.play",
                ) || spec.value in setOf("flash.toggle", "flash.on", "flash.off"))

internal suspend fun runTile(s: DashboardStore, id: String): String {
    val action = s.action(id) ?: error("Choose an action on the front")
    fun checkLock(spec: ActionSpec) {
        if (!PhoneLock.isLocked(s.context)) return
        check(s.flag("locked.$id") && lockEligible(spec)) { "Unlock the phone for this action" }
    }
    checkLock(action)
    return withDashboardHelper(s.context) { helper ->
        ActionDispatcher(s.context, SessionNotifications(s.context), helper).use { dispatcher ->
            suspend fun step(a: ActionSpec): String {
                checkLock(a)
                return dispatcher.perform(a)
            }
            if (action.kind == ActionKind.SEQUENCE) SequenceConfig.fromAction(action).run(::step)
            else step(action)
        }
    }
}
