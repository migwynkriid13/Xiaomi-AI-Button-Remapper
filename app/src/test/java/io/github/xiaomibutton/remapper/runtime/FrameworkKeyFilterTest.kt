package io.github.xiaomibutton.remapper.runtime

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test

class FrameworkKeyFilterTest {
    private val ai = FrameworkKey(6, "gpio-a", "gpio-keys", false, false, 689, 0, 100, 337)

    @Test fun exactPhysicalScanMatchesWithoutAndroidKeyCode() {
        assertTrue(ai.target(null))
        assertFalse(ai.copy(external = true).target(null))
        assertFalse(ai.copy(virtual = true).target(null))
        assertFalse(ai.copy(name = "Virtual keyboard").target(null))
        assertFalse(ai.copy(scan = 115, code = KeyEvent.KEYCODE_VOLUME_UP).target(null))
        assertFalse(ai.copy(code = KeyEvent.KEYCODE_POWER).target(null))
    }

    @Test fun observationNeverConsumesAndAnUnmatchedReleasePasses() {
        val filter = FrameworkKeyFilter()
        assertFalse(filter.consume(ai, 1, false, true))
        assertFalse(filter.consume(ai, 2, true, true))
        assertFalse(filter.consume(ai, 0, true, true))
    }

    @Test fun consumedDownAlsoConsumesRepeatsAndUpAfterDisable() {
        val filter = FrameworkKeyFilter()
        assertTrue(filter.consume(ai, 1, true, true))
        assertFalse(filter.consume(ai.copy(scan = 114, code = KeyEvent.KEYCODE_VOLUME_DOWN), 1, true, false))
        assertTrue(filter.consume(ai.copy(downTime = 130), 2, true, true))
        assertTrue(filter.consume(ai.copy(downTime = 130), 0, false, true))
        assertFalse(filter.hasHeld)
    }

    @Test fun learnedVendorCodeRequiresTwoCompletePressesAndExactDevice() {
        val key = ai.copy(scan = 0, code = 690)
        val learner = TwoPressCalibration()
        learner.key(key, 0, false)
        learner.key(key, 1, false)
        learner.key(key, 2, false)
        learner.key(key, 0, false)
        assertNull(learner.candidate)
        learner.key(key.copy(downTime = 300), 1, false)
        learner.key(key.copy(downTime = 300), 0, false)
        val learned = requireNotNull(learner.candidate)
        assertTrue(key.target(learned))
        assertFalse(key.copy(descriptor = "other").target(learned))
        assertFalse(key.copy(scan = 114).target(learned))
        assertFalse(key.copy(code = 0).target(learned))
        assertNull(ai.copy(scan = 0, code = KeyEvent.KEYCODE_VOLUME_UP).candidate())
        assertNull(ai.copy(scan = 0, code = KeyEvent.KEYCODE_A).candidate())
    }
}
