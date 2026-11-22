package io.github.xiaomibutton.remapper.runtime

import io.github.xiaomibutton.remapper.GestureSlot

/** A single-threaded recognizer. All times use the same monotonic uptime clock. */
internal class GestureEngine(
    private val interval: Long,
    private val longThreshold: Long,
    private val debounce: Long,
    private val doubleEnabled: Boolean,
    private val tripleEnabled: Boolean,
    private val longEnabled: Boolean,
    private val fire: (GestureSlot) -> Unit,
) {
    private var pressedAt: Long? = null
    private var lastUp: Long? = null
    private var tapDeadline: Long? = null
    private var taps = 0
    private var held = false
    private var bouncing = false

    val inProgress: Boolean get() = pressedAt != null || tapDeadline != null || bouncing

    val nextDeadline: Long?
        get() = if (pressedAt != null && !held && longEnabled) pressedAt!! + longThreshold else tapDeadline

    fun reset() {
        pressedAt = null
        lastUp = null
        tapDeadline = null
        taps = 0
        held = false
        bouncing = false
    }

    fun key(value: Int, time: Long) {
        advance(time)
        when (value) {
            1 -> {
                if (pressedAt != null || bouncing) return
                if (lastUp?.let { time - it < debounce } == true) {
                    bouncing = true
                    return
                }
                pressedAt = time
                tapDeadline = null
                held = false
            }
            0 -> {
                if (bouncing) {
                    bouncing = false
                    return
                }
                val start = pressedAt ?: return
                if (time < start) return
                pressedAt = null
                lastUp = time
                if (held) return
                taps++
                val maximum = if (tripleEnabled) 3 else if (doubleEnabled) 2 else 1
                if (taps == maximum) finishTaps() else tapDeadline = time + interval
            }
            // Kernel repeat (2), unknown values and duplicate transitions never add a tap.
        }
    }

    fun advance(time: Long) {
        val start = pressedAt
        if (longEnabled && !held && start != null && time - start >= longThreshold) {
            held = true
            finishTaps()
            fire(GestureSlot.LONG)
        }
        if (tapDeadline?.let { time >= it } == true) finishTaps()
    }

    private fun finishTaps() {
        val count = taps
        taps = 0
        tapDeadline = null
        when (count) {
            1 -> fire(GestureSlot.SINGLE)
            2 -> fire(GestureSlot.DOUBLE)
            3 -> fire(GestureSlot.TRIPLE)
        }
    }
}
