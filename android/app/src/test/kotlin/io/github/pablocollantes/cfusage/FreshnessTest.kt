package io.github.pablocollantes.cfusage

import io.github.pablocollantes.cfusage.util.Backoff
import io.github.pablocollantes.cfusage.util.FreshnessLevel
import io.github.pablocollantes.cfusage.util.FreshnessRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreshnessTest {
    private val now = 1_000_000_000L
    private fun min(m: Int) = now - m * 60_000L

    @Test
    fun levels() {
        assertEquals(FreshnessLevel.LIVE, FreshnessRules.of(now, min(1), true, null).level)
        assertEquals("Live", FreshnessRules.of(now, min(1), true, null).label)
        assertEquals(FreshnessLevel.RECENT, FreshnessRules.of(now, min(5), true, null).level)
        assertEquals("Updated 5 min ago", FreshnessRules.of(now, min(5), true, null).label)
        assertEquals(FreshnessLevel.STALE, FreshnessRules.of(now, min(11), true, null).level)
        assertEquals(FreshnessLevel.DISCONNECTED, FreshnessRules.of(now, min(1), false, null).level)
        assertEquals(FreshnessLevel.NONE, FreshnessRules.of(now, null, true, null).level)
    }

    @Test
    fun pollErrorIsNeverLive() {
        val f = FreshnessRules.of(now, min(1), true, "GraphQL 429")
        assertEquals(FreshnessLevel.RECENT, f.level)
        assertTrue(FreshnessRules.of(now, min(20), true, "GraphQL 429").label.startsWith("Poll failing"))
    }

    @Test
    fun alarmingAndWidgetStale() {
        assertFalse(FreshnessRules.of(now, min(5), true, null).alarming)
        assertTrue(FreshnessRules.of(now, min(5), false, null).alarming)
        assertFalse(FreshnessRules.widgetStale(now, min(10)))
        assertTrue(FreshnessRules.widgetStale(now, min(11)))
        assertTrue(FreshnessRules.widgetStale(now, null))
    }

    @Test
    fun backoffDoublesToCapAndResets() {
        val b = Backoff()
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L), List(8) { b.nextDelayMs() })
        b.reset()
        assertEquals(1_000L, b.nextDelayMs())
    }
}
