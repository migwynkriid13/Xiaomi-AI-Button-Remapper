package io.github.xiaomibutton.remapper.backscreen

import android.content.Context
import io.github.xiaomibutton.remapper.backscreen.core.BackScreen
import io.github.xiaomibutton.remapper.backscreen.core.KeeperService
import io.github.xiaomibutton.remapper.backscreen.core.Schedules

/**
 * Restores user-facing rear-display options while keeping chosen wallpaper files available.
 * Runtime state and saved Xiaomi values are deliberately retained until KeeperService puts them
 * back, rather than deleting the only information that can safely undo a live display tweak.
 */
fun resetBackScreenDefaults(context: Context) {
    val app = context.applicationContext
    Schedules.save(app, emptyList())
    USER_SETTING_FILES.forEach { file ->
        check(app.getSharedPreferences(file, Context.MODE_PRIVATE).edit().clear().commit()) {
            "Could not reset $file"
        }
    }
    ToggleWidget.updateAll(app)
    ToggleTile.requestUpdate(app)
    runCatching { KeeperService.start(app, KeeperService.ACTION_UPDATE) }
        .onFailure { BackScreen.log("Couldn't apply reset back screen settings: ${it.message}") }
}

private val USER_SETTING_FILES = listOf(
    "backscreen",
    "notifications",
    "battery",
    "mirror",
    "camera",
    "display",
    "schedules",
)
