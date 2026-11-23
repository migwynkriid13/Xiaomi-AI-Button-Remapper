package io.github.xiaomibutton.remapper.backscreen.dashboard

import android.app.Notification
import android.app.NotificationManager
import android.os.Process
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.StatusBarNotification
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import io.github.xiaomibutton.remapper.MediaSessionListener
import io.github.xiaomibutton.remapper.backscreen.core.PhoneLock
import kotlin.math.abs

private val locallyDismissed = androidx.compose.runtime.mutableStateListOf<String>()

internal fun forgetDismissal(key: String) {
    locallyDismissed.remove(key)
}

internal fun notices(s: DashboardStore): List<StatusBarNotification> {
    val listener = MediaSessionListener.current ?: return emptyList()
    return runCatching {
            listener.activeNotifications
                .filter { permitted(listener, it) }
                .sortedByDescending { it.postTime }
        }
        .getOrDefault(emptyList())
}

internal fun permitted(listener: MediaSessionListener, n: StatusBarNotification): Boolean {
    val r = Ranking()
    return n.packageName != listener.packageName &&
        n.user == Process.myUserHandle() &&
        n.notification.visibility != Notification.VISIBILITY_SECRET &&
        listener.currentRanking.getRanking(n.key, r) &&
        !r.isSuspended &&
        r.matchesInterruptionFilter() &&
        r.suppressedVisualEffects and
            NotificationManager.Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST == 0 &&
        n.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0
}

private fun group(n: Notification): String? =
    when {
        n.category in setOf(Notification.CATEGORY_CALL, Notification.CATEGORY_ALARM) ->
            "Calls and alarms"
        n.category == Notification.CATEGORY_NAVIGATION || n.category == "location_sharing" ->
            "Navigation and rides"
        n.category == Notification.CATEGORY_TRANSPORT -> null
        n.category in setOf(Notification.CATEGORY_PROGRESS, Notification.CATEGORY_STOPWATCH) ||
            n.extras.containsKey(Notification.EXTRA_PROGRESS_MAX) ||
            n.flags and Notification.FLAG_ONGOING_EVENT != 0 -> "Timers and downloads"
        else -> null
    }

@Composable
internal fun LivePage(
    s: DashboardStore,
    list: List<StatusBarNotification>,
    live: Boolean,
    now: Long,
    message: (String) -> Unit,
) {
    val listener = MediaSessionListener.current
    if (listener == null) {
        Text("Allow the media and notification listener in the front editor.")
        return
    }
    val hidden = locallyDismissed
    val rows = list.filter {
        val kind =
            if (journey(it)) "Navigation and rides"
            else if (liveUpdate(it.notification)) "Timers and downloads" else group(it.notification)
        (if (live) kind != null && s.flag(kind, true) else kind == null) && it.key !in hidden
    }
    LaunchedEffect(list.map { it.key }) { hidden.retainAll(list.map { it.key }.toSet()) }
    fun dismiss(n: StatusBarNotification) {
        if (PhoneLock.isLocked(s.context) || !n.isClearable) return
        if (s.flag("Sync dismissals", true))
            runCatching { listener.cancelNotification(n.key) }
                .onFailure { message("Could not dismiss") }
        else hidden.add(n.key)
    }
    if (rows.isEmpty())
        Text(
            if (live) "Your next route, call, timer or delivery will appear here."
            else "You’re caught up.",
        )
    if (!live && rows.any { it.isClearable })
        TextButton({ rows.filter { it.isClearable }.forEach(::dismiss) }) { Text("Clear all") }
    for (n in rows.take(24)) key(n.key) {
        val note = n.notification
        val extras = note.extras
        val title =
            extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                ?: appLabel(s, n.packageName)
        var travel by remember { mutableFloatStateOf(0f) }
        Surface(
            shape = io.github.xiaomibutton.remapper.TileShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier =
                Modifier.fillMaxWidth().pointerInput(n.key) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (abs(travel) > size.width * .35f) dismiss(n)
                            travel = 0f
                        },
                        onDragCancel = { travel = 0f },
                    ) { _, dx ->
                        travel += dx
                    }
                },
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                        ?: extras.getCharSequence(Notification.EXTRA_TEXT))
                    ?.let { Text(it.toString(), maxLines = 5) }
                if (live) JourneyDetails(s, n)
                val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX)
                if (max > 0)
                    LinearProgressIndicator(
                        progress = {
                            extras
                                .getInt(Notification.EXTRA_PROGRESS)
                                .toFloat()
                                .div(max)
                                .coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                if (extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) && note.`when` > 0L) {
                    val sec = abs(now - note.`when`) / 1000
                    Text(
                        "%d:%02d".format(sec / 60, sec % 60),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                note.actions
                    .orEmpty()
                    .filter { it.remoteInputs.isNullOrEmpty() }
                    .take(3)
                    .forEach { action ->
                        val send = {
                            if (!PhoneLock.isLocked(s.context))
                                runCatching { action.actionIntent.send() }
                                    .onFailure { message("This action expired") }
                            Unit
                        }
                        if (
                            note.category in
                                setOf(Notification.CATEGORY_CALL, Notification.CATEGORY_ALARM)
                        )
                            ConfirmAction(action.title.toString(), n.key, send)
                        else TextButton(send) { Text(action.title.toString()) }
                    }
                if (note.category == Notification.CATEGORY_CALL) CallAudio(s.context, message)
                if (!live && n.isClearable) TextButton({ dismiss(n) }) { Text("Dismiss") }
            }
        }
    }
}
