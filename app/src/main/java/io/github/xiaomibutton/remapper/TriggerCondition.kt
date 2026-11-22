package io.github.xiaomibutton.remapper

enum class TriggerCondition(val title: String) {
    ALWAYS("Always"),
    SCREEN_ON("Screen on"),
    SCREEN_OFF("Screen off"),
    UNLOCKED("Device unlocked"),
    LOCKED("Device locked"),
    MEDIA_PLAYING("Media playing"),
    MEDIA_IDLE("Media idle"),
    WIFI_CONNECTED("Wi-Fi is the active connection"),
    WIFI_DISCONNECTED("Wi-Fi is not the active connection"),
    CHARGING("Charging"),
    NOT_CHARGING("Not charging"),
    PORTRAIT("Portrait"),
    LANDSCAPE("Landscape"),
    HEADPHONES_CONNECTED("Headphones connected"),
    HEADPHONES_DISCONNECTED("Headphones disconnected"),
}
