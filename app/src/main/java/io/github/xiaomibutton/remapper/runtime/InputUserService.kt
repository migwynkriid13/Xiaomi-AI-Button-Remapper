package io.github.xiaomibutton.remapper.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.os.Process
import io.github.xiaomibutton.remapper.QuickSettingsTiles
import io.github.xiaomibutton.remapper.launchableActivities
import io.github.xiaomibutton.remapper.systemPages
import io.github.xiaomibutton.remapper.validatePackage
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Constructed by Shizuku with the application package context in its own shell process.
 * No Android component exports this Binder. Check the owning app UID on every entry point.
 */
class InputUserService(context: Context) : IInputHelper.Stub() {
    private val ownerUid = context.applicationInfo.uid
    private val packages = context.packageManager
    private val started = AtomicBoolean()
    private val stopping = AtomicBoolean()
    @Volatile private var clientSink: IInputClient? = null
    @Volatile private var actionsAllowed = false
    private val commandBusy = AtomicBoolean()
    private val sender = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(128))
    private val ownerDeath = IBinder.DeathRecipient { exitProcess(0) }

    private fun checkOwner() {
        if (Binder.getCallingUid() != ownerUid) throw SecurityException("Unexpected Binder caller")
    }

    override fun start(intercept: Boolean, executeActions: Boolean, client: IInputClient) {
        checkOwner()
        require(!intercept || executeActions) { "A read-only test cannot grab the button" }
        check(!stopping.get() && started.compareAndSet(false, true)) { "Session already used" }
        client.asBinder().linkToDeath(ownerDeath, 0)
        clientSink = client
        actionsAllowed = executeActions
        val callbacks = object : NativeCallbacks {
            override fun onStatus(message: String) = send {
                client.status("$message · Helper PID ${Process.myPid()}, UID ${Process.myUid()}")
            }
            override fun onKey(value: Int, uptimeMillis: Long) = send { client.key(value, uptimeMillis) }
        }
        thread(name = "gpio-input") {
            val result = try {
                if (stopping.get()) "Stopped" else NativeInput.run(intercept, callbacks)
            } catch (e: LinkageError) {
                "Native library unavailable: ${e.message}"
            } catch (e: Exception) {
                "Input session failed: ${e.message}"
            }
            stopping.set(true)
            send { client.ended(result) }
            // A stopped Shizuku server can no longer deliver its destroy transaction.
            // End this one-session process even when its former manager is gone.
            Thread.sleep(1200)
            exitProcess(0)
        }
    }

    // Slow or disappearing clients must never block the native forwarding loop.
    private fun send(call: () -> Unit) {
        try {
            sender.execute {
                try { call() } catch (_: Exception) { exitProcess(0) }
            }
        } catch (_: RejectedExecutionException) {
            exitProcess(0)
        }
    }

    override fun dashboardSession(owner: IBinder) {
        checkOwner()
        check(!stopping.get() && started.compareAndSet(false, true))
        owner.linkToDeath(ownerDeath, 0)
        actionsAllowed = true
    }

    override fun imePolicy(displayId: Int, policy: Int): Int {
        checkOwner()
        require(displayId > 0 && policy in -1..2 && actionsAllowed)
        val binder =
            Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "window") as IBinder
        val api = Class.forName("android.view.IWindowManager")
        val service =
            Class.forName("android.view.IWindowManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
        val int = Int::class.javaPrimitiveType
        if (policy >= 0)
            api.getMethod("setDisplayImePolicy", int, int).invoke(service, displayId, policy)
        return api.getMethod("getDisplayImePolicy", int).invoke(service, displayId) as Int
    }

    override fun heartbeat() {
        checkOwner()
        if (started.get() && !stopping.get()) {
            NativeInput.renew()
            send { clientSink?.pulse() }
        }
    }

    override fun stop() {
        checkOwner()
        if (stopping.compareAndSet(false, true) && started.get()) NativeInput.stop()
    }

    // Shizuku's reserved removal transaction. Process death also closes every device FD.
    override fun destroy() {
        val uid = Binder.getCallingUid()
        check(uid == ownerUid || uid == Process.SHELL_UID || uid == 0 || uid == Process.SYSTEM_UID)
        exitProcess(0)
    }

    override fun execute(command: String): String {
        checkOwner()
        require(PermittedCommands.accepts(command)) { "Command is outside the permitted list" }
        val words = command.split(' ')
        return command(listOf("/system/bin/" + words.first()) + words.drop(1))
    }

    // Android's per-user UID range is 100000. Always target the owner of the remapper.
    private val androidUser = (ownerUid / 100_000).toString()

    override fun openCamera(secure: Boolean): String {
        checkOwner()
        val action = if (secure) "android.media.action.STILL_IMAGE_CAMERA_SECURE" else "android.media.action.STILL_IMAGE_CAMERA"
        return command(listOf("/system/bin/am", "start", "--user", androidUser, "-a", action))
    }

    override fun openSettings(action: String): String {
        checkOwner()
        require(action in systemPages) { "Unknown Settings page" }
        return command(listOf("/system/bin/am", "start", "--user", androidUser, "-a", action))
    }

    override fun openWeb(url: String): String {
        checkOwner()
        val uri = Uri.parse(url)
        require(url.length <= 2048 && uri.scheme?.lowercase() in setOf("https", "http") && !uri.host.isNullOrBlank()) {
            "Expected an HTTP or HTTPS URL"
        }
        // -d is a single argv value; never place the URL in shell source.
        return command(listOf("/system/bin/am", "start", "--user", androidUser, "-a",
            "android.intent.action.VIEW", "-c", "android.intent.category.BROWSABLE", "-d", url))
    }

    override fun openLauncherApp(packageName: String): String {
        checkOwner()
        validatePackage(packageName)
        val activity = packages.launchableActivities(packageName).firstOrNull()?.activityInfo
            ?: error("No launchable activity for this app or profile")
        val component = ComponentName(activity.packageName, activity.name)
        // An implicit activity launch also requires CATEGORY_DEFAULT. Launchers need only CATEGORY_LAUNCHER.
        return command(listOf("/system/bin/am", "start", "--user", androidUser,
            "-a", Intent.ACTION_MAIN, "-c", Intent.CATEGORY_LAUNCHER,
            "-n", component.flattenToShortString(), "--activity-reset-task-if-needed"))
    }

    override fun deviceAction(id: String, value: String): String {
        checkOwner()
        return ExpertDeviceActions.run(id, value, ::command)
    }

    override fun clickQuickTile(component: String): String {
        checkOwner()
        val tile = QuickSettingsTiles.parse(component)
        @Suppress("DEPRECATION")
        val service = try { packages.getServiceInfo(tile.component, 0) }
        catch (_: PackageManager.NameNotFoundException) { return "Error: Control Centre tile is no longer installed" }
        require(service.enabled && service.exported && service.permission == QuickSettingsTiles.BIND_PERMISSION) {
            "Component is not an enabled Control Centre tile"
        }
        val configured = command(listOf("/system/bin/settings", "--user", androidUser,
            "get", "secure", "sysui_qs_tiles"))
        if (configured.startsWith("Error:")) return configured
        if (configured.split(',').none { it.trim() == tile.systemSpec }) {
            val label = runCatching { service.loadLabel(packages).toString()
                .filterNot(Char::isISOControl).trim().take(120) }.getOrDefault("This tile").ifBlank { "This tile" }
            return "Error: Add $label to Control Centre first"
        }
        return command(listOf("/system/bin/cmd", "statusbar", "click-tile", tile.encoded))
    }

    private fun command(argv: List<String>): String {
        check(actionsAllowed && started.get() && !stopping.get()) { "Remapping session inactive" }
        if (!commandBusy.compareAndSet(false, true)) return "Error: previous action still running"
        try {
            val child = ProcessBuilder(argv).redirectErrorStream(true).start()
            try {
                val maxBytes = 4096
                val reading = java.util.concurrent.CompletableFuture.supplyAsync {
                    val collected = java.io.ByteArrayOutputStream()
                    val block = ByteArray(4096)
                    child.inputStream.use { stream ->
                        while (true) {
                            val n = stream.read(block)
                            if (n < 0) break
                            collected.write(block, 0, minOf(n, maxBytes - collected.size()))
                        }
                    }
                    collected.toString(Charsets.UTF_8.name()).trim()
                }
                if (!child.waitFor(4, TimeUnit.SECONDS)) return "Error: action timed out"
                val output = reading.get(1, TimeUnit.SECONDS)
                if (child.exitValue() != 0 || output.lineSequence().any {
                    it.startsWith("Error", true) || it.contains("SecurityException")
                }) return "Error: ${output.ifBlank { "exit ${child.exitValue()}" }}"
                return output.ifBlank { "Completed" }
            } finally {
                child.destroyForcibly()
                child.inputStream.close()
            }
        } catch (e: Exception) {
            return "Error: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            commandBusy.set(false)
        }
    }
}
