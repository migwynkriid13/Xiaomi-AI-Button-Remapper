package io.github.xiaomibutton.remapper.runtime

/** Fixed Android commands for OS actions that do not need the optional accessibility service. */
internal object ExpertDeviceActions {
    private val keys = mapOf(
        "device.screenshot" to "KEYCODE_SYSRQ",
        "nav.home" to "KEYCODE_HOME",
        "nav.back" to "KEYCODE_BACK",
        "nav.recents" to "KEYCODE_APP_SWITCH",
        "nav.menu" to "KEYCODE_MENU",
        "nav.all_apps" to "KEYCODE_ALL_APPS",
        "nav.headset" to "KEYCODE_HEADSETHOOK",
        "device.lock" to "KEYCODE_SLEEP",
    )
    private val shade = mapOf("shade.notifications" to "expand-notifications",
        "shade.quick" to "expand-settings", "shade.collapse" to "collapse")
    private val directions = mapOf("up" to "KEYCODE_DPAD_UP", "down" to "KEYCODE_DPAD_DOWN",
        "left" to "KEYCODE_DPAD_LEFT", "right" to "KEYCODE_DPAD_RIGHT", "center" to "KEYCODE_DPAD_CENTER")
    val navigation = (keys.keys - "device.screenshot") + shade.keys + "nav.dpad"

    fun run(id: String, value: String, execute: (List<String>) -> String): String {
        if (id == "nav.dpad") return execute(listOf("/system/bin/input", "keyevent", directions.getValue(value)))
        require(value.isEmpty())
        keys[id]?.let { return execute(listOf("/system/bin/input", "keyevent", it)) }
        shade[id]?.let { return execute(listOf("/system/bin/cmd", "statusbar", it)) }
        error("Unknown Shizuku action")
    }
}
