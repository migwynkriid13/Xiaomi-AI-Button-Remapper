package io.github.xiaomibutton.remapper.backscreen.dashboard

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Rect
import io.github.xiaomibutton.remapper.backscreen.core.*
import kotlinx.coroutines.*

internal suspend fun openRearApp(a: Activity, s: DashboardStore, pkg: String) {
    check(!PhoneLock.isLocked(a)) { "Unlock the phone to open an app" }
    require(pkg in s.strings("apps"))
    val keeper = KeeperService.instance ?: error("Back screen service is unavailable")
    val display = a.display ?: error("Rear display unavailable")
    val rear = display.displayId
    check(rear > 0 && keeper.rearDisplayId() == rear)
    val intent = a.packageManager.getLaunchIntentForPackage(pkg) ?: error("App no longer installed")
    val lend = Lend(LendReason.QUICK_SWITCH, -1, pkg, Lend.newId())
    check(keeper.onLent(lend)) { "Another app owns the back screen" }
    try {
        val dpi = s.value("dpi.$pkg").toIntOrNull()?.coerceIn(120, 480)
        if (dpi != null)
            withContext(Dispatchers.IO) {
                RearTweaks.set(a, TweakKind.DENSITY, rear) { _, _ -> dpi.toString() }
            }
        if (s.flag("rearIme", true) && !s.flag("compactKeyboard"))
            runCatching {
                withDashboardHelper(a) { helper ->
                    val old = helper.imePolicy(rear, -1)
                    if (old != 0) {
                        val prefs = a.getSharedPreferences("portal_ime", 0)
                        check(
                            prefs.edit().putInt("display", rear).putInt("original", old).commit(),
                        )
                        check(helper.imePolicy(rear, 0) == 0) {
                            "This display refused its keyboard"
                        }
                    }
                }
            }
                .onFailure {
                    android.widget.Toast.makeText(
                            a,
                            "System keyboard routing unavailable. Compact rear keyboard is available in Launcher settings.",
                            android.widget.Toast.LENGTH_LONG,
                        )
                        .show()
                }
        check(!PhoneLock.isLocked(a) && RearState.lendFor(a, LendReason.QUICK_SWITCH) == lend)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(rear)
        val bounds = a.windowManager.currentWindowMetrics.bounds
        val left =
            when (s.value("width.$pkg", "Full")) {
                "Camera safe" -> BackScreen.cameraInsets(display).left
                "70%" -> (bounds.width() * .30).toInt()
                "85%" -> (bounds.width() * .15).toInt()
                else -> 0
            }
        if (left > 0)
            options.launchBounds = Rect(bounds.left + left, bounds.top, bounds.right, bounds.bottom)
        val component = checkNotNull(intent.component)
        val started = runCatching {
            a.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options.toBundle())
        }
            .isSuccess
        if (!started) {
            val result =
                withContext(Dispatchers.IO) { RearCommands.launchPinned(component, rear, left > 0) }
            check(result == "ok") { result }
        }
        keeper.lightForLend(0L)
        if (left > 0)
            keeper.shell(onUnavailable = {}) {
                val id =
                    RearCommands.taskList()?.let {
                        TaskList.tasksOf(it, rear, pkg).firstOrNull()?.id
                    }
                if (id != null) {
                    val result =
                        RearCommands.resizePinned(
                            id,
                            Rect(left, 0, bounds.width(), bounds.height()),
                        )
                    if (result != "ok")
                        BackScreen.log("The app did not accept its requested rear width")
                }
            }
    } catch (e: Exception) {
        keeper.onLendEnded(lend)
        throw e
    }
}

internal fun restoreRearIme(context: android.content.Context) {
    val prefs = context.getSharedPreferences("portal_ime", 0)
    val rear = prefs.getInt("display", -1)
    if (rear < 1 || RearState.lendFor(context, LendReason.QUICK_SWITCH) != null) return
    val original = prefs.getInt("original", 1)
    CoroutineScope(Dispatchers.Main.immediate).launch {
        runCatching {
            withDashboardHelper(context) { helper ->
                if (RearState.lendFor(context, LendReason.QUICK_SWITCH) == null &&
                    prefs.getInt("display", -1) == rear) {
                    if (helper.imePolicy(rear, -1) == 0)
                        check(helper.imePolicy(rear, original) == original)
                    prefs.edit().clear().apply()
                }
            }
        }
    }
}
