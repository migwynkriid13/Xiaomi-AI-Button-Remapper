package io.github.xiaomibutton.remapper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityManager
import io.github.xiaomibutton.remapper.runtime.ButtonAccessibilityService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** All strokes are expressed relative to the primary display and sent only with touch exploration on. */
internal object TalkBackActions {
    private data class Motion(val fingers: Int, val taps: Int = 1, val hold: Boolean = false, val directions: List<String> = emptyList()) {
        val label: String get() {
            val gesture = if (directions.isNotEmpty()) "swipe " + directions.joinToString(" then ")
                else listOf("tap", "double tap", "triple tap")[taps - 1] + if (hold) " and hold" else ""
            return "$fingers-finger $gesture"
        }
    }

    private val motions = buildList {
        add(Motion(1, taps = 2))
        val compass = listOf("up", "down", "left", "right")
        for (finger in listOf(1, 3, 4))
            for (direction in if (finger == 3) compass.take(2) else compass) add(Motion(finger, directions = listOf(direction)))
        for (turn in listOf("up down","down up","left right","right left","right up"))
            add(Motion(1, directions = turn.split(' ')))
        for (count in 1..3) {
            add(Motion(2, taps = count))
            if (count > 1) add(Motion(2, taps = count, hold = true))
        }
        add(Motion(3)); add(Motion(3, hold = true)); add(Motion(3, taps = 3, hold = true))
        add(Motion(4)); add(Motion(4, taps = 2))
    }.associateBy { it.label }
    val entry = CatalogAction("input.talkback", "Perform a TalkBack gesture", "Input", ActionParameter.CHOICE,
        "Accessibility · touch exploration", choices = motions.keys.map { it to it })

    @Suppress("DEPRECATION")
    suspend fun perform(context: Context, value: String): String {
        val service = checkNotNull(ButtonAccessibilityService.connected) { "Enable the button accessibility service" }
        check(context.getSystemService(AccessibilityManager::class.java).isTouchExplorationEnabled) { "Enable TalkBack touch exploration first" }
        val display = checkNotNull(context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY))
        val size = Point().also(display::getRealSize)
        val motion = motions.getValue(value)
        val unit = minOf(size.x, size.y) * 0.18f
        val gesture = GestureDescription.Builder()
        for (tap in 0 until motion.taps) for (finger in 0 until motion.fingers) {
            var x = size.x * 0.5f + (finger - (motion.fingers - 1) / 2f) * unit * 0.36f
            var y = size.y * 0.5f
            val path = Path().apply { moveTo(x, y) }
            for (direction in motion.directions) {
                when (direction) {
                    "left" -> x -= unit
                    "right" -> x += unit
                    "up" -> y -= unit
                    "down" -> y += unit
                }
                path.lineTo(x, y)
            }
            val duration = when {
                motion.directions.isNotEmpty() -> 140L * motion.directions.size
                motion.hold && tap == motion.taps - 1 -> ViewConfiguration.getLongPressTimeout().toLong() + 160
                else -> 55L
            }
            gesture.addStroke(GestureDescription.StrokeDescription(path, tap * 145L, duration))
        }
        val finished = CompletableDeferred<Boolean>()
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) { finished.complete(true) }
            override fun onCancelled(gestureDescription: GestureDescription) { finished.complete(false) }
        }
        check(service.dispatchGesture(gesture.build(), callback, null) && withTimeoutOrNull(4000) { finished.await() } == true) {
            "Android cancelled the TalkBack gesture"
        }
        return "Sent $value to touch exploration"
    }
}
