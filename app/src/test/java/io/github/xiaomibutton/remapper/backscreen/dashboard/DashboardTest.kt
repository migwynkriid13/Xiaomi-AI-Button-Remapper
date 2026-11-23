package io.github.xiaomibutton.remapper.backscreen.dashboard

import io.github.xiaomibutton.remapper.ActionKind
import io.github.xiaomibutton.remapper.ActionSpec
import java.io.ByteArrayInputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DashboardTest {
    @Test
    fun reorderKeepsAllCardsAndStopsAtBoundaries() {
        val pages = listOf("Home", "Music", "Apps")
        assertEquals(listOf("Home", "Apps", "Music"), shift(pages, "Apps", -1))
        assertEquals(pages, shift(pages, "Home", -1))
        assertEquals(pages, shift(pages, "Missing", 1))
    }

    @Test
    fun lyricsHandleMultipleTimestampsAndFractionalSeconds() {
        val lines = parseLyrics("[ar:Artist]\n[01:02.03][02:01.5]Hello\n[00:04]Start")
        assertEquals(
            listOf(Lyric(4000, "Start"), Lyric(62030, "Hello"), Lyric(121500, "Hello")),
            lines,
        )
    }

    @Test
    fun feedsSupportNestedArraysWithoutRenderingEntireDocuments() {
        val source = JSONObject("""{"sensors":[{"value":0},{"value":false}]}""")
        assertEquals("0", feedValue(source, "sensors.0.value"))
        assertEquals("false", feedValue(source, "sensors.1.value"))
        assertThrows(IllegalArgumentException::class.java) { feedValue(source, "sensors") }
    }

    @Test
    fun accountWindowsAcceptStatusLineAndScopedFeedFormats() {
        val wrapped =
            JSONObject(
                """{"rate_limits":{"five_hour":{"used_percentage":12,"resets_at":1800000000}}}""",
            )
        assertEquals(RingReading(12f, 1800000000000L), parseUsage(wrapped, "Session"))
        val scoped =
            JSONObject(
                """{"seven_day_sonnet":{"utilization":101,"resets_at":"2027-01-01T00:00:00Z"}}""",
            )
        assertEquals(RingReading(100f, 1798761600000L), parseUsage(scoped, "Scoped weekly"))
        assertThrows(IllegalStateException::class.java) { parseUsage(wrapped, "Weekly") }
    }

    @Test
    fun lockScreenEligibilityRequiresKnownAction() {
        assertTrue(lockEligible(ActionSpec(ActionKind.FLASHLIGHT)))
        assertTrue(lockEligible(ActionSpec(ActionKind.CATALOG, "media.next")))
        assertFalse(lockEligible(ActionSpec(ActionKind.CATALOG, "network.example")))
        assertFalse(lockEligible(ActionSpec(ActionKind.SEQUENCE)))
    }

    @Test
    fun boundedReadsRejectLargeAndAcceptExactLength() {
        assertArrayEquals(
            byteArrayOf(1, 2),
            ByteArrayInputStream(byteArrayOf(1, 2)).limitedBytes(2),
        )
        assertThrows(IllegalArgumentException::class.java) {
            ByteArrayInputStream(ByteArray(5)).limitedBytes(4)
        }
    }
}
