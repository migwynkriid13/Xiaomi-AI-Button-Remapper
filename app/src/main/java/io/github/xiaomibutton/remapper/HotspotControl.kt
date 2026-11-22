package io.github.xiaomibutton.remapper

import android.annotation.TargetApi
import android.content.Context
import android.net.TetheringInterface
import android.net.TetheringManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

@TargetApi(36)
internal object HotspotControl {
    /** Null means the carrier or platform requires changing tethering in Settings. */
    suspend fun apply(context: Context, operation: String): String? {
        val manager = context.getSystemService(TetheringManager::class.java) ?: return null
        val executor = context.mainExecutor
        return try {
            val enable = when (operation) {
                "on" -> true
                "off" -> false
                else -> {
                    val current = CompletableDeferred<Boolean>()
                    val observer = object : TetheringManager.TetheringEventCallback {
                        override fun onTetheredInterfacesChanged(interfaces: Set<TetheringInterface>) {
                            current.complete(interfaces.any { it.type == TetheringManager.TETHERING_WIFI })
                        }
                    }
                    manager.registerTetheringEventCallback(executor, observer)
                    try { !(withTimeoutOrNull(2500) { current.await() } ?: return null) }
                    finally { manager.unregisterTetheringEventCallback(observer) }
                }
            }
            val request = TetheringManager.TetheringRequest.Builder(TetheringManager.TETHERING_WIFI).build()
            val completed = CompletableDeferred<Boolean>()
            if (enable) manager.startTethering(request, executor, object : TetheringManager.StartTetheringCallback {
                override fun onTetheringStarted() { completed.complete(true) }
                override fun onTetheringFailed(error: Int) { completed.complete(false) }
            }) else manager.stopTethering(request, executor, object : TetheringManager.StopTetheringCallback {
                override fun onStopTetheringSucceeded() { completed.complete(true) }
                override fun onStopTetheringFailed(error: Int) { completed.complete(false) }
            })
            if (withTimeoutOrNull(5000) { completed.await() } == true)
                if (enable) "Wi-Fi hotspot enabled" else "Wi-Fi hotspot disabled"
            else null
        } catch (_: SecurityException) { null } catch (_: UnsupportedOperationException) { null }
    }
}
