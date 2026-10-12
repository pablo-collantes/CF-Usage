package io.github.pablocollantes.cfusage

import io.github.pablocollantes.cfusage.data.DatedValue
import io.github.pablocollantes.cfusage.data.Metric
import io.github.pablocollantes.cfusage.util.MetricText
import io.github.pablocollantes.cfusage.widget.ListBlock
import io.github.pablocollantes.cfusage.widget.MetricVisual
import io.github.pablocollantes.cfusage.widget.ValueStyle
import io.github.pablocollantes.cfusage.widget.WidgetText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetricTextTest {
    // The R2 case that prompted the contract change: 4.92 GB stored now, but the bucket only grew
    // on Oct 1, so the cycle's billed average (GB-month) projects to 3.63.
    private val r2 = Metric(
        key = "r2_storage_gb", label = "R2 storage", group = "R2", unit = "gb", kind = "storage",
        used = 4.917, included = 10.0, pct = 0.4917, projected = 3.6285, projectedPct = 0.36285, rate24h = 4.917,
    )
    private val d1w = Metric(
        key = "d1_rows_written", label = "D1 rows written", group = "D1", unit = "count", kind = "flow",
        used = 6_455_015.0, included = 50_000_000.0, pct = 0.1291, projected = 44_869_679.0, projectedPct = 0.897,
        daily = listOf(DatedValue("2026-09-23", 100.0), DatedValue("2026-09-24", 50.0), DatedValue("2026-09-25", 25.0)),
    )
    private val cpu = Metric(
        key = "workers_cpu_ms", label = "Workers CPU time", group = "Workers", unit = "ms", kind = "flow",
        used = 672_084.0, included = 30_000_000.0, pct = 0.0224, projected = 1_429_828.0, projectedPct = 0.0477,
    )

    @Test
    fun storageShowsCurrentSizeAndBilledAverage() {
        assertEquals("4.92 GB / 10 GB", MetricText.ofTotal(r2))
        assertEquals("billed avg 3.63 GB · 36%", MetricText.projection(r2))
        assertEquals("avg 36%", MetricText.projectionPct(r2))
        assertEquals("Billed avg", MetricText.projectionLabel(r2))
    }

    @Test
    fun flowShowsProjection() {
        assertEquals("6.5M / 50M", MetricText.ofTotal(d1w))
        assertEquals("proj. 44.9M · 90%", MetricText.projection(d1w))
        assertEquals("→90%", MetricText.projectionPct(d1w))
        assertEquals("Projected", MetricText.projectionLabel(d1w))
        assertEquals("672k / 30M ms", MetricText.ofTotal(cpu))
    }

    @Test
    fun gigabytes() {
        assertEquals("0 GB", MetricText.gb(0.0))
        assertEquals("<1 MB", MetricText.gb(0.0004))
        assertEquals("347 MB", MetricText.gb(0.3468))
        assertEquals("4.92 GB", MetricText.gb(4.9174))
        assertEquals("5 GB", MetricText.gb(5.0))
        assertEquals("10 GB", MetricText.gb(10.0))
        assertEquals("12.3 GB", MetricText.gb(12.34))
        assertEquals("120 GB", MetricText.gb(120.0))
    }

    @Test
    fun widgetValues() {
        assertEquals("49%", WidgetText.value(r2, ValueStyle.Percent))
        assertEquals("4.92 GB/10 GB", WidgetText.value(r2, ValueStyle.UsedOfTotal))
        assertEquals("6.5M", WidgetText.value(d1w, ValueStyle.Used))
        assertEquals("→90%", WidgetText.value(d1w, ValueStyle.Projected))
        assertEquals("–", WidgetText.value(null, ValueStyle.Percent))
    }

    @Test
    fun listSecondLine() {
        assertEquals("49% · billed avg 3.63 GB · 36%", WidgetText.secondary(r2, ListBlock(id = "l")))
        assertEquals("proj. 44.9M · 90%", WidgetText.secondary(d1w, ListBlock(id = "l", value = ValueStyle.Percent)))
        assertEquals("", WidgetText.secondary(d1w, ListBlock(id = "l", showPercent = false, showProjected = false)))
    }

    @Test
    fun namesDropTheProductOnlyWhenItIsShared() {
        assertEquals(mapOf("d1_rows_read" to "Rows read", "d1_storage_gb" to "Storage"), WidgetText.names(listOf("d1_rows_read", "d1_storage_gb")))
        assertEquals("D1 rows read", WidgetText.names(listOf("d1_rows_read", "r2_storage_gb"))["d1_rows_read"])
    }

    @Test
    fun topTitles() {
        assertEquals("Workers by requests", WidgetText.topTitle("workers_requests"))
        assertEquals("Databases by rows written", WidgetText.topTitle("d1_rows_written"))
        assertEquals("Buckets by storage", WidgetText.topTitle("r2_storage_gb"))
    }

    @Test
    fun chartValues() {
        assertEquals(listOf(100.0, 150.0, 175.0), WidgetText.chartValues(d1w, MetricVisual.Cumulative))
        assertEquals(listOf(100.0, 50.0, 25.0), WidgetText.chartValues(d1w, MetricVisual.Daily))
        // 50M allowance is far above 175: the line would flatten the chart, so it's left out.
        assertNull(WidgetText.reference(d1w, MetricVisual.Cumulative, listOf(100.0, 150.0, 175.0)))
        assertEquals(50_000_000.0, WidgetText.reference(d1w, MetricVisual.Cumulative, listOf(40e6))!!, 0.0)
    }
}
