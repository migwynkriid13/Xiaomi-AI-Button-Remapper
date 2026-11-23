package io.github.xiaomibutton.remapper

import android.content.Intent
import android.provider.Settings
import io.github.xiaomibutton.remapper.runtime.PermittedCommands
import java.net.URI

internal val systemPages = linkedMapOf(
    Settings.ACTION_SETTINGS to "Settings home", Settings.ACTION_WIFI_SETTINGS to "Wi-Fi",
    Settings.ACTION_BLUETOOTH_SETTINGS to "Bluetooth", Settings.ACTION_DISPLAY_SETTINGS to "Display",
    Settings.ACTION_SOUND_SETTINGS to "Sound and vibration", Settings.ACTION_ACCESSIBILITY_SETTINGS to "Accessibility",
    Settings.ACTION_APPLICATION_SETTINGS to "Applications", Settings.ACTION_LOCATION_SOURCE_SETTINGS to "Location",
    Settings.ACTION_NFC_SETTINGS to "NFC", Settings.ACTION_SECURITY_SETTINGS to "Security",
    Settings.ACTION_BATTERY_SAVER_SETTINGS to "Battery saver", Settings.ACTION_INTERNAL_STORAGE_SETTINGS to "Storage",
    Settings.ACTION_WIRELESS_SETTINGS to "Wireless connections",
    Settings.ACTION_AIRPLANE_MODE_SETTINGS to "Airplane mode",
    Settings.ACTION_DATA_ROAMING_SETTINGS to "Mobile networks",
    Settings.ACTION_DATA_USAGE_SETTINGS to "Data usage",
    Settings.ACTION_NIGHT_DISPLAY_SETTINGS to "Night Light",
    Settings.ACTION_CAPTIONING_SETTINGS to "Captions",
    Settings.ACTION_CAST_SETTINGS to "Cast",
    Settings.ACTION_DEVICE_INFO_SETTINGS to "About phone",
    Settings.ACTION_DATE_SETTINGS to "Date and time",
    Settings.ACTION_INPUT_METHOD_SETTINGS to "Keyboards",
    Settings.ACTION_HARD_KEYBOARD_SETTINGS to "Physical keyboard",
    Settings.ACTION_LOCALE_SETTINGS to "Languages",
    Settings.ACTION_MEMORY_CARD_SETTINGS to "Memory card",
    Settings.ACTION_PRINT_SETTINGS to "Printing",
    Settings.ACTION_PRIVACY_SETTINGS to "Privacy",
    Settings.ACTION_SEARCH_SETTINGS to "Search",
    Settings.ACTION_SYNC_SETTINGS to "Accounts and sync",
    "android.settings.USER_SETTINGS" to "Users",
    Settings.ACTION_VPN_SETTINGS to "VPN",
    Settings.ACTION_HOME_SETTINGS to "Default home",
    Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS to "Default apps",
    Settings.ACTION_MANAGE_OVERLAY_PERMISSION to "Display over other apps",
    Settings.ACTION_MANAGE_WRITE_SETTINGS to "Modify system settings",
    Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS to "Notification access",
    Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS to "Do Not Disturb access",
    Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS to "Battery optimization",
    Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS to "Developer options",
    Settings.ACTION_FINGERPRINT_ENROLL to "Fingerprint enrollment",
    Settings.ACTION_USAGE_ACCESS_SETTINGS to "Usage access",
    Settings.Panel.ACTION_INTERNET_CONNECTIVITY to "Internet panel",
    Settings.Panel.ACTION_VOLUME to "Volume panel",
    Settings.Panel.ACTION_WIFI to "Wi-Fi panel",
    Settings.Panel.ACTION_NFC to "NFC panel"
)

private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)*")

internal fun validatePackage(value: String) {
    require(value.length <= 255 && value.matches(packagePattern)) { "Invalid application package" }
}

private fun validateIntentUri(value: String) {
    require(value.startsWith("intent:") && value.contains("#Intent;") && value.endsWith(";end")) {
        "Enter an Android intent URI, including its #Intent; section and ;end"
    }
    // Normal parse flags strip URI-grant flags. Intent text is launched only as the app user.
    val intent = Intent.parseUri(value, Intent.URI_INTENT_SCHEME)
    require(!intent.data?.scheme.equals("file", true) &&
        !intent.selector?.data?.scheme.equals("file", true)) { "Use a content URI instead of a file URI" }
}

internal fun validateAction(action: ActionSpec) {
    val limit = if (action.kind == ActionKind.SEQUENCE) SequenceConfig.MAX_LENGTH else 2048
    require(action.value.length <= limit && action.extra.length <= 2048) { "Action is too long" }
    require(action.value.none { it.isISOControl() } && action.extra.none { it.isISOControl() }) {
        "Action contains a control character"
    }
    require(action.kind in setOf(ActionKind.SHORTCUT, ActionKind.CATALOG) || action.extra.isEmpty()) { "Unexpected additional action data" }
    require(action.label.length <= 120 && action.label.none { it.isISOControl() } &&
        (action.kind in setOf(ActionKind.SHORTCUT, ActionKind.CATALOG, ActionKind.QUICK_TILE) || action.label.isEmpty())) { "Invalid action label" }
    when (action.kind) {
        ActionKind.URL -> {
            val uri = URI(action.value)
            require(uri.scheme in listOf("https", "http") && !uri.host.isNullOrBlank()) {
                "Enter a full website address starting with https:// or http://"
            }
        }
        ActionKind.INTENT -> validateIntentUri(action.value)
        ActionKind.SHORTCUT -> if (action.extra.isEmpty()) {
            validateIntentUri(action.value)
        } else {
            validatePackage(action.value)
            require(action.extra.isNotBlank()) { "Shortcut ID is empty" }
        }
        ActionKind.QUICK_TILE -> QuickSettingsTiles.parse(action.value)
        ActionKind.SETTINGS -> require(action.value in systemPages) { "Unrecognized Settings destination" }
        ActionKind.SHELL -> require(PermittedCommands.accepts(action.value)) { "Command is outside the permitted list" }
        ActionKind.SEQUENCE -> SequenceConfig.fromAction(action)
        ActionKind.CATALOG -> ActionLibrary.validate(action.value, action.extra)
        ActionKind.APP -> validatePackage(action.value)
        else -> require(action.value.isEmpty()) { "This action does not accept a value" }
    }
}

