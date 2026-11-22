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
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.service.notification.Condition
import io.github.xiaomibutton.remapper.validateAction
import io.github.xiaomibutton.remapper.ActionKind
import io.github.xiaomibutton.remapper.ActionSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class ActionDispatcher(
    private val context: Context,
    private val notifications: SessionNotifications,
) : AutoCloseable {
    private val camera = context.getSystemService(CameraManager::class.java)
    private val torchStates = mutableMapOf<String, Boolean>()
    private val torchObserver = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { torchStates[cameraId] = enabled }
        override fun onTorchModeUnavailable(cameraId: String) { torchStates.remove(cameraId) }
    }
    private val listening = runCatching {
        camera.registerTorchCallback(torchObserver, Handler(Looper.getMainLooper()))
    }.isSuccess

    override fun close() {
        if (listening) camera.unregisterTorchCallback(torchObserver)
    }

    private val locked: Boolean get() = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
        !context.getSystemService(PowerManager::class.java).isInteractive

    suspend fun perform(action: ActionSpec, helper: IInputHelper): String {
        validateAction(action)
        return when (action.kind) {
            ActionKind.NONE -> "Button action disabled"
            ActionKind.CAMERA -> {
                val secure = locked
                val result = withContext(Dispatchers.IO) { helper.openCamera(secure) }
                if (result.startsWith("Error:")) {
                    val intent = Intent(if (secure) MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE else MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                    "${launch(intent, "Camera")} · $result"
                } else if (secure) "Secure camera requested" else "Camera requested"
            }
            ActionKind.APP -> {
                val intent = context.packageManager.getLaunchIntentForPackage(action.value)
                    ?: error("No launchable activity for this app or profile")
                if (locked) launch(intent, "Application") else {
                    val result = withContext(Dispatchers.IO) { helper.openLauncherApp(action.value) }
                    if (result.startsWith("Error:")) launch(intent, "Application") else "Application launch requested"
                }
            }
            ActionKind.FLASHLIGHT -> {
                val id = torchStates.keys.firstOrNull {
                    camera.getCameraCharacteristics(it)[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_BACK
                } ?: torchStates.keys.firstOrNull() ?: error("Flashlight unavailable or camera in use")
                val enable = torchStates[id] != true
                camera.setTorchMode(id, enable)
                torchStates[id] = enable
                if (enable) "Flashlight on" else "Flashlight off"
            }
            ActionKind.SILENT -> {
                val audio = context.getSystemService(AudioManager::class.java)
                val silent = audio.ringerMode != AudioManager.RINGER_MODE_SILENT
                audio.ringerMode = if (silent) AudioManager.RINGER_MODE_SILENT else AudioManager.RINGER_MODE_NORMAL
                if (audio.ringerMode == AudioManager.RINGER_MODE_SILENT) "Ringer silent" else "Ringer normal"
            }
            ActionKind.DND -> {
                val nm = context.getSystemService(NotificationManager::class.java)
                check(nm.isNotificationPolicyAccessGranted) { "Grant Do Not Disturb access in Android Settings" }
                // API 35+ gives each app its own rule. Another app's mode must not invert our toggle.
                val active = if (Build.VERSION.SDK_INT >= 35) nm.automaticZenRules.keys.any {
                    nm.getAutomaticZenRuleState(it) == Condition.STATE_TRUE
                } else nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
                nm.setInterruptionFilter(if (active) NotificationManager.INTERRUPTION_FILTER_ALL else NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                if (active) "Remapper DND off" else "Remapper DND on"
            }
            ActionKind.URL -> {
                val uri = Uri.parse(action.value)
                require(uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank()) { "Choose an HTTP or HTTPS URL" }
                val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
                if (locked) launch(intent, "Web link") else {
                    val result = withContext(Dispatchers.IO) { helper.openWeb(uri.toString()) }
                    if (result.startsWith("Error:")) launch(intent, "Web link") else "Web link requested"
                }
            }
            ActionKind.SETTINGS -> {
                require(action.value.startsWith("android.settings.")) { "Invalid Settings action" }
                val intent = Intent(action.value)
                if (locked) launch(intent, "Android Settings") else {
                    val result = withContext(Dispatchers.IO) { helper.openSettings(action.value) }
                    if (result.startsWith("Error:")) launch(intent, "Android Settings") else "Settings page requested"
                }
            }
            ActionKind.INTENT -> launch(userIntent(action.value), "Custom intent")
            ActionKind.SHORTCUT -> {
                if (action.extra.isBlank() || action.value.isBlank()) {
                    launch(userIntent(action.value.ifBlank { action.extra }), "App shortcut")
                } else {
                    val launcher = context.getSystemService(LauncherApps::class.java)
                    check(launcher.hasShortcutHostPermission()) { "Android has not granted shortcut-host access" }
                    val user = Process.myUserHandle()
                    if (RemapperRuntime.visible && !locked) {
                        launcher.startShortcut(action.value, action.extra, null, null, user)
                        "Shortcut launch requested"
                    } else if (Build.VERSION.SDK_INT >= 31) {
                        val pending = launcher.getShortcutIntent(action.value, action.extra, null, user)
                            ?: error("Shortcut is no longer available")
                        notifications.offer("App shortcut", pending)
                    } else "Open the remapper to launch this shortcut on this Android version"
                }
            }
            ActionKind.SHELL -> withContext(Dispatchers.IO) { helper.execute(action.value) }.take(300)
        }
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

    private fun launch(intent: Intent, label: String): String {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (RemapperRuntime.visible && !locked) {
            context.startActivity(intent)
            return "$label launch requested"
        }
        // Direct activity PendingIntent lets System UI apply normal keyguard and BAL rules.
        val pending = PendingIntent.getActivity(context, 3, intent, SessionNotifications.FLAGS)
        return notifications.offer(label, pending)
    }

    @Suppress("DEPRECATION")
    fun haptic() {
        context.getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
