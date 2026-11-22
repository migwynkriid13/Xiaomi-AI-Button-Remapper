package io.github.xiaomibutton.remapper.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.xiaomibutton.remapper.ConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (ConfigStore(context).config.first().enabled) {
                    SessionNotifications(context).apply { createChannels(); resumeAfterReboot() }
                }
            } finally { pending.finish() }
        }
    }
}
