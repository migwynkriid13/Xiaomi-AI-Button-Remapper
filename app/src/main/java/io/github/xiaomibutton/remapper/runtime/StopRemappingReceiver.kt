package io.github.xiaomibutton.remapper.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Receives the private Stop action from the status notification. */
class StopRemappingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        RemapperRuntime.stop(context, "Remapping stopped from the notification.")
    }
}
