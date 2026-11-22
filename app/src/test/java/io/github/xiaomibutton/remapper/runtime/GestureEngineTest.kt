package io.github.xiaomibutton.remapper.runtime

import io.github.xiaomibutton.remapper.GestureSlot
import org.junit.Assert.*
import org.junit.Test

class GestureEngineTest {
    private val output = mutableListOf<GestureSlot>()
    private fun engine(double: Boolean = true, triple: Boolean = false, long: Boolean = true) =
        GestureEngine(300, 600, 25, double, triple, long, output::add)

    @Test fun cameraSingleDispatchesImmediatelyWhenNoMultiMappingExists() {
        val g = engine(double = false)
        g.key(0, 0)
        g.key(1, 10)
        g.key(1, 20)
        g.key(2, 25)
        g.key(0, 40)
        g.key(0, 41)
        assertEquals(listOf(GestureSlot.SINGLE), output)
        assertNull(g.nextDeadline)
    }

    @Test fun tapWaitsForDoubleWindowAndThenFiresExactlyOnce() {
        val g = engine()
        g.key(1, 0)
        g.key(0, 50)
        g.advance(349)
        assertTrue(output.isEmpty())
        g.advance(350)
        g.advance(700)
        assertEquals(listOf(GestureSlot.SINGLE), output)
    }

    @Test fun bounceIsIgnoredAndASecondRealPressFinishesDouble() {
        val g = engine()
        g.key(1, 0)
        g.key(0, 50)
        g.key(1, 55)
        g.key(0, 70)
        g.key(1, 100)
        g.key(0, 130)
        assertEquals(listOf(GestureSlot.DOUBLE), output)
    }

    @Test fun holdsFireAtThresholdAndConsumeReleaseAndRepeats() {
        val g = engine()
        g.key(1, 1000)
        g.advance(1599)
        assertTrue(output.isEmpty())
        g.advance(1600)
        g.key(2, 1700)
        g.key(0, 1800)
        assertEquals(listOf(GestureSlot.LONG), output)
    }

    @Test fun tripleEnabledWaitsForThirdPress() {
        val g = engine(triple = true)
        for (t in listOf(0L, 100L, 200L)) {
            g.key(1, t)
            g.key(0, t + 30)
        }
        assertEquals(listOf(GestureSlot.TRIPLE), output)
    }

    @Test fun resetOnDisconnectDropsOutstandingHoldsAndTaps() {
        val g = engine()
        g.key(1, 0)
        g.key(0, 50)
        g.key(1, 100)
        g.reset()
        g.key(0, 1000)
        g.advance(2000)
        assertTrue(output.isEmpty())
        assertNull(g.nextDeadline)
    }

    @Test fun releaseAfterLongThresholdWinsEvenWhenTimerDelayed() {
        val g = engine()
        g.key(1, 0)
        g.key(0, 50)
        g.key(1, 100)
        g.key(0, 800)
        assertEquals(listOf(GestureSlot.SINGLE, GestureSlot.LONG), output)
    }

    @Test fun permittedCommandsMatchWholeTokensOnly() {
        assertTrue(PermittedCommands.accepts("input keyevent KEYCODE_MEDIA_NEXT"))
        assertFalse(PermittedCommands.accepts("input keyevent KEYCODE_MEDIA_NEXT; id"))
        assertFalse(PermittedCommands.accepts("getprop"))
    }
}
