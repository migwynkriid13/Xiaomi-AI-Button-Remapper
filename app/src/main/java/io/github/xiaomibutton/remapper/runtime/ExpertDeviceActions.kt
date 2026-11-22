package io.github.xiaomibutton.remapper.runtime

/** Maps a closed set of device controls to Android shell tool argument arrays. No shell parsing. */
internal object ExpertDeviceActions {
    private val caps = setOf("radio.wifi","radio.bluetooth","radio.data","radio.airplane","radio.nfc","display.night")
    fun run(id: String, value: String, user: String, execute: (List<String>) -> String): String {
        require(user.toIntOrNull() != null)
        fun command(tool: String, vararg args: String) = execute(listOf("/system/bin/$tool") + args)
        if (id == "device.reboot" || id == "device.shutdown") {
            require(value == "confirmed")
            return command("svc", "power", if (id == "device.reboot") "reboot" else "shutdown")
        }
        if (id == "apps.stop" || id == "apps.clear_recent") {
            require(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)*").matches(value) && value.length <= 255)
            if (id == "apps.clear_recent") {
                val list = command("am", "stack", "list")
                if (list.startsWith("Error:")) return list
                val tasks = tasksForPackage(list, value, user)
                if (tasks.isEmpty()) return "No recent task found for this app in your profile"
                for (task in tasks) {
                    val result = command("am", "stack", "remove", task)
                    if (result.startsWith("Error:")) return result
                }
                return "Removed ${tasks.size} recent tasks"
            }
            return command("am","force-stop","--user",user,value)
        }
        require(value.isEmpty())
        if (id == "device.power" || id == "nav.menu")
            return command("input","keyevent",if (id == "device.power") "KEYCODE_POWER" else "KEYCODE_MENU")
        val key = id.substringBeforeLast('.')
        require(key in caps)
        val enabled = when (id.substringAfterLast('.')) {
            "on" -> true
            "off" -> false
            "toggle" -> {
                val current = when (key) {
                    "radio.nfc" -> command("cmd","nfc","status")
                    "radio.wifi" -> command("cmd","wifi","status").lineSequence().firstOrNull().orEmpty()
                    "display.night" -> command("settings","--user",user,"get","secure","night_display_activated")
                    else -> command("settings","get","global", when (key) {
                        "radio.data" -> "mobile_data"
                        "radio.airplane" -> "airplane_mode_on"
                        else -> "bluetooth_on"
                    })
                }.trim().lowercase()
                when (current) {
                    "1","wifi is enabled","nfc is enabled" -> false
                    "0","wifi is disabled","nfc is disabled" -> true
                    else -> return "Error: unable to determine the current state on this firmware"
                }
            }
            else -> error("Unknown operation")
        }
        val mode = if (enabled) "enable" else "disable"
        return when (key) {
            "radio.airplane" -> command("cmd","connectivity","airplane-mode",mode)
            "radio.nfc" -> command("cmd","nfc","$mode-nfc")
            "display.night" -> command("settings","--user",user,"put","secure","night_display_activated",if (enabled) "1" else "0")
            else -> command("svc",key.substringAfter('.'),mode)
        }
    }

    internal fun tasksForPackage(list: String, pkg: String, user: String): List<String> {
        val task = Regex("""^\s*taskId=(\d+):\s+${Regex.escape(pkg)}/\S+\s+.*\buserId=${Regex.escape(user)}\b.*$""")
        return list.lineSequence().mapNotNull { task.matchEntire(it)?.groupValues?.get(1) }.distinct().toList()
    }
}
