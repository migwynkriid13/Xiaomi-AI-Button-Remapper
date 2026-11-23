package io.github.xiaomibutton.remapper

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

private val themedIcons = listOf(
    "flash" to R.drawable.action_flash_on, "camera" to R.drawable.action_photo_camera,
    "bluetooth" to R.drawable.action_bluetooth, "battery" to R.drawable.action_battery_saver,
    "brightness" to R.drawable.action_brightness_6, "rotation lock" to R.drawable.action_screen_lock_rotation,
    "rotation" to R.drawable.action_rotate_90_degrees_ccw, "orientation" to R.drawable.action_rotate_90_degrees_ccw,
    "wake" to R.drawable.action_power_settings_new, "power" to R.drawable.action_power_settings_new,
    "lock" to R.drawable.action_lock, "notification" to R.drawable.action_notifications,
    "keyboard" to R.drawable.action_keyboard, "key code" to R.drawable.action_keyboard,
    "text" to R.drawable.action_content_paste, "clipboard" to R.drawable.action_content_paste,
    "vibrat" to R.drawable.action_vibration, "do not disturb" to R.drawable.action_do_not_disturb_on,
    "ringer" to R.drawable.action_volume_up, "volume" to R.drawable.action_volume_up,
    "media" to R.drawable.action_play_circle, "audio" to R.drawable.action_music_note,
    "sound" to R.drawable.action_music_note, "music" to R.drawable.action_music_note,
    "message" to R.drawable.action_message, "sms" to R.drawable.action_message, "call" to R.drawable.action_call,
    "wi-fi" to R.drawable.action_wifi_tethering, "network" to R.drawable.action_wifi_tethering,
    "data" to R.drawable.action_wifi_tethering, "accessibility" to R.drawable.action_accessibility_new,
    "key mapper" to R.drawable.action_looks_one, "touch" to R.drawable.action_touch_app,
    "swipe" to R.drawable.action_touch_app, "tap" to R.drawable.action_touch_app,
    "clock" to R.drawable.action_alarm, "alarm" to R.drawable.action_alarm,
    "back" to R.drawable.action_arrow_back, "home" to R.drawable.action_home,
    "display" to R.drawable.action_smartphone, "screen" to R.drawable.action_smartphone,
    "app" to R.drawable.action_apps, "setting" to R.drawable.action_settings,
    "mapping" to R.drawable.action_looks_one, "automation" to R.drawable.action_code,
    "variable" to R.drawable.action_code, "gesture" to R.drawable.action_touch_app,
    "input" to R.drawable.action_keyboard, "navigation" to R.drawable.action_home,
    "interface" to R.drawable.action_smartphone
)

private fun catalogIcon(id: String): Int? {
    val normalized = id.replace("media.app.", "media.")
    return when (normalized) {
        "media.toggle" -> R.drawable.action_play_pause
        "media.play" -> R.drawable.action_play_arrow
        "media.pause" -> R.drawable.action_pause
        "media.stop", "media.stop_sound" -> R.drawable.action_stop
        "media.next" -> R.drawable.action_skip_next
        "media.previous" -> R.drawable.action_skip_previous
        "media.forward" -> R.drawable.action_fast_forward
        "media.rewind" -> R.drawable.action_fast_rewind
        "media.step_forward" -> R.drawable.action_redo
        "media.step_back" -> R.drawable.action_replay
        "media.sound" -> R.drawable.action_audiotrack
        "media.ringtone" -> R.drawable.action_ring_volume
        "media.notification_all" -> R.drawable.action_clear_all
        "media.notification_latest" -> R.drawable.action_notifications_off
        "volume.up", "volume.increase_stream", "volume.unmute" -> R.drawable.action_volume_up
        "volume.down", "volume.decrease_stream" -> R.drawable.action_volume_down
        "volume.toggle", "volume.mute" -> R.drawable.action_volume_off
        "volume.mic_mute" -> R.drawable.action_mic_off
        "volume.mic_unmute" -> R.drawable.action_mic
        "volume.panel" -> R.drawable.action_tune
        "nav.home" -> R.drawable.action_home
        "nav.back" -> R.drawable.action_arrow_back
        "nav.recents", "nav.last" -> R.drawable.action_view_carousel
        "nav.split" -> R.drawable.action_view_agenda
        "nav.menu" -> R.drawable.action_menu
        "device.screenshot" -> R.drawable.action_screenshot
        "device.lock", "device.secure_lock" -> R.drawable.action_lock
        "device.power", "device.power_menu" -> R.drawable.action_power_settings_new
        "device.controls" -> R.drawable.action_tune
        "backscreen.quick_switch" -> R.drawable.rear_ic_swap
        "backscreen.wallpaper.toggle" -> R.drawable.action_smartphone
        "apps.details" -> R.drawable.action_info
        "apps.stop" -> R.drawable.action_dangerous
        "apps.clear_recent" -> R.drawable.action_clear_all
        "apps.setting" -> R.drawable.action_settings
        "apps.assistant", "apps.voice" -> R.drawable.action_mic
        "display.brighter" -> R.drawable.action_brightness_high
        "display.dimmer" -> R.drawable.action_brightness_low
        "display.portrait" -> R.drawable.action_screen_lock_portrait
        "display.landscape" -> R.drawable.action_screen_lock_landscape
        "display.orientation", "display.cycle" -> R.drawable.action_screen_rotation
        "shade.collapse" -> R.drawable.action_expand_less
        "flash.on" -> R.drawable.action_flashlight_on
        "flash.off" -> R.drawable.action_flashlight_off
        "flash.strength" -> R.drawable.action_flash_on
        "phone.dial" -> R.drawable.action_dialpad
        "phone.end" -> R.drawable.action_call_end
        "phone.call", "phone.answer" -> R.drawable.action_call
        "phone.sms", "phone.compose" -> R.drawable.action_message
        "keyboard.show", "keyboard.picker", "keyboard.switch" -> R.drawable.action_keyboard
        "keyboard.hide" -> R.drawable.action_keyboard_hide
        "input.paste" -> R.drawable.action_content_paste
        "input.swipe", "input.pinch" -> R.drawable.action_open_with
        "input.tap", "input.element" -> R.drawable.action_touch_app
        "intent.service", "intent.broadcast" -> R.drawable.action_code
        else -> when {
            id.startsWith("display.night.") -> R.drawable.action_nightlight
            id.startsWith("display.auto.") -> R.drawable.action_brightness_auto
            id.startsWith("display.rotate.") -> R.drawable.action_screen_rotation
            id.startsWith("radio.wifi.") -> R.drawable.action_wifi
            id.startsWith("radio.nfc.") -> R.drawable.action_nfc
            id.startsWith("radio.airplane.") -> R.drawable.action_airplanemode_active
            id.startsWith("radio.bluetooth.") -> R.drawable.action_bluetooth
            id.startsWith("radio.data.") -> R.drawable.action_signal_cellular_alt
            id.startsWith("radio.hotspot.") -> R.drawable.action_wifi_tethering
            id.endsWith(".up") -> R.drawable.action_keyboard_arrow_up
            id.endsWith(".down") -> R.drawable.action_keyboard_arrow_down
            id.endsWith(".left") -> R.drawable.action_keyboard_arrow_left
            id.endsWith(".right") -> R.drawable.action_keyboard_arrow_right
            else -> null
        }
    }
}

