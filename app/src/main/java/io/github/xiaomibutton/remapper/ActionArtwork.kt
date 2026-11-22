package io.github.xiaomibutton.remapper

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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

internal fun glyphFor(kind: ActionKind?, catalog: CatalogAction? = null): Int = when (kind) {
    ActionKind.CAMERA -> R.drawable.action_photo_camera
    ActionKind.FLASHLIGHT -> R.drawable.action_flash_on
    ActionKind.APP -> R.drawable.action_apps
    ActionKind.SHORTCUT -> R.drawable.action_open_in_new
    ActionKind.SILENT -> R.drawable.action_volume_up
    ActionKind.DND -> R.drawable.action_do_not_disturb_on
    ActionKind.URL -> R.drawable.action_link
    ActionKind.INTENT, ActionKind.SHELL -> R.drawable.action_code
    ActionKind.SETTINGS -> R.drawable.action_settings
    ActionKind.NONE -> R.drawable.action_block
    ActionKind.SEQUENCE -> R.drawable.action_tune
    else -> {
        val title = catalog?.title.orEmpty().lowercase()
        val group = catalog?.group.orEmpty().lowercase()
        themedIcons.firstOrNull { it.first in title }?.second
            ?: themedIcons.firstOrNull { it.first in group }?.second ?: R.drawable.action_tune
    }
}

@Composable
internal fun ActionGlyph(resource: Int) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center) {
        Icon(painterResource(resource), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
internal fun MappedActionIcon(action: ActionSpec, cache: PickerIconCache) {
    val catalog = if (action.kind == ActionKind.CATALOG) ActionLibrary.find(action.value) else null
    val source = when {
        action.kind == ActionKind.APP -> PickerIconSource.Package(action.value)
        action.kind == ActionKind.SHORTCUT && action.extra.isEmpty() -> PickerIconSource.Legacy(action.value)
        action.kind == ActionKind.SHORTCUT -> PickerIconSource.Hosted(action.value, action.extra)
        catalog?.parameter == ActionParameter.APP || catalog?.parameter == ActionParameter.APP_NUMBER ->
            PickerIconSource.Package(action.extra.substringBefore('|'))
        else -> null
    }
    if (source != null) PickerIcon(source, cache) else ActionGlyph(glyphFor(action.kind, catalog))
}
