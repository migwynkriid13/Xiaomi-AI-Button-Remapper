package io.github.xiaomibutton.remapper.backscreen.ui

import io.github.xiaomibutton.remapper.R

/** One part of a section's help: a heading and what it says. */
data class HelpSection(val title: Int, val text: Int)

/**
 * The app's five sections, one tab each: its place in the bottom bar, its name, and its help,
 * behind the ? in the top bar.
 */
enum class Feature(
    val navId: Int,
    val label: Int,
    val help: List<HelpSection>,
) {
    WALLPAPER(
        R.id.rear_nav_wallpaper, R.string.rear_tab_wallpaper,
        listOf(
            HelpSection(R.string.rear_help_panning_title, R.string.rear_help_panning),
            HelpSection(R.string.rear_help_display_title, R.string.rear_help_display),
            HelpSection(R.string.rear_help_dimmed_title, R.string.rear_help_dimmed),
        ),
    ),
    NOTIFICATIONS(
        R.id.rear_nav_notifications, R.string.rear_tab_notifications,
        listOf(
            HelpSection(R.string.rear_help_notifications_off_title, R.string.rear_help_notifications_off),
            HelpSection(R.string.rear_help_notifications_locked_title, R.string.rear_help_notifications_locked),
        ),
    ),
    BATTERY(
        R.id.rear_nav_battery, R.string.rear_tab_battery,
        listOf(
            HelpSection(R.string.rear_help_battery_when_title, R.string.rear_help_battery_when),
            HelpSection(R.string.rear_help_battery_glow_title, R.string.rear_help_battery_glow),
            HelpSection(R.string.rear_help_battery_xiaomi_title, R.string.rear_help_battery_xiaomi),
        ),
    ),
    MIRROR(
        R.id.rear_nav_mirror, R.string.rear_tab_mirror,
        listOf(
            HelpSection(R.string.rear_help_mirror_lock_title, R.string.rear_help_mirror_lock),
            HelpSection(R.string.rear_good_to_know, R.string.rear_mirror_limits),
            HelpSection(R.string.rear_help_mirror_uninstall_title, R.string.rear_help_mirror_uninstall),
        ),
    ),
    CAMERA(
        R.id.rear_nav_camera, R.string.rear_tab_camera,
        listOf(
            HelpSection(R.string.rear_camera_close_title, R.string.rear_camera_close),
            HelpSection(R.string.rear_good_to_know, R.string.rear_camera_limits),
        ),
    );

    companion object {
        fun forNavId(id: Int) = entries.firstOrNull { it.navId == id }
    }
}

/** A tab that keeps itself up to date while it's showing; the app calls this every second. */
interface Refreshable {
    fun refresh()
}
