package io.github.xiaomibutton.remapper.runtime

import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.service.notification.Condition
import io.github.xiaomibutton.remapper.validateAction
import io.github.xiaomibutton.remapper.ActionKind
import io.github.xiaomibutton.remapper.ActionSpec
import io.github.xiaomibutton.remapper.ActionLibrary
import io.github.xiaomibutton.remapper.DeviceActions
import io.github.xiaomibutton.remapper.InteractionActions
import io.github.xiaomibutton.remapper.KeyMapInterop
import io.github.xiaomibutton.remapper.MediaActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class ActionDispatcher(
    private val context: Context,
    private val notifications: SessionNotifications,
    private val helper: IInputHelper,
) : AutoCloseable {
    private val camera = context.getSystemService(CameraManager::class.java)
    private val torchStates = mutableMapOf<String, Boolean>()
    private val torchObserver = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { torchStates[cameraId] = enabled }
        override fun onTorchModeUnavailable(cameraId: String) { torchStates.remove(cameraId) }
    }
    private val listening = runCatching {
        camera?.registerTorchCallback(torchObserver, Handler(Looper.getMainLooper()))
    }.isSuccess

    override fun close() {
        if (listening) runCatching { camera?.unregisterTorchCallback(torchObserver) }
    }

    private val locked: Boolean get() = (context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false) ||
        (context.getSystemService(PowerManager::class.java)?.isInteractive != true)

    suspend fun perform(action: ActionSpec): String {
        validateAction(action)
        return when (action.kind) {
            ActionKind.NONE -> "Button action disabled"
            ActionKind.SEQUENCE -> error("Sequences must be expanded by the running session")
            ActionKind.CATALOG -> {
                val id = action.value
                check(!ActionLibrary.isRetired(id)) { "This older mapping requires privileges unavailable through Accessibility. Choose a new action." }
                val value = action.extra
                val entry = requireNotNull(ActionLibrary.find(id))
                when {
                    entry.requirement == KeyMapInterop.REQUIREMENT -> KeyMapInterop.trigger(context, value)
                    id.startsWith("flash.") -> flashlight(id, value)
                    id.startsWith("media.") || id.startsWith("volume.") || id.startsWith("ringer.") -> MediaActions.execute(context, id, value)
                    id.startsWith("keyboard.") || id.startsWith("input.") -> InteractionActions.execute(context, id, value)
                    id == "device.screenshot" -> {
                        check(!locked) { "Unlock the screen to take a screenshot." }
                        shellAction("Screenshot requested") { helper.deviceAction(id, value) }
                    }
                    id in ExpertDeviceActions.navigation -> shellAction(entry.title + " requested") { helper.deviceAction(id, value) }
                    else -> DeviceActions.execute(context, id, value) { intent, label -> launch(intent, label) }
                }
            }
            ActionKind.CAMERA -> {
                val secure = locked
                shellAction(if (secure) "Secure camera requested" else "Camera requested") { helper.openCamera(secure) }
            }
            ActionKind.APP -> {
                val intent = context.packageManager.getLaunchIntentForPackage(action.value)
                    ?: error("No launchable activity for this app or profile")
                if (locked) launch(intent, "Application")
                else shellAction("Application launch requested") { helper.openLauncherApp(action.value) }
            }
            ActionKind.FLASHLIGHT -> flashlight("flash.toggle", "")
            ActionKind.SILENT -> {
                val audio = checkNotNull(context.getSystemService(AudioManager::class.java))
                val silent = audio.ringerMode != AudioManager.RINGER_MODE_SILENT
                audio.ringerMode = if (silent) AudioManager.RINGER_MODE_SILENT else AudioManager.RINGER_MODE_NORMAL
                if (audio.ringerMode == AudioManager.RINGER_MODE_SILENT) "Ringer silent" else "Ringer normal"
            }
            ActionKind.DND -> {
                val nm = checkNotNull(context.getSystemService(NotificationManager::class.java))
                check(nm.isNotificationPolicyAccessGranted) { "Grant Do Not Disturb access in Android Settings" }
                // API 35+ gives each app its own rule. Another app's mode must not invert our toggle.
                val active = if (Build.VERSION.SDK_INT >= 35) nm.automaticZenRules.keys.any {
                    nm.getAutomaticZenRuleState(it) == Condition.STATE_TRUE
                } else nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
                nm.setInterruptionFilter(if (active) NotificationManager.INTERRUPTION_FILTER_ALL else NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                if (active) "Remapper DND off" else "Remapper DND on"
            }
            ActionKind.URL -> {
                val uri = Uri.parse(action.value).normalizeScheme()
                require(uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank()) { "Choose an HTTP or HTTPS URL" }
                val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
                if (locked) launch(intent, "Web link")
                else shellAction("Web link requested") { helper.openWeb(uri.toString()) }
            }
            ActionKind.SETTINGS -> {
                require(action.value.startsWith("android.settings.")) { "Invalid Settings action" }
                val intent = Intent(action.value)
                check(intent.resolveActivity(context.packageManager) != null) { "Settings destination unavailable on this device" }
                if (locked) launch(intent, "Android Settings")
                else shellAction("Settings page requested") { helper.openSettings(action.value) }
            }
            ActionKind.INTENT -> launch(userIntent(action.value), "Custom intent")
            ActionKind.SHORTCUT -> {
                if (action.extra.isBlank() || action.value.isBlank()) {
                    launch(userIntent(action.value.ifBlank { action.extra }), "App shortcut")
                } else {
                    val launcher = checkNotNull(context.getSystemService(LauncherApps::class.java))
                    check(launcher.hasShortcutHostPermission()) { "Android has not granted shortcut-host access" }
                    val user = Process.myUserHandle()
                    if (!locked && canLaunchActivity) {
                        launcher.startShortcut(action.value, action.extra, null, null, user)
                        "Shortcut launch requested"
                    } else if (Build.VERSION.SDK_INT >= 31) {
                        val pending = launcher.getShortcutIntent(action.value, action.extra, null, user)
                            ?: error("Shortcut is no longer available")
                        notifications.offer("App shortcut", pending)
                    } else "Open the remapper to launch this shortcut on this Android version"
                }
            }
            ActionKind.SHELL -> error("This older mapping runs a shell command. Choose a supported action instead.")
        }
    }

    private fun flashlight(operation: String, value: String): String {
        val flash = checkNotNull(camera) { "Camera service unavailable" }
        val id = torchStates.keys.firstOrNull {
            flash.getCameraCharacteristics(it)[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_BACK
        } ?: torchStates.keys.firstOrNull() ?: error("Flashlight unavailable or camera in use")
        if (operation == "flash.strength") {
            if (Build.VERSION.SDK_INT < 33) error("Strength control requires Android 13+")
            val maximum = flash.getCameraCharacteristics(id)[CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL] ?: 1
            check(maximum > 1) { "This flash has a fixed brightness" }
            val strength = (1 + (maximum - 1) * (value.toInt() - 1) / 99).coerceIn(1, maximum)
            flash.turnOnTorchWithStrengthLevel(id, strength)
            torchStates[id] = true
            return "Flashlight at level $strength of $maximum"
        }
        val enable = when (operation) {
            "flash.on" -> true
            "flash.off" -> false
            else -> !torchStates.getValue(id)
        }
        flash.setTorchMode(id, enable)
        torchStates[id] = enable
        return if (enable) "Flashlight on" else "Flashlight off"
    }

    private fun userIntent(encoded: String): Intent {
        val intent = Intent.parseUri(encoded, Intent.URI_INTENT_SCHEME)
        // Strip all imported grant/task flags. The chosen intent always runs as the ordinary app UID.
        intent.flags = 0
        intent.selector = null
        intent.clipData = null
        require(intent.data?.scheme != "file") { "File URIs are unsupported" }
        return intent
    }

    private suspend fun shellAction(label: String, send: () -> String): String {
        val result = withContext(Dispatchers.IO) { send() }
        check(!result.startsWith("Error:")) { result }
        return label
    }

    private val canLaunchActivity: Boolean get() = RemapperRuntime.uiVisible || ButtonAccessibilityService.connected != null

    private fun launch(intent: Intent, label: String): String {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!locked && canLaunchActivity) {
            try {
                (ButtonAccessibilityService.connected ?: context).startActivity(intent)
                return "$label launch requested"
            } catch (_: SecurityException) {
                // Firmware can require an explicit notification tap from the background.
            }
        }
        // Direct activity PendingIntent lets System UI apply normal keyguard and BAL rules.
        val pending = PendingIntent.getActivity(context, 3, intent, SessionNotifications.FLAGS)
        return notifications.offer(label, pending)
    }

    @Suppress("DEPRECATION")
    fun haptic() {
        val vibrator = context.getSystemService(Vibrator::class.java) ?: return
        val feedback = VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE)
        if (Build.VERSION.SDK_INT >= 33) vibrator.vibrate(feedback,
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK))
        else vibrator.vibrate(feedback)
    }
}
