package io.github.xiaomibutton.remapper.runtime

/** Literal command choices. Privileged execution receives argv and never invokes sh -c. */
object PermittedCommands {
    val options = mapOf(
        "input keyevent KEYCODE_MEDIA_PLAY_PAUSE" to "Play or pause media",
        "input keyevent KEYCODE_MEDIA_NEXT" to "Next track",
        "input keyevent KEYCODE_MEDIA_PREVIOUS" to "Previous track",
        "input keyevent KEYCODE_VOLUME_MUTE" to "Mute media volume",
        "input keyevent KEYCODE_VOLUME_UP" to "Raise media volume",
        "input keyevent KEYCODE_VOLUME_DOWN" to "Lower media volume",
        "input keyevent KEYCODE_HOME" to "Home screen",
        "input keyevent KEYCODE_BACK" to "Go back",
        "getprop ro.product.model" to "Read device model",
        "getprop ro.build.version.release" to "Read Android version",
    )

    fun accepts(command: String): Boolean = options.containsKey(command)
}
