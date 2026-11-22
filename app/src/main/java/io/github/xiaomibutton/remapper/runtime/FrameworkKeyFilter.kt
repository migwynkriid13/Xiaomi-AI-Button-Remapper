package io.github.xiaomibutton.remapper.runtime

import android.view.KeyEvent

internal data class LearnedKey(val descriptor: String, val code: Int, val scan: Int = 0)
internal data class FrameworkKey(
    val deviceId: Int, val descriptor: String, val name: String,
    val external: Boolean, val virtual: Boolean, val scan: Int, val code: Int, val downTime: Long, val platformMax: Int = Int.MAX_VALUE,
) {
    private val physical: Boolean get() = deviceId >= 0 && !external && !virtual && descriptor.isNotBlank()
    private val gpio: Boolean get() = GPIO_NAME.matches(name)
    private val protected: Boolean get() = code in protectedKeys || scan in protectedScans

    fun target(learned: LearnedKey?): Boolean = physical && !protected &&
        (scan == 689 && gpio || learned != null && candidate() == learned)

    fun samePhysicalKey(other: FrameworkKey): Boolean = deviceId == other.deviceId &&
        descriptor == other.descriptor && scan == other.scan && code == other.code && other.physical

    fun candidate(): LearnedKey? = if (physical && !protected && (scan == 689 || scan == 0 && learnable(code, platformMax)))
        LearnedKey(descriptor, code, scan) else null

    companion object {
        private val GPIO_NAME = Regex("gpio-keys(?:[. _:-].*)?", RegexOption.IGNORE_CASE)
        private val protectedScans = setOf(113, 114, 115, 116, 142, 143, 152, 356)
        private val protectedKeys = setOf(KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_MUTE,
            KeyEvent.KEYCODE_SLEEP, KeyEvent.KEYCODE_WAKEUP, KeyEvent.KEYCODE_TV_POWER,
            KeyEvent.KEYCODE_STB_POWER, KeyEvent.KEYCODE_AVR_POWER, KeyEvent.KEYCODE_SOFT_SLEEP)
        fun learnable(code: Int, platformMax: Int): Boolean = code == KeyEvent.KEYCODE_ASSIST ||
            code == KeyEvent.KEYCODE_VOICE_ASSIST || code == KeyEvent.KEYCODE_CAMERA ||
            code in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 || code in KeyEvent.KEYCODE_F13..KeyEvent.KEYCODE_F24 ||
            code in KeyEvent.KEYCODE_MACRO_1..KeyEvent.KEYCODE_MACRO_4 || (code > platformMax && code <= 0xffff)
    }
}

/** Consume the matching repeat/release only if this service consumed its DOWN. */
internal class FrameworkKeyFilter {
    private var held: FrameworkKey? = null
    val hasHeld: Boolean get() = held != null

    fun consume(key: FrameworkKey, value: Int, enabled: Boolean, target: Boolean): Boolean {
        if (value != 1 && held?.samePhysicalKey(key) == true) {
            if (value == 0) held = null
            return true
        }
        if (value == 1 && target) {
            held = if (enabled) key else null
            return enabled
        }
        return false
    }

    fun reset() { held = null }
}

internal class TwoPressCalibration {
    private var down: FrameworkKey? = null
    private var first: LearnedKey? = null
    var candidate: LearnedKey? = null
        private set
    fun key(key: FrameworkKey, value: Int, canceled: Boolean) {
        val choice = key.candidate() ?: return
        if (candidate != null) return
        if (canceled) { down = null; first = null; return }
        if (value == 1) down = key
        if (value == 0 && down?.samePhysicalKey(key) == true) {
            down = null
            if (first == choice) candidate = choice else first = choice
        }
    }
}
