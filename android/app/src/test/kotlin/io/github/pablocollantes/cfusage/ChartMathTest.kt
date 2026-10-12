package io.github.pablocollantes.cfusage

import io.github.pablocollantes.cfusage.ui.charts.ChartMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartMathTest {
    @Test
    fun downsampleKeepsTotalsAndSize() {
        val hourly = List(168) { (it % 24).toDouble() }
        val d = ChartMath.downsampleSum(hourly, 28)
        assertEquals(28, d.size)
        assertEquals(hourly.sum(), d.sum(), 1e-9)
        // 168 / 28 = 6 hours per point: the first point is hours 0..5.
        assertEquals(0.0 + 1 + 2 + 3 + 4 + 5, d[0], 1e-9)
    }

    @Test
    fun downsampleUnevenAndShort() {
        val v = List(10) { 1.0 }
        val d = ChartMath.downsampleSum(v, 3)
        assertEquals(3, d.size)
        assertEquals(10.0, d.sum(), 1e-9)
        assertEquals(v, ChartMath.downsampleSum(v, 24))
        assertEquals(emptyList<Double>(), ChartMath.downsampleSum(emptyList(), 5))
    }

    @Test
    fun bucketStartsMatchDownsample() {
        // 168 hourly points into 48 bars: bucket b starts at ceil(b * 3.5).
        val starts = ChartMath.bucketStarts(168, 48)
        assertEquals(48, starts.size)
        assertEquals(listOf(0, 4, 7, 11), starts.take(4))
        // Each start really is the first index downsampleSum puts in that bucket.
        for ((b, i) in starts.withIndex()) {
            assertEquals(b, ((i.toLong() * 48) / 168).toInt())
            if (i > 0) assertEquals(b - 1, (((i - 1).toLong() * 48) / 168).toInt())
        }
        assertEquals(listOf(0, 1, 2), ChartMath.bucketStarts(3, 48))
    }

    @Test
    fun niceTicks() {
        val t = ChartMath.niceTicks(9_400.0)
        assertEquals(2_500.0, t.step, 0.0)
        assertEquals(10_000.0, t.max, 0.0)
        assertEquals(listOf(0.0, 2_500.0, 5_000.0, 7_500.0, 10_000.0), t.values)
        assertTrue(ChartMath.niceTicks(1.0).max >= 1.0)
        assertEquals(1.0, ChartMath.niceTicks(0.0).max, 0.0)
        val big = ChartMath.niceTicks(1.27e6)
        assertTrue(big.max >= 1.27e6 && big.max <= 2e6)
    }

    @Test
    fun monotoneNeverOvershoots() {
        val xs = floatArrayOf(0f, 1f, 2f, 3f, 4f)
        val ys = floatArrayOf(0f, 0f, 10f, 10f, 0f)
        val m = ChartMath.monotoneTangents(xs, ys)
        // Flat segments get zero slopes at their ends, so the curve can't dip below or rise above them.
        assertEquals(0f, m[0], 0f)
        assertEquals(0f, m[1], 0f)
        assertEquals(0f, m[3], 0f)
        for (i in 0 until xs.size - 1) {
            val dx = (xs[i + 1] - xs[i]) / 3f
            val c1 = ys[i] + m[i] * dx
            val c2 = ys[i + 1] - m[i + 1] * dx
            val lo = minOf(ys[i], ys[i + 1])
            val hi = maxOf(ys[i], ys[i + 1])
            assertTrue(c1 in lo..hi && c2 in lo..hi)
        }
    }

    @Test
    fun indices() {
        assertEquals(0, ChartMath.nearestIndex(-5f, 100f, 11))
        assertEquals(5, ChartMath.nearestIndex(52f, 100f, 11))
        assertEquals(10, ChartMath.nearestIndex(500f, 100f, 11))
        assertEquals(3, ChartMath.barIndex(35f, 100f, 10))
        assertEquals(listOf(0, 7, 15, 23), ChartMath.labelIndices(24, 4))
        assertEquals(listOf(0, 1), ChartMath.labelIndices(2, 4))
    }
}
