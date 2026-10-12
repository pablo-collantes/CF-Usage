package io.github.pablocollantes.cfusage

import io.github.pablocollantes.cfusage.util.Fmt
import io.github.pablocollantes.cfusage.util.flagEmoji
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test
    fun compact() {
        assertEquals("0", Fmt.compact(0.0))
        assertEquals("950", Fmt.compact(950.0))
        assertEquals("9.4k", Fmt.compact(9_400.0))
        assertEquals("127k", Fmt.compact(127_392.0))
        assertEquals("504k", Fmt.compact(503_634.3))
        assertEquals("12.3k", Fmt.compact(12_340.0))
        assertEquals("1.2M", Fmt.compact(1_200_000.0))
        assertEquals("10M", Fmt.compact(10_000_000.0))
        assertEquals("25B", Fmt.compact(25e9))
        assertEquals("214M", Fmt.compact(213_911_623.0))
        assertEquals("0.5", Fmt.compact(0.5))
        assertEquals("2.5", Fmt.compact(2.5))
        assertEquals("-1.5k", Fmt.compact(-1_500.0))
    }

    @Test
    fun percent() {
        assertEquals("0%", Fmt.pct(0.0))
        assertEquals("<0.1%", Fmt.pct(0.0004))
        assertEquals("1.3%", Fmt.pct(0.0127))
        assertEquals("5%", Fmt.pct(0.05))
        assertEquals("42%", Fmt.pct(0.42))
        assertEquals("184%", Fmt.pct(1.84))
    }

    @Test
    fun units() {
        assertEquals("504k ms", Fmt.unit(503_634.3, "ms"))
        assertEquals("6.1k GB-s", Fmt.unit(6_142.8, "gbs"))
        assertEquals("0.40 GB", Fmt.unit(0.3986, "gb"))
        assertEquals("4.92 GB", Fmt.unit(4.917, "gb"))
        assertEquals("5 MB", Fmt.unit(0.005, "gb"))
        assertEquals("1.3M", Fmt.unit(1_273_920.0, "count"))
        assertEquals("920.0 MB", Fmt.bytes(920e6))
        assertEquals("5.96 ms", Fmt.ms(5.96))
        assertEquals("42.5 ms", Fmt.ms(42.5))
        assertEquals("0.25 ms", Fmt.ms(0.25))
        assertEquals("341k ms", Fmt.ms(340_711.0))
    }

    @Test
    fun money() {
        assertEquals("$0.00", Fmt.usd(0.0))
        assertEquals("<$0.01", Fmt.usd(0.004))
        assertEquals("$5.42", Fmt.usd(5.42))
        assertEquals("$1.2k", Fmt.usd(1_234.0))
    }

    @Test
    fun ages() {
        assertEquals("just now", Fmt.ago(10_000))
        assertEquals("3 min ago", Fmt.ago(3 * 60_000))
        assertEquals("2 h ago", Fmt.ago(2 * 3_600_000 + 60_000))
    }

    @Test
    fun flags() {
        assertEquals("🇺🇸", flagEmoji("US"))
        assertEquals("🇩🇪", flagEmoji("de"))
        assertEquals("🏳", flagEmoji("XX1"))
    }
}
