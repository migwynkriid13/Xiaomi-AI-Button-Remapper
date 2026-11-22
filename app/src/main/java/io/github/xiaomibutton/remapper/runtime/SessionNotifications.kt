package io.github.xiaomibutton.remapper.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

internal class SessionNotifications(private val context: Context) {
    private val manager = checkNotNull(context.getSystemService(NotificationManager::class.java))

    fun createChannels() {
        manager.createNotificationChannels(listOf(
            NotificationChannel(SESSION, "Button remapping", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(ACTIONS, "Button actions and reminders", NotificationManager.IMPORTANCE_DEFAULT),
        ))
    }

    fun sessionVisible(): Boolean = manager.areNotificationsEnabled() &&
        manager.getNotificationChannel(SESSION)?.importance != NotificationManager.IMPORTANCE_NONE

    fun session(detail: String): Notification {
        val stop = Intent(context, RemapService::class.java).setAction(RemapService.STOP)
        return Notification.Builder(context, SESSION)
            .setSmallIcon(android.R.drawable.ic_menu_preferences)
            .setContentTitle("Xiaomi AI Button Remapper")
            .setContentText(detail)
            .setContentIntent(openApp())
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop now", PendingIntent.getService(context, 2, stop, FLAGS)).build())
            .build()
    }

    fun offer(label: String, pending: PendingIntent): String {
        check(manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(ACTIONS)?.importance != NotificationManager.IMPORTANCE_NONE) {
            "Enable button action notifications to open this action from the background."
        }
        manager.notify(ACTION_ID, Notification.Builder(context, ACTIONS)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(label)
            .setContentText("Tap to open")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build())
        return "Tap the notification to open $label"
    }

    fun clearOffer() = manager.cancel(ACTION_ID)

    fun resumeAfterReboot() {
        if (!manager.areNotificationsEnabled()) return
        manager.notify(REMINDER_ID, Notification.Builder(context, ACTIONS)
            .setSmallIcon(android.R.drawable.ic_menu_preferences)
            .setContentTitle("Resume AI button remapping")
            .setContentText("Open the remapper to check your input service and resume.")
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build())
    }

    private fun openApp(): PendingIntent? = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
        PendingIntent.getActivity(context, 1, it, FLAGS)
    }

    companion object {
        const val SESSION_ID = 689
        const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        private const val ACTION_ID = 690
        private const val REMINDER_ID = 691
        private const val SESSION = "remap_session"
        private const val ACTIONS = "remap_actions"
    }
}
