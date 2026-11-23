package io.github.xiaomibutton.remapper.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import io.github.xiaomibutton.remapper.AppLog
import io.github.xiaomibutton.remapper.ConfigStore
import io.github.xiaomibutton.remapper.LogArea
import io.github.xiaomibutton.remapper.R
import io.github.xiaomibutton.remapper.StartupSettings
import io.github.xiaomibutton.remapper.backscreen.resumeRearScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/**
 * Keeps the process alive after an opted-in reboot until Shizuku arrives, then hands each saved
 * feature back to its normal foreground service. Opening the app uses the same restore path without
 * needing this service.
 */
class StartupRecoveryService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var restoreInFlight = false

    private val binderListener = Shizuku.OnBinderReceivedListener {
        scope.launch { restoreIfReady() }
    }

    override fun onCreate() {
        super.onCreate()
        foreground("Waiting for Shizuku to restore enabled features")
        RemapperRuntime.initialize(this)
        Shizuku.addBinderReceivedListenerSticky(binderListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!StartupSettings.startOnBoot(this)) {
            finish()
            return START_NOT_STICKY
        }
        resumeRearScreen(this)
        scope.launch { restoreIfReady() }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun restoreIfReady() {
        if (restoreInFlight) return
        restoreInFlight = true
        try {
            val wantsRemap = ConfigStore(this).config.first().enabled
            if (!wantsRemap) {
                AppLog.add(LogArea.APP, "Back screen startup restored")
                finish()
                return
            }
            RemapperRuntime.refresh(this)
            if (!RemapperRuntime.status.value.shizukuAuthorized) {
                foreground("Waiting for Shizuku · open Shizuku to finish startup")
                return
            }
            AppLog.add(LogArea.BUTTON, "Shizuku ready · restoring button remapping")
            RemapperRuntime.start(this)
            finish()
        } finally {
            restoreInFlight = false
        }
    }

    private fun foreground(detail: String) {
        val manager = checkNotNull(getSystemService(NotificationManager::class.java))
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Automatic startup", NotificationManager.IMPORTANCE_LOW))
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 8, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_button_mono)
            .setContentTitle("Xiaomi AI Button Remapper")
            .setContentText(detail)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID, notification)
    }

    private fun finish() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderListener)
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "automatic_startup"
        const val ID = 691
    }
}
