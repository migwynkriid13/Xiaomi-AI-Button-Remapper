package io.github.xiaomibutton.remapper.backscreen.camera

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.fragment.app.Fragment
import io.github.xiaomibutton.remapper.R
import io.github.xiaomibutton.remapper.backscreen.core.BackScreen
import io.github.xiaomibutton.remapper.backscreen.core.KeeperService
import io.github.xiaomibutton.remapper.backscreen.core.LendReason
import io.github.xiaomibutton.remapper.backscreen.core.RearCommands
import io.github.xiaomibutton.remapper.backscreen.core.RearOwner
import io.github.xiaomibutton.remapper.backscreen.core.RearState
import io.github.xiaomibutton.remapper.backscreen.rear.RearHostActivity
import io.github.xiaomibutton.remapper.backscreen.ui.AskNotificationsOnce
import io.github.xiaomibutton.remapper.backscreen.ui.MainSwitchBar
import io.github.xiaomibutton.remapper.backscreen.ui.Refreshable
import io.github.xiaomibutton.remapper.backscreen.ui.needShizuku
import io.github.xiaomibutton.remapper.backscreen.ui.shizukuMissing
import io.github.xiaomibutton.remapper.backscreen.ui.snackbar
import io.github.xiaomibutton.remapper.backscreen.wallpaper.PreviewBackdrop
import io.github.xiaomibutton.remapper.backscreen.wallpaper.WallpaperSettings
import com.google.android.material.snackbar.Snackbar

/**
 * Gesture teaches the swipe over a display-shaped preview. Try it groups direct camera access,
 * wallpaper recovery and exit instructions. Direct access also works with the gesture off.
 */
class CameraFragment : Fragment(R.layout.rear_fragment_camera), Refreshable {

    private lateinit var mainSwitch: MainSwitchBar
    private lateinit var backdrop: PreviewBackdrop
    private lateinit var hint: SwipeHint
    private lateinit var openButton: Button
    private lateinit var wallpaperCard: View

    private val ctx: Context get() = requireContext()

    // Refusing leaves this tab and Xiaomi's back strip to close the camera.
    private val askNotifications = AskNotificationsOnce(this, CameraSettings, R.string.rear_camera_notifications_denied)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        mainSwitch = view.findViewById(R.id.rear_mainSwitch)
        hint = view.findViewById(R.id.rear_swipeHint)
        openButton = view.findViewById(R.id.rear_openButton)
        wallpaperCard = view.findViewById(R.id.rear_wallpaperCard)
        // Nothing is drawn in the clock's colours here, so there's nothing to match.
        backdrop = PreviewBackdrop(
            view.findViewById(R.id.rear_previewFrame), view.findViewById(R.id.rear_preview), view.findViewById(R.id.rear_clock), {},
            view.findViewById(R.id.rear_glow),
        )

        mainSwitch.onCheckedChange = ::switched
        openButton.setOnClickListener { openOrClose() }
        view.findViewById<Button>(R.id.rear_wallpaperButton).setOnClickListener { turnOnWallpaper() }
    }

    override fun onResume() {
        super.onResume()
        if (!isHidden) reloadPreview()
        hint.playing = !isHidden
    }

    override fun onPause() {
        super.onPause()
        hint.playing = false
    }

    /** Another tab is showing, or this one again. */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        hint.playing = !hidden && isResumed
        if (!hidden) reloadPreview()
    }

    override fun onDestroyView() {
        backdrop.release()
        super.onDestroyView()
    }

    override fun refresh() {
        if (view == null) return
        val on = CameraSettings.isEnabled(ctx)
        val wallpaperOn = WallpaperSettings.isEnabled(ctx)
        val open = cameraIsUp()
        mainSwitch.isChecked = on
        mainSwitch.setSummary(
            when {
                !on -> R.string.rear_state_off
                open -> R.string.rear_camera_on_open
                !wallpaperOn -> R.string.rear_camera_on_no_wallpaper
                shizukuMissing -> R.string.rear_state_waiting_shizuku
                else -> R.string.rear_camera_on_ready
            }
        )
        openButton.setText(if (open) R.string.rear_close_camera else R.string.rear_open_camera)
        wallpaperCard.visibility = if (wallpaperOn) View.GONE else View.VISIBLE
        backdrop.refresh()
    }

    private fun cameraIsUp() = RearState.lendFor(ctx, LendReason.CAMERA) != null

    private fun switched(on: Boolean) {
        CameraSettings.setEnabled(ctx, on)
        BackScreen.log(if (on) "Swipe left for Xiaomi Camera on" else "Swipe left for Xiaomi Camera off")
        // The back screen reads swipes only while a section uses them; nothing new to see there.
        RearHostActivity.swipesChanged()
        if (on) askNotifications.ask()
        refresh()
    }

    /** Opens Xiaomi Camera on the back screen, or closes it if it's up. */
    private fun openOrClose() {
        if (cameraIsUp()) {
            KeeperService.start(ctx, KeeperService.ACTION_CLOSE_CAMERA, why = "the Camera tab")
            return
        }
        when {
            !RearCommands.isReady() -> needShizuku()
            RearState.owner(ctx) is RearOwner.Lent -> snackbar(R.string.rear_play_lent, Snackbar.LENGTH_LONG)
            else -> {
                KeeperService.start(ctx, KeeperService.ACTION_OPEN_CAMERA, why = "the Camera tab")
                // Off, it still opens from here, but a swipe won't.
                if (!CameraSettings.isEnabled(ctx)) snackbar(R.string.rear_camera_opened_switch_off, Snackbar.LENGTH_LONG)
            }
        }
    }

    private fun turnOnWallpaper() {
        if (!RearCommands.isReady()) return needShizuku()
        BackScreen.log("Wallpaper on, from the Camera tab")
        KeeperService.setWallpaper(ctx, true)
        refresh()
    }

    /** Shown again: the Wallpaper tab may have changed what's under it. */
    private fun reloadPreview() {
        backdrop.reload()
        refresh()
    }
}
