package io.github.xiaomibutton.remapper.backscreen.notifications

import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.R as AppCompatR
import androidx.fragment.app.Fragment
import io.github.xiaomibutton.remapper.R
import io.github.xiaomibutton.remapper.backscreen.core.BackScreen
import io.github.xiaomibutton.remapper.backscreen.core.KeeperService
import io.github.xiaomibutton.remapper.backscreen.core.RearCommands
import io.github.xiaomibutton.remapper.backscreen.core.RearOwner
import io.github.xiaomibutton.remapper.backscreen.core.RearState
import io.github.xiaomibutton.remapper.backscreen.rear.RearHostActivity
import io.github.xiaomibutton.remapper.backscreen.ui.DISABLED_ALPHA
import io.github.xiaomibutton.remapper.backscreen.ui.MainSwitchBar
import io.github.xiaomibutton.remapper.backscreen.ui.Refreshable
import io.github.xiaomibutton.remapper.backscreen.ui.SwitchRow
import io.github.xiaomibutton.remapper.backscreen.ui.addChoiceChips
import io.github.xiaomibutton.remapper.backscreen.ui.needShizuku
import io.github.xiaomibutton.remapper.backscreen.ui.setChipsEnabled
import io.github.xiaomibutton.remapper.backscreen.ui.shizukuMissing
import io.github.xiaomibutton.remapper.backscreen.ui.snackbar
import io.github.xiaomibutton.remapper.backscreen.wallpaper.ClockLayer
import io.github.xiaomibutton.remapper.backscreen.wallpaper.OverlayColors
import io.github.xiaomibutton.remapper.backscreen.wallpaper.PreviewBackdrop
import io.github.xiaomibutton.remapper.backscreen.wallpaper.WallpaperSettings
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar

/**
 * The Notifications tab: the switch, Notification access, a preview shaped like the back screen
 * with a sample notification in the chosen style and Try on back screen under it, the style,
 * and the options. The style and options grey out while it's off; access can be allowed first.
 */
class NotificationsFragment : Fragment(R.layout.rear_fragment_notifications), Refreshable {

    private lateinit var mainSwitch: MainSwitchBar
    private lateinit var accessIcon: ImageView
    private lateinit var accessText: TextView
    private lateinit var accessButton: Button
    private lateinit var backdrop: PreviewBackdrop
    private lateinit var clock: ClockLayer
    private lateinit var notifications: NotificationLayer
    private lateinit var tryButton: Button
    private lateinit var styleCard: View
    private lateinit var styleChips: ChipGroup
    private lateinit var styleDetail: TextView
    private lateinit var optionsCard: View
    private lateinit var swipeDownRow: View
    private lateinit var swipeDownSwitch: MaterialSwitch
    private lateinit var lightUpRow: View
    private lateinit var lightUpSwitch: MaterialSwitch

    // Shizuku is allowing or removing access, so the button waits.
    private var changingAccess = false

    private val ctx: Context get() = requireContext()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        mainSwitch = view.findViewById(R.id.rear_mainSwitch)
        accessIcon = view.findViewById(R.id.rear_accessIcon)
        accessText = view.findViewById(R.id.rear_accessText)
        accessButton = view.findViewById(R.id.rear_accessButton)
        clock = view.findViewById(R.id.rear_clock)
        notifications = view.findViewById(R.id.rear_notifications)
        tryButton = view.findViewById(R.id.rear_tryButton)
        styleCard = view.findViewById(R.id.rear_styleCard)
        styleChips = view.findViewById(R.id.rear_styleChips)
        styleDetail = view.findViewById(R.id.rear_styleDetail)
        optionsCard = view.findViewById(R.id.rear_optionsCard)
        swipeDownRow = view.findViewById(R.id.rear_swipeDownRow)
        swipeDownSwitch = view.findViewById(R.id.rear_swipeDownSwitch)
        lightUpRow = view.findViewById(R.id.rear_lightUpRow)
        lightUpSwitch = view.findViewById(R.id.rear_lightUpSwitch)
        // The banner takes the clock's colours, as on the back screen.
        backdrop = PreviewBackdrop(
            view.findViewById(R.id.rear_previewFrame), view.findViewById(R.id.rear_preview), clock, ::matchClockColors,
            view.findViewById(R.id.rear_glow),
        )

        mainSwitch.onCheckedChange = ::switched
        accessButton.setOnClickListener { onAccessButton() }
        tryButton.setOnClickListener { tryIt() }

        styleChips.addChoiceChips(NotificationStyle.entries, NotificationSettings.style(ctx), { getString(it.label) }) { chosen ->
            NotificationSettings.setStyle(ctx, chosen)
            BackScreen.log("Notification style: ${chosen.name.lowercase()}")
            RearHostActivity.notificationsChanged()
            showStyle()
        }

        SwitchRow.bind(swipeDownRow, swipeDownSwitch)
        swipeDownSwitch.isChecked = NotificationSettings.swipeDown(ctx)
        swipeDownSwitch.setOnCheckedChangeListener { _, checked ->
            NotificationSettings.setSwipeDown(ctx, checked)
            RearHostActivity.notificationsChanged()
        }
        SwitchRow.bind(lightUpRow, lightUpSwitch)
        lightUpSwitch.isChecked = NotificationSettings.lightUp(ctx)
        lightUpSwitch.setOnCheckedChangeListener { _, checked -> NotificationSettings.setLightUp(ctx, checked) }

