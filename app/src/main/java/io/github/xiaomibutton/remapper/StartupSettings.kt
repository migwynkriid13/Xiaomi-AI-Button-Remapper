package io.github.xiaomibutton.remapper

import android.content.Context
import android.content.Intent
import io.github.xiaomibutton.remapper.runtime.StartupRecoveryService

/** User choice for restoring enabled features without first opening the app after a reboot. */
object StartupSettings {
    private const val FILE = "startup"
    private const val KEY_START_ON_BOOT = "start_on_boot"

    fun startOnBoot(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_START_ON_BOOT, false)

    fun setStartOnBoot(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_START_ON_BOOT, enabled).apply()
        if (!enabled) context.applicationContext.stopService(Intent(context, StartupRecoveryService::class.java))
    }

    fun reset(context: Context) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().apply()
        context.applicationContext.stopService(Intent(context, StartupRecoveryService::class.java))
    }
}
