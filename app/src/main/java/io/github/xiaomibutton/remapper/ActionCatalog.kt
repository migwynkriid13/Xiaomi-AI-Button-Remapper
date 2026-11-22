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
    Settings.ACTION_WIRELESS_SETTINGS to "Wireless connections"
)

private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)*")

private fun validatePackage(value: String) {
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
    require(action.value.length <= 2048 && action.extra.length <= 2048) { "Action is too long" }
    require(action.value.none { it.isISOControl() } && action.extra.none { it.isISOControl() }) {
        "Action contains a control character"
    }
    require(action.kind == ActionKind.SHORTCUT || action.extra.isEmpty()) { "Unexpected additional action data" }
    require(action.label.length <= 120 && action.label.none { it.isISOControl() } &&
        (action.kind == ActionKind.SHORTCUT || action.label.isEmpty())) { "Invalid shortcut label" }
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
        ActionKind.SETTINGS -> require(action.value in systemPages) { "Unrecognized Settings destination" }
        ActionKind.SHELL -> require(PermittedCommands.accepts(action.value)) { "Command is outside the permitted list" }
        ActionKind.APP -> validatePackage(action.value)
        else -> require(action.value.isEmpty()) { "This action does not accept a value" }
    }
}

