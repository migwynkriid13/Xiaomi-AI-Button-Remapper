package io.github.xiaomibutton.remapper

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.widget.Toast
import io.github.xiaomibutton.remapper.runtime.ButtonAccessibilityService
import io.github.xiaomibutton.remapper.runtime.UiActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal object DeviceActions {
    private const val WRITE = "Modify system settings"
    private const val PANEL = "Confirm in Android Settings"
    private const val ACCESS = "Accessibility"
    private const val PER_PHONE = "Phone permissions"
    private const val MAP = "Mappings"
    private const val DISPLAY = "Display"
    private const val NAV = "Navigation"
    private const val DEVICE = "Device"
    private const val CONNECT = "Connectivity"
    private val slots = listOf("toggle" to "Toggle", "on" to "Enable", "off" to "Disable")
    private val rotationChoices = listOf("0" to "0°", "1" to "90°", "2" to "180°", "3" to "270°")
    val actions = buildList {
        fun action(id: String, title: String, group: String, parameter: ActionParameter = ActionParameter.NONE,
                   requirement: String = "", choices: List<Pair<String, String>> = emptyList(), hint: String = "") {
            add(CatalogAction(id, title, group, parameter, requirement, choices, hint))
        }
        fun toggles(id: String, label: String, group: String, requirement: String) {
            for ((verb, title) in slots) action("$id.$verb", "$title $label", group, requirement = requirement)
        }
        for ((key, name) in listOf("wifi" to "Wi-Fi", "bluetooth" to "Bluetooth", "data" to "mobile data",
            "airplane" to "airplane mode", "nfc" to "NFC")) toggles("radio.$key", name, CONNECT, PANEL)
        toggles("radio.hotspot", "Wi-Fi hotspot", CONNECT, "Android 16+ · tethering permission")
        toggles("display.auto", "automatic brightness", DISPLAY, WRITE)
        toggles("display.rotate", "automatic rotation", DISPLAY, WRITE)
        toggles("display.night", "Night Light", DISPLAY, PANEL)
        action("display.brighter", "Increase display brightness", DISPLAY, ActionParameter.NUMBER, WRITE, hint = "Change in percent (1–100)")
        action("display.dimmer", "Decrease display brightness", DISPLAY, ActionParameter.NUMBER, WRITE, hint = "Change in percent (1–100)")
        action("display.portrait", "Set portrait orientation", DISPLAY, requirement = WRITE)
        action("display.landscape", "Set landscape orientation", DISPLAY, requirement = WRITE)
        action("display.orientation", "Set screen orientation", DISPLAY, ActionParameter.CHOICE, WRITE, rotationChoices)
        action("display.cycle", "Cycle screen orientations", DISPLAY, requirement = WRITE)
        for ((id, name) in listOf("back" to "Go back", "home" to "Go home", "recents" to "Open recents",
            "last" to "Go to last app", "split" to "Toggle split screen")) action("nav.$id", name, NAV, requirement = ACCESS)
        action("nav.menu", "Open app menu", NAV, requirement = ACCESS, hint = "Requires Android 16 or later.")
        action("nav.all_apps", "Open all apps", NAV, requirement = ACCESS, hint = "Requires Android 12 or later.")
        action("nav.headset", "Press headset button", NAV, requirement = ACCESS, hint = "Requires Android 12 or later.")
        val direction = listOf("up" to "Up", "down" to "Down", "left" to "Left", "right" to "Right", "center" to "Select")
        action("nav.dpad", "Send navigation key", NAV, ActionParameter.CHOICE, ACCESS, direction,
            "Works outside a text field when the device supports this action. Requires Android 13 or later.")
        for ((key, title) in listOf("shortcut" to "Run accessibility shortcut", "button" to "Press accessibility button",
            "choose" to "Choose an accessibility button action")) {
            action("a11y.$key", title, "Interface", requirement = ACCESS, hint = "Requires Android 12 or later.")
        }
        for ((id, label) in listOf("notifications" to "Expand notifications", "quick" to "Open quick settings",
            "collapse" to "Close the notification shade")) action("shade.$id", label, "Interface", requirement = ACCESS)
        action("shade.toggle_notifications", "Toggle notification drawer", "Interface", requirement = UiActions.REQUIREMENT)
        action("shade.toggle_quick", "Toggle quick settings", "Interface", requirement = UiActions.REQUIREMENT)
        action("device.screenshot", "Take screenshot", "Button actions")
        action("device.lock", "Lock screen", DEVICE, requirement = ACCESS)
        action("device.power_menu", "Show power menu", DEVICE, requirement = ACCESS)
        action("device.controls", "Open device controls", DEVICE, requirement = ACCESS)
        action("device.secure_lock", "Securely lock device", DEVICE, requirement = "Device admin (lock only)")
        action("apps.assistant", "Open device assistant", "Apps")
        action("apps.voice", "Open voice assistant", "Apps")
        action("apps.details", "Open app info", "Apps", ActionParameter.APP)
        action("intent.broadcast", "Send an Android broadcast", "Automation", ActionParameter.TEXT, hint = "Explicit Android intent URI with a package or component")
        action("intent.service", "Start an Android service", "Automation", ActionParameter.TEXT, hint = "Explicit Android intent URI with a target component")
        action("apps.setting", "Modify system setting", "Settings", ActionParameter.TEXT, WRITE,
            hint = "Writable Android System setting, in the form name=value")
        action("flash.on", "Enable flashlight", "Flashlight")
        action("flash.off", "Disable flashlight", "Flashlight")
        action("flash.strength", "Change flashlight brightness", "Flashlight", ActionParameter.NUMBER,
            hint = "Strength in percent (1–100). Requires a variable-strength flash and Android 13+.")
        action("phone.dial", "Open phone dialer", "Phone", ActionParameter.TEXT, hint = "Phone number")
        action("phone.call", "Make phone call", "Phone", ActionParameter.TEXT, PER_PHONE, hint = "Phone number")
        action("phone.answer", "Answer phone call", "Phone", requirement = PER_PHONE)
        action("phone.end", "End phone call", "Phone", requirement = PER_PHONE)
        action("phone.sms", "Send SMS", "Phone", ActionParameter.TEXT, "SMS permission", hint = "Phone number | message")
        action("phone.compose", "Compose SMS", "Phone", ActionParameter.TEXT, hint = "Phone number | message")
        action("utility.toast", "Show a message", "Interface", ActionParameter.TEXT, hint = "Message")
        action("utility.notify", "Create notification", "Interface", ActionParameter.TEXT, hint = "Notification text")
        action("utility.vibrate", "Vibrate", "Device", ActionParameter.NUMBER, hint = "Duration in milliseconds (1–5000)")
        action("utility.http", "Send HTTP request", "Automation", ActionParameter.TEXT, hint = "URL for GET, or JSON with url, method, headers and body. HTTP sends unencrypted. Requests run on each mapped gesture.")
        action("vars.set", "Set variable", "Variables", ActionParameter.TEXT, hint = "name=value. Use {name} in a message or notification.")
        action("vars.modify", "Modify numeric variable", "Variables", ActionParameter.TEXT, hint = "name=amount to add (can be negative)")
        add(KeyMapInterop.action("maps.trigger", "Trigger a Key Mapper mapping", MAP))
        for (verb in listOf("pause", "resume", "toggle")) action("maps.$verb", verb.replaceFirstChar { it.uppercase() } + " Key Mapper mappings", MAP, requirement = "Key Mapper")
        for (type in listOf("group","map")) for (verb in listOf("enable","disable","toggle")) {
            action("maps.$type.$verb", verb.replaceFirstChar { it.uppercase() } + " Key Mapper " + type, MAP,
                ActionParameter.TEXT, "Key Mapper", hint = "Paste the $type UID from Key Mapper")
        }
    }

    // Retain IDs so an older backup can still restore every supported gesture in the same file.
    val retired = listOf(
        CatalogAction("device.power", "Turn screen on or off", DEVICE),
        CatalogAction("apps.stop", "Force stop an app", "Apps", ActionParameter.APP),
        CatalogAction("apps.clear_recent", "Close app and clear it from recents", "Apps", ActionParameter.APP),
    ) + listOf("shutdown" to "Shut down device", "reboot" to "Restart device").map { (id, label) ->
        CatalogAction("device.$id", label, DEVICE, ActionParameter.CHOICE, choices = listOf("confirmed" to "Confirmed"))
    }

    private val variableName = Regex("[A-Za-z][A-Za-z0-9_]{0,47}")
    private val phone = Regex("[+0-9 *#().-]{1,48}")
    private fun sms(value: String): Pair<String,String> {
        val number = value.substringBefore('|').trim()
        val message = value.substringAfter('|', "").trim()
        require(phone.matches(number) && number.any(Char::isDigit) && message.isNotBlank()) { "Enter phone number | message" }
        return number to message
    }
    fun validate(id: String, value: String) {
        when (id) {
            "display.brighter","display.dimmer","flash.strength" -> require(value.toIntOrNull() in 1..100) { "Enter a percent from 1 to 100" }
            "utility.vibrate" -> require(value.toIntOrNull() in 1..5000) { "Enter 1 to 5000 milliseconds" }
            "utility.http" -> httpSpec(value)
            "intent.broadcast","intent.service" -> explicitIntent(value, id)
            "vars.set","vars.modify","apps.setting" -> {
                require('=' in value && variableName.matches(value.substringBefore('='))) { "Enter name=value" }
                if (id == "vars.modify") require(value.substringAfter('=').toLongOrNull() != null) { "Use a signed integer amount" }
            }
            "phone.sms","phone.compose" -> sms(value)
            "phone.call","phone.dial" -> require(phone.matches(value) && value.any(Char::isDigit)) { "Enter a phone number" }
        }
        if (id.startsWith("maps.map.") || id.startsWith("maps.group.")) KeyMapInterop.uid(value)
    }

    private fun httpSpec(value: String): JSONObject {
        val obj = if (value.startsWith("{")) JSONObject(value) else JSONObject().put("url", value)
        val url = URL(obj.getString("url"))
        require(url.protocol in setOf("https", "http") && url.host.isNotBlank() && url.userInfo == null) { "Enter an HTTP or HTTPS URL" }
        val method = obj.optString("method", "GET").uppercase()
        require(method in setOf("GET","HEAD","POST","PUT","PATCH","DELETE")) { "Unsupported HTTP method" }
        require(method !in setOf("GET","HEAD") || !obj.has("body")) { "Use a method that accepts a request body" }
        obj.optJSONObject("headers")?.let { headers -> for (name in headers.keys()) {
            require(Regex("[A-Za-z0-9-]+").matches(name) && headers.getString(name).none(Char::isISOControl)) { "Invalid HTTP header" }
        } }
        return obj
    }

    private fun explicitIntent(encoded: String, id: String): Intent {
        require(encoded.startsWith("intent:") && encoded.endsWith(";end")) { "Enter an Android intent URI" }
        val intent = Intent.parseUri(encoded, Intent.URI_INTENT_SCHEME)
        intent.flags = 0
        intent.selector = null
        intent.clipData = null
        require(intent.component != null || id == "intent.broadcast" && intent.`package` != null) { "Select an explicit target" }
        require(intent.data?.scheme != "file") { "Use a content URI" }
        return intent
    }

    suspend fun execute(context: Context, id: String, value: String, launch: (Intent, String) -> String): String {
        val action = requireNotNull(ActionLibrary.find(id))
        if (action.requirement == KeyMapInterop.REQUIREMENT) return KeyMapInterop.trigger(context, value)
        if (id.startsWith("radio.hotspot.")) {
            if (Build.VERSION.SDK_INT >= 36) {
                val result = HotspotControl.apply(context, id.substringAfterLast('.'))
                if (result != null) return result
            }
            return launch(Intent("android.settings.TETHER_SETTINGS"), "Confirm hotspot change in Settings")
        }
        if (action.requirement == PANEL) {
            val page = when {
                id.startsWith("radio.wifi.") -> Settings.Panel.ACTION_WIFI
                id.startsWith("radio.bluetooth.") -> Settings.ACTION_BLUETOOTH_SETTINGS
                id.startsWith("radio.airplane.") -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
                id.startsWith("radio.nfc.") -> Settings.ACTION_NFC_SETTINGS
                id.startsWith("display.night.") -> Settings.ACTION_NIGHT_DISPLAY_SETTINGS
                else -> Settings.Panel.ACTION_INTERNET_CONNECTIVITY
            }
            return launch(Intent(page), "Confirm " + action.title + " in Settings")
        }
        if (action.requirement == WRITE) return updateSetting(context, id, value)
        if (action.requirement == ACCESS || id == "device.screenshot") return global(context, id, action.title, value)
        if (id.startsWith("maps.")) return KeyMapInterop.control(context, id, value)
        val values = context.getSharedPreferences("action_variables", Context.MODE_PRIVATE)
        fun expand(text: String) = Regex("\\{([A-Za-z][A-Za-z0-9_]*)}").replace(text) { values.getString(it.groupValues[1], "") ?: "" }.take(2048)
        return when (id) {
            "shade.toggle_quick","shade.toggle_notifications" -> UiActions.toggleShade(id == "shade.toggle_quick")
            "device.secure_lock" -> DeviceAdminLock.apply(context)
            "intent.broadcast" -> { context.sendBroadcast(explicitIntent(value,id)); "Broadcast sent" }
            "intent.service" -> {
                checkNotNull(context.startService(explicitIntent(value,id))) { "Service unavailable" }
                "Service start requested"
            }
            "apps.assistant" -> launch(Intent(Intent.ACTION_ASSIST), action.title)
            "apps.voice" -> launch(Intent(android.speech.RecognizerIntent.ACTION_VOICE_SEARCH_HANDS_FREE), action.title)
            "apps.details" -> launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$value")), action.title)
            "utility.toast" -> { Toast.makeText(context, expand(value), Toast.LENGTH_SHORT).show(); "Message shown" }
            "utility.notify" -> {
                val manager = context.getSystemService(NotificationManager::class.java)
                check(manager.areNotificationsEnabled()) { "Allow app notifications" }
                manager.notify(692, Notification.Builder(context,"remap_actions").setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("AI button").setContentText(expand(value)).setVisibility(Notification.VISIBILITY_PRIVATE).build())
                "Notification posted"
            }
            "utility.vibrate" -> {
                context.getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createOneShot(value.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
                "Vibration requested"
            }
            "utility.http" -> withContext(Dispatchers.IO) {
                val spec = httpSpec(value)
                val connection = URL(spec.getString("url")).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    connection.instanceFollowRedirects = false
                    connection.useCaches = false
                    connection.requestMethod = spec.optString("method","GET").uppercase()
                    spec.optJSONObject("headers")?.let { h -> for (key in h.keys()) connection.setRequestProperty(key, h.getString(key)) }
                    if (spec.has("body")) {
                        connection.doOutput = true
                        connection.outputStream.use { it.write(spec.getString("body").toByteArray()) }
                    }
                    "HTTP response " + connection.responseCode
                } finally { connection.disconnect() }
            }
            "vars.set","vars.modify" -> {
                val name = value.substringBefore('=')
                val operand = value.substringAfter('=')
                val result = if (id == "vars.set") operand else {
                    val current = values.getString(name,"0")?.toLongOrNull() ?: error("Variable does not contain a number")
                    Math.addExact(current, operand.toLong()).toString()
                }
                values.edit().putString(name,result).apply()
                "Updated $name"
            }
            "phone.dial","phone.call" -> {
                if (id == "phone.call") permission(context, Manifest.permission.CALL_PHONE)
                launch(Intent(if (id == "phone.dial") Intent.ACTION_DIAL else Intent.ACTION_CALL, Uri.fromParts("tel",value,null)), action.title)
            }
            "phone.answer","phone.end" -> {
                if (context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED)
                    error("Grant Phone permission to use this action")
                @Suppress("DEPRECATION")
                val telecom = context.getSystemService(TelecomManager::class.java)
                @Suppress("DEPRECATION")
                if (id == "phone.answer") telecom.acceptRingingCall() else check(telecom.endCall()) { "Android did not end a call" }
                "Call action requested"
            }
            "phone.sms","phone.compose" -> {
                val (number, message) = sms(value)
                if (id == "phone.compose") launch(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto",number,null)).putExtra("sms_body",message), "SMS")
                else {
                    permission(context, Manifest.permission.SEND_SMS)
                    val manager = context.getSystemService(SmsManager::class.java)
                    val parts = manager.divideMessage(message)
                    manager.sendMultipartTextMessage(number, null, parts, null, null)
                    "SMS submitted to the selected SIM"
                }
            }
            else -> error("Unhandled device action: $id")
        }
    }

    private fun permission(context: Context, permission: String) {
        check(context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) { "Grant the action's permission in the picker or system app settings" }
    }

    private suspend fun global(context: Context, id: String, label: String, value: String): String {
        val service = (context as? AccessibilityService) ?: ButtonAccessibilityService.connected ?: error("Enable the accessibility service")
        val key = when (id) {
            "nav.home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "nav.back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "nav.recents","nav.last" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "nav.split" -> AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
            "nav.menu" -> if (Build.VERSION.SDK_INT >= 36) AccessibilityService.GLOBAL_ACTION_MENU else error("Requires Android 16 or later")
            "nav.all_apps" -> if (Build.VERSION.SDK_INT >= 31) AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS else error("Requires Android 12 or later")
            "nav.headset" -> if (Build.VERSION.SDK_INT >= 31) AccessibilityService.GLOBAL_ACTION_KEYCODE_HEADSETHOOK else error("Requires Android 12 or later")
            "a11y.shortcut", "a11y.button", "a11y.choose" -> {
                if (Build.VERSION.SDK_INT < 31) error("Requires Android 12 or later")
                when (id) {
                    "a11y.shortcut" -> AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_SHORTCUT
                    "a11y.button" -> AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_BUTTON
                    else -> AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_BUTTON_CHOOSER
                }
            }
            "nav.dpad" -> {
                if (Build.VERSION.SDK_INT < 33) error("Requires Android 13 or later")
                when (value) {
                    "up" -> AccessibilityService.GLOBAL_ACTION_DPAD_UP
                    "down" -> AccessibilityService.GLOBAL_ACTION_DPAD_DOWN
                    "left" -> AccessibilityService.GLOBAL_ACTION_DPAD_LEFT
                    "right" -> AccessibilityService.GLOBAL_ACTION_DPAD_RIGHT
                    else -> AccessibilityService.GLOBAL_ACTION_DPAD_CENTER
                }
            }
            "device.lock" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            "device.screenshot" -> AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
            "device.power_menu" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            "device.controls" -> return deviceControls(context)
            "shade.quick" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "shade.notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "shade.collapse" -> { check(Build.VERSION.SDK_INT >= 31) { "Requires Android 12 or later" }; AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE }
            else -> error("Unknown navigation action")
        }
        check(service.performGlobalAction(key)) { "Android does not offer $label in the current state" }
        if (id == "nav.last") { delay(120); check(service.performGlobalAction(key)) { "Could not switch from recents" } }
        return "$label requested"
    }

    private fun deviceControls(context: Context): String {
        val intent = Intent(Intent.ACTION_MAIN).setClassName("com.android.systemui", "com.android.systemui.controls.ui.ControlsActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "Device controls requested"
    }

    private fun updateSetting(context: Context, id: String, value: String): String {
        check(Settings.System.canWrite(context)) { "Allow Modify system settings for the remapper" }
        val resolver = context.contentResolver
        fun get(key: String) = Settings.System.getInt(resolver, key, 0)
        fun put(key: String, v: Int) = check(Settings.System.putInt(resolver, key, v)) { "Android rejected the setting" }
        val mode = id.substringAfterLast('.')
        fun flag(key: String) = put(key, when (mode) { "on" -> 1; "off" -> 0; else -> if (get(key) == 0) 1 else 0 })
        when {
            id == "apps.setting" -> check(Settings.System.putString(resolver,value.substringBefore('='),value.substringAfter('='))) { "Android rejected the setting" }
            id.startsWith("display.auto.") -> flag(Settings.System.SCREEN_BRIGHTNESS_MODE)
            id.startsWith("display.rotate.") -> flag(Settings.System.ACCELEROMETER_ROTATION)
            id == "display.brighter" || id == "display.dimmer" -> {
                val amount = (value.toInt() * 255 / 100).coerceAtLeast(1)
                put(Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                put(Settings.System.SCREEN_BRIGHTNESS,(get(Settings.System.SCREEN_BRIGHTNESS) + if (id == "display.brighter") amount else -amount).coerceIn(1,255))
            }
            else -> {
                val orientation = when (id) {
                    "display.portrait" -> 0
                    "display.landscape" -> 1
                    "display.orientation" -> value.toInt()
                    "display.cycle" -> (get(Settings.System.USER_ROTATION) + 1) % 4
                    else -> error("Unknown display action")
                }
                put(Settings.System.ACCELEROMETER_ROTATION,0)
                put(Settings.System.USER_ROTATION,orientation)
            }
        }
        return "Setting updated"
    }
}
