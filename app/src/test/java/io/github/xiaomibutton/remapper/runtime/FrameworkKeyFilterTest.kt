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
        assertTrue(ai.copy(name = "gpio-keys.1").target(null))
        assertFalse(ai.copy(scan = 115, code = KeyEvent.KEYCODE_VOLUME_UP).target(null))
        assertFalse(ai.copy(scan = 116, code = KeyEvent.KEYCODE_F1).target(LearnedKey(ai.descriptor, KeyEvent.KEYCODE_F1, 116)))
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

    @Test fun alternateInternalDeviceWithKnownScanNeedsExplicitLearning() {
        val alternate = ai.copy(name = "Xiaomi AI Button")
        val learning = TwoPressCalibration()
        assertFalse(alternate.target(null))
        repeat(2) {
            val key = alternate.copy(downTime = alternate.downTime + it * 200L)
            learning.key(key, 1, false)
            learning.key(key, 0, false)
        }
        val learned = requireNotNull(learning.candidate)
        assertTrue(alternate.target(learned))
        assertFalse(alternate.copy(scan = 115).target(learned))
        assertFalse(alternate.copy(virtual = true).target(learned))
        assertFalse(alternate.copy(descriptor = "usb-macro").target(learned))
    }

    @Test fun shizukuAuthorizationDoesNotGetMistakenForAccessibility() {
        val shizukuOnly = RuntimeStatus(authorized = true)
        assertFalse(shizukuOnly.backendReady)
        assertTrue(shizukuOnly.copy(inputMethod = InputMethod.SHIZUKU).backendReady)
        val accessibilityOnly = RuntimeStatus(accessibilityConnected = true)
        assertTrue(accessibilityOnly.backendReady)
        assertFalse(accessibilityOnly.copy(inputMethod = InputMethod.SHIZUKU).backendReady)
    }
}