        showStyle()
    }

    override fun onResume() {
        super.onResume()
        if (!isHidden) reloadPreview()
    }

    /** Another tab is showing, or this one again. */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) reloadPreview()
    }

    override fun onDestroyView() {
        backdrop.release()
        super.onDestroyView()
    }

    override fun refresh() {
        if (view == null) return
        val on = NotificationSettings.isEnabled(ctx)
        val allowed = NotificationAccess.isGranted(ctx)
        mainSwitch.isChecked = on
        mainSwitch.setSummary(
            when {
                !on -> R.string.rear_state_off
                !allowed -> R.string.rear_notifications_on_no_access
                !WallpaperSettings.isEnabled(ctx) -> R.string.rear_notifications_on_xiaomi
                shizukuMissing -> R.string.rear_state_waiting_shizuku
                else -> R.string.rear_notifications_on_wallpaper
            }
        )
        showAccess(allowed)
        // The style and options are for while it's on.
        for (card in listOf(styleCard, optionsCard)) card.alpha = if (on) 1f else DISABLED_ALPHA
        styleChips.setChipsEnabled(on)
        for (v in listOf(swipeDownRow, swipeDownSwitch, lightUpRow, lightUpSwitch)) v.isEnabled = on
        backdrop.refresh()
    }

    /** Whether access is allowed, and the one button that changes that. */
    private fun showAccess(allowed: Boolean) {
        val shizuku = RearCommands.isReady()
        accessIcon.setImageResource(if (allowed) R.drawable.rear_ic_check_circle else R.drawable.rear_ic_error)
        accessIcon.imageTintList = ColorStateList.valueOf(
            MaterialColors.getColor(accessIcon, if (allowed) AppCompatR.attr.colorPrimary else AppCompatR.attr.colorError)
        )
        accessIcon.contentDescription = getString(if (allowed) R.string.rear_setup_done else R.string.rear_setup_needed_short)
        accessText.setText(
            when {
                allowed -> R.string.rear_access_allowed
                shizuku -> R.string.rear_access_needed
                else -> R.string.rear_access_needed_settings
            }
        )
        accessButton.setText(
            when {
                allowed -> R.string.rear_access_remove
                shizuku -> R.string.rear_allow_access
                else -> R.string.rear_open_settings
            }
        )
        accessButton.isEnabled = !changingAccess
    }

    private fun switched(on: Boolean) {
        NotificationSettings.setEnabled(ctx, on)
        BackScreen.log(if (on) "Notifications on" else "Notifications off")
        RearNotificationListener.switched(ctx, on)
        RearHostActivity.notificationsChanged()
        // One tap: switching it on allows access too, if Shizuku can.
        if (on && !NotificationAccess.isGranted(ctx) && RearCommands.isReady()) allow()
        refresh()
    }

    private fun onAccessButton() {
        val allowed = NotificationAccess.isGranted(ctx)
        when {
            !RearCommands.isReady() -> NotificationAccess.openSettings(ctx)
            allowed -> remove()
            else -> allow()
        }
    }

    private fun allow() = changeAccess(NotificationAccess::grant) { allowed ->
        if (allowed && NotificationSettings.isEnabled(ctx)) RearNotificationListener.switched(ctx, true)
        if (allowed) R.string.rear_access_now_allowed else R.string.rear_access_failed
    }

    private fun remove() = changeAccess(NotificationAccess::remove) { allowed ->
        if (allowed) R.string.rear_access_failed else R.string.rear_access_now_removed
    }

    /** Runs [change] through Shizuku, then says how it went: [result] gives the message. */
    private fun changeAccess(change: (Context, (Boolean) -> Unit) -> Unit, result: (Boolean) -> Int) {
        changingAccess = true
        refresh()
        change(ctx) { allowed ->
            changingAccess = false
            if (view == null) return@change
            snackbar(result(allowed))
            refresh()
        }
    }

    /** Shows it in the preview from the start, and on the back screen where it would show now. */
    private fun tryIt() {
        notifications.hideBanner(animate = false)
        showStyle()
        val message = when {
            !NotificationSettings.isEnabled(ctx) -> R.string.rear_try_preview_only
            !WallpaperSettings.isEnabled(ctx) -> R.string.rear_try_wallpaper_off
            RearState.owner(ctx) is RearOwner.Lent -> R.string.rear_play_lent
            else -> {
                KeeperService.start(ctx, KeeperService.ACTION_TRY_NOTIFICATION)
                if (!RearCommands.isReady()) needShizuku()
                null
            }
        }
        message?.let { snackbar(it, Snackbar.LENGTH_LONG) }
    }

    /** The sample in the preview, in the chosen style, and what that style shows. */
    private fun showStyle() {
        val style = NotificationSettings.style(ctx)
        styleDetail.setText(style.summary)
        NotificationFormatter.format(SampleNotification.make(ctx), style, locked = false, LockScreen())?.let {
            matchClockColors()
            notifications.showBanner(it, durationMs = 0)
        }
    }

    /** Shown again: the Wallpaper tab may have changed what's under it. */
    private fun reloadPreview() {
        backdrop.reload()
        refresh()
    }

    /** The clock's colours, so it matches the wallpaper; light on dark without one. */
    private fun matchClockColors() {
        val colors = OverlayColors.of(clock.takeIf { backdrop.clockShown })
        notifications.setColors(colors.text, colors.background)
    }
}
