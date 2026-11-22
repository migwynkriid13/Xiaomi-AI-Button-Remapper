package io.github.xiaomibutton.remapper.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Receives the private Stop action from the status notification. */
class StopRemappingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == STOP_TEST) RemapperRuntime.endTest()
        else RemapperRuntime.stop(context, "Remapping stopped from the notification.")
    }

    companion object {
        internal const val STOP_TEST = "io.github.xiaomibutton.remapper.STOP_TEST"
    }
}
