package io.github.xiaomibutton.remapper.backscreen.dashboard

import android.Manifest
import android.app.AlarmManager
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.provider.CalendarContract
import android.text.format.DateFormat
import java.util.Calendar

internal fun battery(c: Context) =
    c.getSystemService(BatteryManager::class.java)
        .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

internal fun headphones(c: Context) =
    c.getSystemService(AudioManager::class.java)
        .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .firstOrNull {
            it.type in
                setOf(
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                )
        }

internal fun activeProfile(s: DashboardStore, locked: Boolean): String? {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return listOf("Night", "Headphones", "Desk").firstOrNull { name ->
        s.flag("profile.$name") &&
            when (
                s.value(
                    "trigger.$name",
                    when (name) {
                        "Night" -> "Night hours"
                        "Desk" -> "Charging"
                        else -> "Headphones"
                    },
                )
            ) {
                "Night hours" -> hour >= 22 || hour < 7
                "Charging" -> s.context.getSystemService(BatteryManager::class.java).isCharging
                "Locked" -> locked
                "Media playing" ->
                    s.context.getSystemService(AudioManager::class.java).isMusicActive
                else -> headphones(s.context) != null
            }
    }
}

internal fun nextAlarm(c: Context): String {
    val time =
        c.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime
            ?: return "No alarm set"
    return DateFormat.format(if (DateFormat.is24HourFormat(c)) "EEE HH:mm" else "EEE h:mm a", time)
        .toString()
}

internal fun nextEvent(s: DashboardStore): String {
    val c = s.context
    if (
        c.checkSelfPermission(Manifest.permission.READ_CALENDAR) !=
            PackageManager.PERMISSION_GRANTED
    )
        return "Calendar access is off"
    val now = System.currentTimeMillis()
    val uri =
        CalendarContract.Instances.CONTENT_URI.buildUpon()
            .apply {
                ContentUris.appendId(this, now)
                ContentUris.appendId(this, now + 7 * 86400000L)
            }
            .build()
    return c.contentResolver
        .query(
            uri,
            arrayOf("title", "begin"),
            "visible=1 AND end>?",
            arrayOf(now.toString()),
            "begin ASC",
        )
        ?.use {
            if (!it.moveToFirst()) "No events this week"
            else {
                val minutes = (it.getLong(1) - now) / 60000
                val title =
                    if (s.flag("eventTitles", true)) it.getString(0) ?: "Event"
                    else "Calendar event"
                title + if (minutes <= 0) " · now" else " · in $minutes min"
            }
        } ?: "Calendar unavailable"
}
