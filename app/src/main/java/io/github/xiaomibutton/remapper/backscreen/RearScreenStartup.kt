package io.github.xiaomibutton.remapper.backscreen

import android.content.Context
import io.github.xiaomibutton.remapper.backscreen.core.BackScreen
import io.github.xiaomibutton.remapper.backscreen.core.KeeperService
import io.github.xiaomibutton.remapper.backscreen.core.RearState
import io.github.xiaomibutton.remapper.backscreen.core.Schedules
import io.github.xiaomibutton.remapper.backscreen.core.isRearScreenPlatform
import io.github.xiaomibutton.remapper.backscreen.wallpaper.Gallery

private var checkedSchedules = false

/** Catch up after a force stop and resume configured rear features from either app entry point. */
internal fun resumeRearScreen(context: Context) {
    if (!isRearScreenPlatform()) return
    if (!checkedSchedules) {
        checkedSchedules = true
        Gallery.migrate(context)
        Schedules.catchUp(context)
    }
    if (KeeperService.instance == null && RearState.keeperNeeded(context)) {
        try {
            KeeperService.start(context, KeeperService.ACTION_RESUME)
        } catch (error: IllegalStateException) {
            BackScreen.log("Couldn't resume the back screen: ${error.message}")
        }
    }
}
