package io.github.xiaomibutton.remapper

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.service.notification.Condition
import android.service.notification.NotificationListenerService
import android.view.KeyEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Android binds this optional service only after the user grants notification access. */
class MediaSessionListener : NotificationListenerService() {
    override fun onListenerConnected() { super.onListenerConnected(); current = this }
    override fun onListenerDisconnected() { if (current === this) current = null; super.onListenerDisconnected() }
    override fun onDestroy() { if (current === this) current = null; super.onDestroy() }

    companion object {
        @Volatile internal var current: MediaSessionListener? = null
            private set
    }
}

internal object MediaActions {
    private const val ACCESS = "Notification access"
    private const val POLICY = "Do Not Disturb access"
    private const val RINGER = "Ringer and Do Not Disturb"
    private const val DURATION = "Seconds (1–3600)"

    private data class Transport(val id: String, val label: String, val code: Int)
    private val controls = listOf(
        Transport("toggle", "Play or pause", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
        Transport("play", "Play", KeyEvent.KEYCODE_MEDIA_PLAY),
        Transport("pause", "Pause", KeyEvent.KEYCODE_MEDIA_PAUSE),
        Transport("next", "Next track", KeyEvent.KEYCODE_MEDIA_NEXT),
        Transport("previous", "Previous track", KeyEvent.KEYCODE_MEDIA_PREVIOUS),
        Transport("forward", "Fast forward", KeyEvent.KEYCODE_MEDIA_FAST_FORWARD),
        Transport("rewind", "Rewind", KeyEvent.KEYCODE_MEDIA_REWIND),
        Transport("stop", "Stop playback", KeyEvent.KEYCODE_MEDIA_STOP),
    )

    private data class Stream(val id: String, val label: String, val type: Int?)
    private val streams = listOf(
        Stream("auto", "Current stream", null), Stream("music", "Media", AudioManager.STREAM_MUSIC),
        Stream("ring", "Ring", AudioManager.STREAM_RING), Stream("notification", "Notification", AudioManager.STREAM_NOTIFICATION),
        Stream("alarm", "Alarm", AudioManager.STREAM_ALARM), Stream("system", "System", AudioManager.STREAM_SYSTEM),
        Stream("call", "Call", AudioManager.STREAM_VOICE_CALL), Stream("dtmf", "Dial tones", AudioManager.STREAM_DTMF),
        Stream("accessibility", "Accessibility", AudioManager.STREAM_ACCESSIBILITY),
    )
    private val adjustments = mapOf("up" to AudioManager.ADJUST_RAISE, "down" to AudioManager.ADJUST_LOWER,
        "increase_stream" to AudioManager.ADJUST_RAISE, "decrease_stream" to AudioManager.ADJUST_LOWER,
        "mute" to AudioManager.ADJUST_MUTE, "unmute" to AudioManager.ADJUST_UNMUTE, "toggle" to AudioManager.ADJUST_TOGGLE_MUTE)
    private val volumeOptions = streams.flatMap { listOf(it.id to "${it.label} · show panel", "${it.id}.quiet" to "${it.label} · hide panel") }
    private val modes = mapOf("normal" to AudioManager.RINGER_MODE_NORMAL, "vibrate" to AudioManager.RINGER_MODE_VIBRATE,
        "silent" to AudioManager.RINGER_MODE_SILENT)
    private val dnd = mapOf("priority" to NotificationManager.INTERRUPTION_FILTER_PRIORITY,
        "alarms" to NotificationManager.INTERRUPTION_FILTER_ALARMS, "none" to NotificationManager.INTERRUPTION_FILTER_NONE)
    private val dndChoices = listOf("priority" to "Priority only", "alarms" to "Alarms only", "none" to "Total silence")

    val actions = buildList {
        for (c in controls) {
            add(CatalogAction("media.${c.id}", c.label, "Media"))
            add(CatalogAction("media.app.${c.id}", "${c.label} in an app", "Media", ActionParameter.APP, ACCESS))
        }
        for ((key, label) in listOf("step_forward" to "Step forward", "step_back" to "Step backward")) {
            add(CatalogAction("media.$key", label, "Media", ActionParameter.NUMBER, ACCESS, hint = DURATION))
            add(CatalogAction("media.app.$key", "$label in an app", "Media", ActionParameter.APP_NUMBER, ACCESS, hint = DURATION))
        }
        add(CatalogAction("media.sound", "Play audio file", "Media", ActionParameter.AUDIO))
        add(CatalogAction("media.ringtone", "Play a ringtone", "Media", ActionParameter.RINGTONE))
        add(CatalogAction("media.stop_sound", "Stop remapper sound", "Media"))
        add(CatalogAction("media.notification_all", "Dismiss clearable notifications", "Notifications", requirement = ACCESS))
        add(CatalogAction("media.notification_latest", "Dismiss latest clearable notification", "Notifications", requirement = ACCESS))
        for ((id, title) in listOf("up" to "Volume up", "down" to "Volume down", "mute" to "Mute volume",
            "unmute" to "Unmute volume", "toggle" to "Toggle volume mute")) {
            add(CatalogAction("volume.$id", title, "Volume", ActionParameter.CHOICE, choices = volumeOptions))
        }
        val namedStreams = volumeOptions.filterNot { it.first.substringBefore('.') == "auto" }
        for ((id, title) in listOf("increase_stream" to "Increase volume stream", "decrease_stream" to "Decrease volume stream")) {
            add(CatalogAction("volume.$id", title, "Volume", ActionParameter.CHOICE, choices = namedStreams))
        }
        add(CatalogAction("volume.panel", "Show volume panel", "Volume"))
        add(CatalogAction("volume.mic_mute", "Mute microphone", "Volume"))
        add(CatalogAction("volume.mic_unmute", "Unmute microphone", "Volume"))
        add(CatalogAction("volume.mic_toggle", "Toggle microphone mute", "Volume"))
        add(CatalogAction("ringer.set", "Set ringer mode", RINGER, ActionParameter.CHOICE, POLICY,
            listOf("normal" to "Normal", "vibrate" to "Vibrate", "silent" to "Silent")))
        add(CatalogAction("ringer.cycle", "Cycle normal, vibrate and silent", RINGER, requirement = POLICY))
        add(CatalogAction("ringer.vibrate_ring", "Switch between vibrate and ring", RINGER))
        add(CatalogAction("ringer.dnd_toggle", "Toggle Do Not Disturb mode", RINGER, ActionParameter.CHOICE, POLICY, dndChoices))
        add(CatalogAction("ringer.dnd_enable", "Enable Do Not Disturb mode", RINGER, ActionParameter.CHOICE, POLICY, dndChoices))
        add(CatalogAction("ringer.dnd_disable", "Disable remapper Do Not Disturb", RINGER, requirement = POLICY))
    }

    /** Package and document restrictions are checked by the shared import/save validator. */
    fun validate(id: String, value: String) {
        if (id.endsWith(".step_forward") || id.endsWith(".step_back")) {
            val seconds = value.substringAfterLast('|').toIntOrNull()
            require(seconds != null && seconds in 1..3600) { DURATION }
        }
    }

    suspend fun execute(context: Context, id: String, value: String): String {
        validate(id, value)
        return when {
            id == "media.notification_all" || id == "media.notification_latest" -> dismiss(context, id.endsWith("_all"))
            id == "media.sound" || id == "media.ringtone" -> withContext(Dispatchers.IO) {
                playSound(context.applicationContext, Uri.parse(value), id == "media.ringtone")
            }
            id == "media.stop_sound" -> { stopSound(); "Remapper sound stopped" }
            id.startsWith("media.") -> transport(context, id, value)
            id.startsWith("volume.") -> volume(context, id.removePrefix("volume."), value)
            id.startsWith("ringer.dnd_") -> doNotDisturb(context, id.removePrefix("ringer.dnd_"), value)
            id.startsWith("ringer.") -> ringer(context, id.removePrefix("ringer."), value)
            else -> error("Unknown audio action")
        }
    }

    private fun listenerComponent(context: Context) = ComponentName(context, MediaSessionListener::class.java)
    private fun requireAccess(context: Context) {
        check(checkNotNull(context.getSystemService(NotificationManager::class.java))
            .isNotificationListenerAccessGranted(listenerComponent(context))) { "Enable Notification access in Settings" }
    }
    private fun dismiss(context: Context, all: Boolean): String {
        requireAccess(context)
        val listener = checkNotNull(MediaSessionListener.current) { "Waiting for Android to connect the notification listener" }
        val visible = listener.activeNotifications.orEmpty().filter { it.isClearable }
        if (visible.isEmpty()) return "No clearable notifications"
        val keys = if (all) visible else listOf(visible.maxBy { it.postTime })
        keys.forEach { listener.cancelNotification(it.key) }
        return "Requested dismissal of ${keys.size} notification(s)"
    }

    @SuppressLint("MissingPermission") // Enabled notification listener is the documented alternative.
    private fun controller(context: Context, pkg: String?): MediaController {
        requireAccess(context)
        val sessions = checkNotNull(context.getSystemService(MediaSessionManager::class.java)).getActiveSessions(listenerComponent(context))
        return checkNotNull(sessions.firstOrNull { pkg == null || it.packageName == pkg }) {
            if (pkg == null) "No active media session" else "Open the selected app and start a media session"
        }
    }

    private fun transport(context: Context, id: String, value: String): String {
        val targeted = id.startsWith("media.app.")
        val op = id.substringAfterLast('.')
        val pkg = if (targeted) value.substringBefore('|') else null
        if (op == "step_forward" || op == "step_back") {
            val player = controller(context, pkg)
            val state = checkNotNull(player.playbackState) { "The player did not report playback state" }
            check(state.actions and PlaybackState.ACTION_SEEK_TO != 0L && state.position >= 0) {
                "This media session does not support seeking by time"
            }
            val total = player.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            val elapsed = if (state.state == PlaybackState.STATE_PLAYING && state.lastPositionUpdateTime > 0) {
                ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime).coerceAtLeast(0) * state.playbackSpeed).toLong()
            } else 0L
            val seconds = value.substringAfterLast('|').toInt() * (if (op == "step_forward") 1 else -1)
            player.transportControls.seekTo(seekPosition(state.position, elapsed, seconds, total))
            return "Seek requested from ${player.packageName}"
        }
        val command = checkNotNull(controls.find { it.id == op }) { "Unknown media control" }
        val time = SystemClock.uptimeMillis()
        val down = KeyEvent(time, time, KeyEvent.ACTION_DOWN, command.code, 0)
        val up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP)
        if (pkg != null) {
            val player = controller(context, pkg)
            val handled = player.dispatchMediaButtonEvent(down)
            val released = player.dispatchMediaButtonEvent(up)
            check(handled && released) { "Selected media session declined ${command.label.lowercase()}" }
        } else {
            val audio = checkNotNull(context.getSystemService(AudioManager::class.java))
            audio.dispatchMediaKeyEvent(down)
            audio.dispatchMediaKeyEvent(up)
        }
        return "${command.label} requested"
    }

    internal fun seekPosition(position: Long, elapsed: Long, seconds: Int, duration: Long): Long {
        val limit = if (duration > 0) duration else Long.MAX_VALUE
        val start = position.coerceIn(0L, limit)
        val current = start + elapsed.coerceIn(-start, limit - start)
        return current + (seconds * 1000L).coerceIn(-current, limit - current)
    }

    @Suppress("DEPRECATION")
    private fun volume(context: Context, op: String, value: String): String {
        val audio = checkNotNull(context.getSystemService(AudioManager::class.java))
        if (op.startsWith("mic_")) {
            val muted = when (op) { "mic_mute" -> true; "mic_unmute" -> false; "mic_toggle" -> !audio.isMicrophoneMute; else -> error("Unknown microphone action") }
            audio.isMicrophoneMute = muted
            check(audio.isMicrophoneMute == muted) { "Android or the microphone privacy switch kept its current setting" }
            return if (muted) "Microphone muted" else "Microphone unmuted"
        }
        if (op == "panel") { audio.adjustVolume(AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI); return "Volume panel requested" }
        check(!audio.isVolumeFixed) { "Device uses fixed volume" }
        val direction = checkNotNull(adjustments[op])
        val stream = streams.first { it.id == value.substringBefore('.') }
        val flags = if (value.endsWith(".quiet")) 0 else AudioManager.FLAG_SHOW_UI
        try {
            if (stream.type == null) audio.adjustVolume(direction, flags) else audio.adjustStreamVolume(stream.type, direction, flags)
        } catch (e: SecurityException) {
            throw IllegalStateException("Android restricted this volume change. Check Do Not Disturb access and the selected stream.", e)
        }
        return "${stream.label} adjustment requested"
    }

    private fun ringer(context: Context, op: String, value: String): String {
        val audio = checkNotNull(context.getSystemService(AudioManager::class.java))
        val next = when (op) {
            "set" -> checkNotNull(modes[value])
            "cycle" -> when (audio.ringerMode) {
                AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
                AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
                else -> AudioManager.RINGER_MODE_NORMAL
            }
            "vibrate_ring" -> if (audio.ringerMode == AudioManager.RINGER_MODE_VIBRATE) AudioManager.RINGER_MODE_NORMAL else AudioManager.RINGER_MODE_VIBRATE
            else -> error("Unknown ringer mode")
        }
        try { audio.ringerMode = next } catch (e: SecurityException) {
            throw IllegalStateException("Android requires Do Not Disturb access to change this ringer mode", e)
        }
        check(audio.ringerMode == next) { "Android kept the current ringer mode" }
        return "Ringer: ${modes.entries.first { it.value == next }.key}"
    }

    private fun doNotDisturb(context: Context, op: String, value: String): String {
        val manager = checkNotNull(context.getSystemService(NotificationManager::class.java))
        check(manager.isNotificationPolicyAccessGranted) { "Grant Do Not Disturb access in Settings" }
        val on = if (Build.VERSION.SDK_INT >= 35) manager.automaticZenRules.keys.any {
            manager.getAutomaticZenRuleState(it) == Condition.STATE_TRUE
        } else manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        val enable = when (op) { "enable" -> true; "disable" -> false; "toggle" -> !on; else -> error("Unknown Do Not Disturb action") }
        manager.setInterruptionFilter(if (enable) checkNotNull(dnd[value]) else NotificationManager.INTERRUPTION_FILTER_ALL)
        return if (enable) "Remapper Do Not Disturb enabled" else "Remapper Do Not Disturb disabled"
    }

    private var sound: MediaPlayer? = null
    @Synchronized private fun stopSound() {
        sound?.release()
        sound = null
    }

    @Synchronized private fun playSound(context: Context, uri: Uri, ringtone: Boolean): String {
        val next = MediaPlayer()
        try {
            next.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(if (ringtone) AudioAttributes.USAGE_NOTIFICATION_RINGTONE else AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            next.setDataSource(context, uri)
            next.prepare()
            next.setOnCompletionListener { finished -> synchronized(this) { if (sound === finished) stopSound() } }
            next.setOnErrorListener { failed, _, _ -> synchronized(this) { if (sound === failed) stopSound() }; true }
            stopSound()
            sound = next
            next.start()
        } catch (e: Exception) {
            if (sound === next) sound = null
            next.release()
            throw IllegalStateException("Cannot play this audio. Choose the file or ringtone again.", e)
        }
        return "Sound playing"
    }
}
