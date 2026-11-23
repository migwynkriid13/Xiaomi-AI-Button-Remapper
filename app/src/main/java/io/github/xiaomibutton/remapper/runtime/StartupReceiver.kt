package io.github.xiaomibutton.remapper.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.xiaomibutton.remapper.AppLog
import io.github.xiaomibutton.remapper.LogArea
import io.github.xiaomibutton.remapper.StartupSettings

/** Starts the opt-in recovery service after Android finishes booting. */
class StartupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED || !StartupSettings.startOnBoot(context)) return
        AppLog.add(LogArea.APP, "Phone restarted · restoring enabled features")
        runCatching {
            context.startForegroundService(Intent(context, StartupRecoveryService::class.java))
        }.onFailure { error ->
            AppLog.add(LogArea.APP, "Android blocked startup recovery (${error.javaClass.simpleName})")
        }
    }
}
