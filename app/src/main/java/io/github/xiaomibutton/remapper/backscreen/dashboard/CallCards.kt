package io.github.xiaomibutton.remapper.backscreen.dashboard

import android.app.Notification
import android.content.Context
import android.content.pm.ApplicationInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.telecom.TelecomManager
import androidx.compose.material3.*
import androidx.compose.runtime.*
import io.github.xiaomibutton.remapper.backscreen.core.PhoneLock

@Composable
internal fun ConfirmAction(label: String, key: String, run: () -> Unit) {
    var progress by remember(key, label) { mutableFloatStateOf(0f) }
    Text(label, style = MaterialTheme.typography.labelLarge)
    Slider(
        progress,
        { progress = it },
        onValueChangeFinished = {
            if (progress > .88f) run()
            progress = 0f
        },
    )
}

@Composable
internal fun LockedCallCards(s: DashboardStore, now: Long, unlock: () -> Unit) {
    val c = s.context
    val current =
        remember(now / 1500) {
            notices(s).filter { n ->
                val dialer = c.getSystemService(TelecomManager::class.java).defaultDialerPackage
                val trustedClock =
                    n.notification.category == Notification.CATEGORY_ALARM &&
                        runCatching {
                                c.packageManager.getApplicationInfo(n.packageName, 0).flags and
                                    ApplicationInfo.FLAG_SYSTEM != 0
                            }
                            .getOrDefault(false)
                (n.packageName == dialer &&
                    n.notification.category == Notification.CATEGORY_CALL) || trustedClock
            }
        }
    for (n in current) {
        Text(
            if (n.notification.category == Notification.CATEGORY_ALARM) "Alarm" else "Phone call",
            style = MaterialTheme.typography.titleMedium,
        )
        for (action in
            n.notification.actions.orEmpty().filter { it.remoteInputs.isNullOrEmpty() }.take(3)) {
            ConfirmAction(action.title.toString(), n.key) {
                if (android.os.Build.VERSION.SDK_INT >= 31 && action.isAuthenticationRequired)
                    unlock()
                else runCatching { action.actionIntent.send() }
            }
        }
    }
    TextButton(unlock) { Text("Unlock for more details") }
}

@Composable
internal fun CallAudio(c: Context, message: (String) -> Unit) {
    if (android.os.Build.VERSION.SDK_INT < 31) return
    val audio = c.getSystemService(AudioManager::class.java)
    val speaker =
        audio.availableCommunicationDevices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } ?: return
    DashToggle("Speaker", audio.communicationDevice?.id == speaker.id) { on ->
        if (!PhoneLock.isLocked(c))
            runCatching {
                if (on) check(audio.setCommunicationDevice(speaker))
                else audio.clearCommunicationDevice()
            }
                .onFailure { message("The dialer controls audio for this call") }
    }
}
