package io.github.xiaomibutton.remapper.runtime

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.os.Build
import android.os.PowerManager
import android.view.accessibility.AccessibilityNodeInfo
import io.github.xiaomibutton.remapper.validatePackage
import org.json.JSONObject
import org.json.JSONTokener

/** Uses the active window only for an explicitly configured, enabled action. */
internal object UiActions {
    const val REQUIREMENT = "UI interactions"
    private val operations = mapOf("click" to AccessibilityNodeInfo.ACTION_CLICK,
        "long_click" to AccessibilityNodeInfo.ACTION_LONG_CLICK,
        "focus" to AccessibilityNodeInfo.ACTION_FOCUS, "select" to AccessibilityNodeInfo.ACTION_SELECT,
        "scroll_forward" to AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
        "scroll_backward" to AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)

    private data class Selector(val pkg: String, val id: String?, val text: String?, val action: Int)
    private fun parse(raw: String): Selector {
        val parser = JSONTokener(raw)
        val item = parser.nextValue() as? JSONObject ?: error("Enter an element selector JSON object")
        require(parser.nextClean() == 0.toChar())
        val pkg = item.get("package") as? String ?: error("Enter the target package")
        validatePackage(pkg)
        fun optional(field: String): String? = if (item.has(field)) (item.get(field) as? String
            ?: error("Invalid selector field")).also { require(it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl)) } else null
        val id = optional("id")
        val text = optional("text")
        require((id != null) != (text != null)) { "Choose one resource ID or one exact element text" }
        if (id != null) require((id.startsWith("$pkg:id/") || id.startsWith("android:id/")) &&
            id.substringAfter(":id/").matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "Enter a full resource ID such as package.name:id/next" }
        val action = operations[optional("action")] ?: error("Choose click, long_click, focus, select, scroll_forward or scroll_backward")
        return Selector(pkg, id, text, action)
    }

    fun validateElement(raw: String) { parse(raw) }

    private fun ready(): ButtonAccessibilityService {
        check(RemapperRuntime.status.value.uiActionsEnabled) { "Enable UI interactions in the remapper settings" }
        val service = checkNotNull(ButtonAccessibilityService.connected) { "Enable Xiaomi AI Button Remapper in Android Accessibility settings" }
        check(service.canInspectWindow) { "Turn the remapper accessibility service off and back on to update its permissions" }
        val keyguard = service.getSystemService(KeyguardManager::class.java)
        check(keyguard != null && !keyguard.isKeyguardLocked && !keyguard.isDeviceLocked &&
            service.getSystemService(PowerManager::class.java)?.isInteractive == true) { "Unlock the screen for this UI action" }
        return service
    }

    private fun safeNode(node: AccessibilityNodeInfo): Boolean =
        !node.isPassword && (Build.VERSION.SDK_INT < 34 || !node.isAccessibilityDataSensitive)

    @Suppress("DEPRECATION")
    private fun safeAncestors(node: AccessibilityNodeInfo, pkg: String): Boolean {
        var parent = node.parent
        repeat(64) {
            val current = parent ?: return true
            try {
                if (!safeNode(current) || current.packageName?.toString() != pkg) return false
                parent = current.parent
            } finally { current.recycle() }
        }
        parent?.recycle()
        return false
    }

    @Suppress("DEPRECATION")
    fun interact(raw: String): String {
        val choice = parse(raw)
        val root = ready().rootInActiveWindow ?: error("No active application window")
        try {
            check(root.packageName?.toString() == choice.pkg && safeNode(root)) { "Open the configured app before using this action" }
            val results = if (choice.id != null) root.findAccessibilityNodeInfosByViewId(choice.id)
                else root.findAccessibilityNodeInfosByText(checkNotNull(choice.text))
            try {
                val matching = results.filter { node ->
                    node.packageName?.toString() == choice.pkg && node.isVisibleToUser && safeNode(node) &&
                        safeAncestors(node, choice.pkg) && (choice.id != null || node.text?.toString() == choice.text ||
                        node.contentDescription?.toString() == choice.text)
                }
                check(matching.size == 1) { if (matching.isEmpty()) "No eligible visible element matched" else "Several visible elements matched. Use a unique resource ID." }
                val node = matching.single()
                check(node.actionList.any { it.id == choice.action } && node.performAction(choice.action)) {
                    "The selected element does not support this operation"
                }
                return "UI element action requested"
            } finally { results.forEach { it.recycle() } }
        } finally { root.recycle() }
    }

    @Suppress("DEPRECATION")
    fun toggleShade(quick: Boolean): String {
        val service = ready()
        val root = service.rootInActiveWindow ?: error("Cannot identify the active window")
        val pkg = try { root.packageName?.toString() } finally { root.recycle() }
        check(!pkg.isNullOrBlank()) { "Cannot identify the active window" }
        val systemUi = pkg in setOf("com.android.systemui", "miui.systemui.plugin")
        val action = if (systemUi) {
            if (Build.VERSION.SDK_INT >= 31) AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE
            else AccessibilityService.GLOBAL_ACTION_BACK
        } else if (quick) AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS else AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
        check(service.performGlobalAction(action)) { "Android declined the shade action" }
        return if (systemUi) "Requested shade dismissal" else if (quick) "Requested quick settings" else "Requested notifications"
    }
}
