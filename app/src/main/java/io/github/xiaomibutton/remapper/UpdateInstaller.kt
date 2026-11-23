package io.github.xiaomibutton.remapper

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal sealed interface UpdateState {
    data object Ready : UpdateState
    data class Downloading(val percent: Int) : UpdateState
    data object Installing : UpdateState
    data class Confirm(val intent: Intent) : UpdateState
    data class Failed(val message: String) : UpdateState
}

internal object UpdateInstaller {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val current = MutableStateFlow<UpdateState>(UpdateState.Ready)
    val state = current.asStateFlow()

    fun start(context: Context, release: ApkRelease) {
        val previous = current.value
        if (previous != UpdateState.Ready && previous !is UpdateState.Failed) return
        if (!current.compareAndSet(previous, UpdateState.Downloading(0))) return
        val app = context.applicationContext
        scope.launch {
            try {
                stageUpdate(app, release)
            } catch (_: UpdateIntegrityException) {
                fail("The download didn’t match the published APK. Tap Update to try again.")
            } catch (_: Exception) {
                fail("Couldn’t download the update. Check your connection and free space, then try again.")
            }
        }
    }

    private fun stageUpdate(context: Context, release: ApkRelease) {
        val installer = context.packageManager.packageInstaller
        val prefs = context.getSharedPreferences("apk_update", Context.MODE_PRIVATE)
        // A killed process may leave a partially written or unconfirmed session behind.
        prefs.edit().remove("session").apply()
        installer.mySessions.filter { it.appPackageName == context.packageName }.forEach {
            installer.abandonSession(it.sessionId)
        }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(release.size)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, release.size).use { output ->
                    downloadApk(release, output) { current.value = UpdateState.Downloading(it) }
                    session.fsync(output)
                }
                val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val result = PendingIntent.getBroadcast(context, id, Intent(context, UpdateResultReceiver::class.java), flags)
                prefs.edit().putInt("session", id).apply()
                current.value = UpdateState.Installing
                session.commit(result.intentSender)
            }
        } catch (e: Exception) {
            prefs.edit().remove("session").apply()
            installer.abandonSession(id)
            throw e
        }
    }

    fun handleResult(context: Context, result: Intent) {
        val prefs = context.getSharedPreferences("apk_update", Context.MODE_PRIVATE)
        val id = result.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (id < 0 || prefs.getInt("session", -1) != id) return
        val status = result.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = result.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            current.value = if (confirm != null) UpdateState.Confirm(confirm)
                else UpdateState.Failed("Android couldn’t open the update confirmation. Try again.")
            return
        }
        prefs.edit().remove("session").apply()
        current.value = when (status) {
            PackageInstaller.STATUS_SUCCESS -> UpdateState.Ready
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateState.Failed("Update canceled. You can try again.")
            PackageInstaller.STATUS_FAILURE_STORAGE -> UpdateState.Failed("Free some storage, then try again.")
            PackageInstaller.STATUS_FAILURE_BLOCKED -> UpdateState.Failed("Android blocked this update. Check your install permissions.")
            PackageInstaller.STATUS_FAILURE_CONFLICT -> UpdateState.Failed("The APK conflicts with the installed app. Check that you’re using the signed release.")
            else -> UpdateState.Failed("Android couldn’t install the update. Your current version is still installed.")
        }
    }

    fun showConfirmation(context: Context, confirmation: UpdateState.Confirm) {
        if (!current.compareAndSet(confirmation, UpdateState.Installing)) return
        try {
            context.startActivity(confirmation.intent)
        } catch (_: RuntimeException) {
            fail("Android couldn’t open the update confirmation. Try again.")
        }
    }

    fun fail(message: String) { current.value = UpdateState.Failed(message) }
}

class UpdateResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = UpdateInstaller.handleResult(context, intent)
}
