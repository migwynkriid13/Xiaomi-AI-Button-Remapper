package io.github.xiaomibutton.remapper.runtime

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent

/** Optional action host. Physical button input comes from the Shizuku user service. */
class ButtonAccessibilityService : AccessibilityService() {
    private var editorRequested = false
    internal val canInspectWindow: Boolean get() = serviceInfo?.let {
        (it.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT) != 0
    } == true

    override fun onServiceConnected() {
        super.onServiceConnected()
        RemapperRuntime.initialize(this)
        connected = this
        refreshFlags()
        RemapperRuntime.change { it.copy(accessibilityConnected = true) }
    }

    internal fun refreshFlags() {
        if (connected !== this) return
        val info = serviceInfo ?: return
        val viewIds = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        var flags = info.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS.inv()
        flags = if (RemapperRuntime.status.value.uiActionsEnabled) flags or viewIds else flags and viewIds.inv()
        if (Build.VERSION.SDK_INT >= 33) {
            val ime = AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR
            flags = if (editorRequested) flags or ime else flags and ime.inv()
        }
        if (flags != info.flags) {
            info.flags = flags
            serviceInfo = info
        }
    }

    internal fun requestEditor(requested: Boolean) {
        editorRequested = requested
        refreshFlags()
    }

    @Suppress("DEPRECATION")
    internal fun activeAppForProfiles(): String? {
        if (getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false) return null
        return runCatching {
            val root = rootInActiveWindow ?: return null
            try { root.packageName?.toString() } finally { root.recycle() }
        }.getOrNull()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun disconnect() {
        if (connected !== this) return
        connected = null
        editorRequested = false
        RemapperRuntime.change { it.copy(accessibilityConnected = false) }
    }

    override fun onUnbind(intent: Intent?): Boolean { disconnect(); return super.onUnbind(intent) }
    override fun onDestroy() { disconnect(); super.onDestroy() }

    internal companion object {
        var connected: ButtonAccessibilityService? = null
            private set
    }
}
