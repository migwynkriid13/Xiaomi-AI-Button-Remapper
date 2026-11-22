package io.github.xiaomibutton.remapper

import org.junit.Assert.*
import org.junit.Test

class MediaActionsTest {
    @Test fun everyTransportHasGlobalAndPackageScopedVariants() {
        val actions = MediaActions.actions.associateBy { it.id }
        assertEquals(actions.size, MediaActions.actions.size)
        for (command in listOf("toggle", "play", "pause", "next", "previous", "forward", "rewind", "stop",
                "step_forward", "step_back")) {
            assertNotNull(actions["media.$command"])
            val scoped = actions.getValue("media.app.$command")
            assertEquals("Notification access", scoped.requirement)
            assertEquals(if (command.startsWith("step_")) ActionParameter.APP_NUMBER else ActionParameter.APP, scoped.parameter)
        }
    }

    @Test fun eachVolumeStreamSupportsVisibleAndQuietAdjustment() {
        val expected = listOf("auto", "music", "ring", "notification", "alarm", "system", "call", "dtmf", "accessibility")
        val values = expected.flatMap { listOf(it, "$it.quiet") }.toSet()
        for (name in listOf("up", "down", "mute", "unmute", "toggle")) {
            val action = MediaActions.actions.single { it.id == "volume.$name" }
            assertEquals(values, action.choices.map { it.first }.toSet())
        }
        for (alias in listOf("increase_stream", "decrease_stream")) {
            val action = MediaActions.actions.single { it.id == "volume.$alias" }
            assertEquals(values - setOf("auto", "auto.quiet"), action.choices.map { it.first }.toSet())
        }
    }

    @Test fun seekIntervalIsValidatedForGlobalAndTargetedPlayback() {
        for ((action, prefix) in listOf("media.step_forward" to "", "media.app.step_back" to "org.example|")) {
            for (valid in listOf("1", "3600")) MediaActions.validate(action, prefix + valid)
            for (invalid in listOf("", "-2", "0", "3601", "1.5", "NaN")) {
                assertThrows(IllegalArgumentException::class.java) { MediaActions.validate(action, prefix + invalid) }
            }
        }
    }

    @Test fun seekingUsesElapsedPlaybackAndClampsToReportedDurationWithoutOverflow() {
        assertEquals(29_500L, MediaActions.seekPosition(25_000, -500, 5, 120_000))
        assertEquals(0L, MediaActions.seekPosition(300, 500, -5, 120_000))
        assertEquals(120_000L, MediaActions.seekPosition(110_000, 500, 60, 120_000))
        assertEquals(Long.MAX_VALUE, MediaActions.seekPosition(Long.MAX_VALUE - 3, 5, 1, 0))
    }
}
