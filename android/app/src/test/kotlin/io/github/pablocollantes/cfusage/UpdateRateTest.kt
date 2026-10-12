package io.github.pablocollantes.cfusage

import io.github.pablocollantes.cfusage.data.UpdateRate
import io.github.pablocollantes.cfusage.data.UpdateRates
import io.github.pablocollantes.cfusage.push.SafetyNetWorker
import io.github.pablocollantes.cfusage.util.FreshnessLevel
import io.github.pablocollantes.cfusage.util.FreshnessRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateRateTest {
    @Test
    fun mobileDataRateAppliesOnlyWhenSetAndMetered() {
        val same = UpdateRate(everyMin = 5, meteredMin = 0)
        assertEquals(300, same.effectiveSeconds(metered = false))
        assertEquals(300, same.effectiveSeconds(metered = true))
        val split = UpdateRate(everyMin = 1, meteredMin = 15)
        assertEquals(60, split.effectiveSeconds(metered = false))
        assertEquals(900, split.effectiveSeconds(metered = true))
    }

    @Test
    fun storedValuesOutsideTheChoicesFallBackToDefaults() {
        assertEquals(UpdateRate(1, 0), UpdateRates.sanitize(UpdateRate(everyMin = 7, meteredMin = 1)))
        assertEquals(UpdateRate(30, 2), UpdateRates.sanitize(UpdateRate(30, 2)))
    }

    @Test
    fun everyChoiceHasADataEstimate() {
        for (m in UpdateRates.choices) assertTrue("no estimate for $m", UpdateRates.monthly(m).startsWith("about "))
        assertEquals("every minute", UpdateRates.every(1))
        assertEquals("every 15 min", UpdateRates.every(15))
    }

    @Test
    fun safetyNetSkipsOnlyWhileTheStreamIsHealthy() {
        // 5-minute rate: a summary 6 min old is on schedule, 8 min old is late.
        assertTrue(SafetyNetWorker.streamHealthy(connected = true, summaryAgeMs = 6 * 60_000L, everySeconds = 300))
        assertFalse(SafetyNetWorker.streamHealthy(connected = true, summaryAgeMs = 8 * 60_000L, everySeconds = 300))
        assertFalse(SafetyNetWorker.streamHealthy(connected = false, summaryAgeMs = 1_000L, everySeconds = 60))
        // A clock that went backwards isn't "fresh".
        assertFalse(SafetyNetWorker.streamHealthy(connected = true, summaryAgeMs = -5_000L, everySeconds = 60))
    }

    @Test
    fun freshnessWidensWithTheRate() {
        val now = 1_000_000_000L
        fun min(m: Int) = now - m * 60_000L
        val every15 = 15 * 60_000L
        // On schedule at a 15-minute rate, stale at the default one.
        assertEquals(FreshnessLevel.LIVE, FreshnessRules.of(now, min(12), true, null, every15).level)
        assertEquals(FreshnessLevel.STALE, FreshnessRules.of(now, min(12), true, null).level)
        assertEquals(FreshnessLevel.RECENT, FreshnessRules.of(now, min(18), true, null, every15).level)
        assertEquals(FreshnessLevel.STALE, FreshnessRules.of(now, min(21), true, null, every15).level)
        assertFalse(FreshnessRules.widgetStale(now, min(19), every15))
        assertTrue(FreshnessRules.widgetStale(now, min(21), every15))
    }
}