internal fun glyphFor(kind: ActionKind?, catalog: CatalogAction? = null): Int = when (kind) {
    ActionKind.CAMERA -> R.drawable.action_photo_camera
    ActionKind.FLASHLIGHT -> R.drawable.action_flash_on
    ActionKind.APP -> R.drawable.action_apps
    ActionKind.SHORTCUT -> R.drawable.action_open_in_new
    ActionKind.QUICK_TILE -> R.drawable.action_tune
    ActionKind.SILENT -> R.drawable.action_volume_off
    ActionKind.DND -> R.drawable.action_do_not_disturb_on
    ActionKind.URL -> R.drawable.action_link
    ActionKind.INTENT, ActionKind.SHELL -> R.drawable.action_code
    ActionKind.SETTINGS -> R.drawable.action_settings
    ActionKind.NONE -> R.drawable.action_block
    ActionKind.SEQUENCE -> R.drawable.action_tune
    else -> {
        val title = catalog?.title.orEmpty().lowercase()
        val group = catalog?.group.orEmpty().lowercase()
        catalog?.let { catalogIcon(it.id) } ?: themedIcons.firstOrNull { it.first in title }?.second
            ?: themedIcons.firstOrNull { it.first in group }?.second ?: R.drawable.action_tune
    }
}

@Composable
internal fun ActionGlyph(resource: Int, muted: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.size(40.dp).clip(TileShape)
        .background(if (muted) colors.surfaceContainerLow else colors.primaryContainer),
        contentAlignment = Alignment.Center) {
        Icon(painterResource(resource), null, Modifier.size(21.dp),
            tint = if (muted) colors.onSurfaceVariant else colors.onPrimaryContainer)
    }
}

@Composable
internal fun MappedActionIcon(action: ActionSpec, cache: PickerIconCache) {
    val catalog = if (action.kind == ActionKind.CATALOG) ActionLibrary.find(action.value) else null
    val source = when {
        action.kind == ActionKind.APP -> PickerIconSource.Package(action.value)
        action.kind == ActionKind.SHORTCUT && action.extra.isEmpty() -> PickerIconSource.Legacy(action.value)
        action.kind == ActionKind.SHORTCUT -> PickerIconSource.Hosted(action.value, action.extra)
        action.kind == ActionKind.QUICK_TILE -> runCatching {
            PickerIconSource.ServiceComponent(QuickSettingsTiles.parse(action.value).component)
        }.getOrNull()
        catalog?.parameter == ActionParameter.APP || catalog?.parameter == ActionParameter.APP_NUMBER ->
            PickerIconSource.Package(action.extra.substringBefore('|'))
        else -> null
    }
    if (source != null) PickerIcon(source, cache) else ActionGlyph(glyphFor(action.kind, catalog))
}
