package io.github.xiaomibutton.remapper

import android.content.ComponentName

/** A component-backed Quick Settings tile published by Android or an installed application. */
internal data class QuickTileId(val packageName: String, val className: String) {
    val component: ComponentName get() = ComponentName(packageName, className)
    val encoded: String get() = if (className.startsWith("$packageName.")) {
        "$packageName/." + className.removePrefix("$packageName.")
    } else "$packageName/$className"
    val systemSpec: String get() = "custom($encoded)"
}

internal object QuickSettingsTiles {
    const val ACTION = "android.service.quicksettings.action.QS_TILE"
    const val BIND_PERMISSION = "android.permission.BIND_QUICK_SETTINGS_TILE"

    private val className = Regex("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*")

    /** Parses the canonical flattened component form without accepting shell syntax or whitespace. */
    fun parse(value: String): QuickTileId {
        require(value.length in 3..512 && value.none(Char::isISOControl)) { "Invalid Control Centre tile" }
        require(value.count { it == '/' } == 1) { "Invalid Control Centre tile component" }
        val pkg = value.substringBefore('/')
        val rawClass = value.substringAfter('/')
        validatePackage(pkg)
        require(rawClass.isNotBlank() && !rawClass.any(Char::isWhitespace)) { "Invalid Control Centre tile class" }
        val fullClass = if (rawClass.startsWith('.')) pkg + rawClass else rawClass
        require(className.matches(fullClass)) { "Invalid Control Centre tile class" }
        return QuickTileId(pkg, fullClass).also {
            require(it.encoded == value) { "Use the canonical Control Centre tile component" }
        }
    }
}
