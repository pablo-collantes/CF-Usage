package io.github.pablocollantes.cfusage

import io.github.pablocollantes.cfusage.data.ApiJson
import io.github.pablocollantes.cfusage.data.DatedValue
import io.github.pablocollantes.cfusage.data.Summary
import io.github.pablocollantes.cfusage.widget.BillBlock
import io.github.pablocollantes.cfusage.widget.HeaderBlock
import io.github.pablocollantes.cfusage.widget.ListBlock
import io.github.pablocollantes.cfusage.widget.MetricBlock
import io.github.pablocollantes.cfusage.widget.MetricVisual
import io.github.pablocollantes.cfusage.widget.SectionLayout
import io.github.pablocollantes.cfusage.widget.SectionsBlock
import io.github.pablocollantes.cfusage.widget.StatusBlock
import io.github.pablocollantes.cfusage.widget.Templates
import io.github.pablocollantes.cfusage.widget.TextSize
import io.github.pablocollantes.cfusage.widget.TopBlock
import io.github.pablocollantes.cfusage.widget.WidgetDesign
import io.github.pablocollantes.cfusage.widget.WidgetLayout
import io.github.pablocollantes.cfusage.widget.WidgetLayout.Spec
import io.github.pablocollantes.cfusage.widget.WidgetStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetLayoutTest {
    private val live by lazy {
        ApiJson.decodeFromString(Summary.serializer(), javaClass.classLoader!!.getResource("fixtures/summary-live.json")!!.readText())
    }

    @Test
    fun fixedBlocksThatDontFitAreSkipped() {
        val a = WidgetLayout.allocate(listOf(Spec(30f), Spec(50f), Spec(10f)), available = 50f, gap = 5f)
        assertEquals(listOf(true, false, true), a.map { it.shown })
        assertEquals(30f, a[0].height)
    }

    @Test
    fun listsGrowInOrderAndChartsTakeTheRest() {
        val specs = listOf(
            Spec(base = 10f, rowH = 10f, rows = 5),
            Spec(base = 10f, rowH = 10f, rows = 5),
            Spec(base = 20f, flex = true, flexMin = 10f),
        )
        // Minimums: 20 + (5 + 20) + (5 + 30) = 80; 40 left: the first list takes 4 rows, nothing for the second.
        val a = WidgetLayout.allocate(specs, available = 120f, gap = 5f)
        assertEquals(listOf(5, 1), a.take(2).map { it.rows })
        assertEquals(10f, a[2].chart)
        // With more room the second list grows too, then the chart gets what's left.
        val b = WidgetLayout.allocate(specs, available = 200f, gap = 5f)
        assertEquals(listOf(5, 5), b.take(2).map { it.rows })
        assertEquals(10f + 40f, b[2].chart)
    }

    @Test
    fun emptyListTakesNoRows() {
        val a = WidgetLayout.allocate(listOf(Spec(base = 0f, rowH = 20f, rows = 0)), 100f, 5f)
        assertTrue(a.single().shown)
        assertEquals(0, a.single().rows)
    }

    @Test
    fun overviewFitsFourProductsInFourByTwo() {
        val r = WidgetLayout.layout(Templates.overview(), live, 340f, 160f)
        val (header, sections) = r.blocks.map { it.second }
        assertTrue(header.shown)
        assertEquals(4, sections.rows)
        assertTrue(r.footer)
    }

    @Test
    fun shortWidgetDropsWhatDoesntFit() {
        // A 4×1: the header fits, the four product rows don't fit even one.
        val r = WidgetLayout.layout(Templates.overview(), live, 340f, 80f)
        assertEquals(listOf(true, false), r.blocks.map { it.second.shown })
    }

    @Test
    fun productDetailPutsUpdatedInTheHeaderAndTruncatesRows() {
        val d1 = Templates.product("D1", live)
        assertTrue(WidgetLayout.updatedInHeader(d1))
        val r = WidgetLayout.layout(d1, live, 340f, 160f)
        assertFalse(r.footer)
        assertEquals(3, r.blocks[1].second.rows)

        val doDesign = Templates.product("Durable Objects", live)
        assertEquals(5, WidgetLayout.layout(doDesign, live, 340f, 250f).blocks[1].second.rows)
        assertEquals(3, WidgetLayout.layout(doDesign, live, 340f, 160f).blocks[1].second.rows)
        // Larger text fits fewer rows.
        val big = doDesign.copy(style = WidgetStyle(text = TextSize.Large))
        assertEquals(5, WidgetLayout.layout(doDesign, live, 340f, 200f).blocks[1].second.rows)
        assertEquals(4, WidgetLayout.layout(big, live, 340f, 200f).blocks[1].second.rows)
    }

    @Test
    fun spotlightChartFillsTheRest() {
        val r = WidgetLayout.layout(Templates.spotlight(), live, 160f, 160f)
        val a = r.blocks.single().second
        assertTrue(a.shown)
        assertTrue("chart ${a.chart}", a.chart > 24f)
        val ring = Templates.spotlight().copy(blocks = listOf(MetricBlock(id = "m", visual = MetricVisual.Ring)))
        assertEquals(0f, WidgetLayout.layout(ring, live, 160f, 160f).blocks.single().second.chart)
    }

    @Test
    fun hiddenStatusTakesNoRoom() {
        val clear = live.copy(alerts = emptyList(), kills = emptyList())
        val d = WidgetDesign("x", "x", listOf(StatusBlock(id = "s", hideWhenClear = true), BillBlock(id = "b")))
        assertEquals(listOf("b"), WidgetLayout.layout(d, clear, 160f, 160f).blocks.map { it.first.id })
        val shown = d.copy(blocks = listOf(StatusBlock(id = "s"), BillBlock(id = "b")))
        assertEquals(2, WidgetLayout.layout(shown, clear, 160f, 160f).blocks.size)
    }

    @Test
    fun tilesWrapByWidth() {
        val tiles = (Templates.overview().blocks[1] as SectionsBlock).copy(layout = SectionLayout.Tiles)
        val spec = WidgetLayout.spec(tiles, live, 318f, 1f) // 10 metrics, 5 per row
        assertEquals(2, spec.rows)
        assertEquals(5, WidgetLayout.spec(tiles, live, 138f, 1f).rows) // 2 per row
    }

    @Test
    fun sectionRowsAdaptToWidth() {
        val sections = Templates.overview().blocks[1] as SectionsBlock
        // 4×2: full names, rings, three items a line, one line per product.
        val wide = WidgetLayout.sectionRows(sections, 318f, 1f)
        assertEquals(listOf(false, true, 3, 4), listOf(wide.compactLabels, wide.indicator, wide.perLine, wide.lines))
        // 2×2: two-letter tags, no rings, products with three metrics wrap onto a second line.
        val small = WidgetLayout.sectionRows(sections, 138f, 1f)
        assertEquals(listOf(true, false, 2, 6), listOf(small.compactLabels, small.indicator, small.perLine, small.lines))
        assertEquals(6, WidgetLayout.spec(sections, live, 138f, 1f).rows)
        // No indicator chosen: never rings.
        assertFalse(WidgetLayout.sectionRows(sections.copy(indicator = io.github.pablocollantes.cfusage.widget.Indicator.None), 318f, 1f).indicator)
    }

    @Test
    fun topRowsFollowTheData() {
        val spec = WidgetLayout.spec(TopBlock(id = "t", key = "workers_requests", count = 8), live, 318f, 1f)
        assertEquals(live.metric("workers_requests")!!.top.size.coerceAtMost(8), spec.rows)
        val none = live.copy(metrics = live.metrics.map { it.copy(top = emptyList(), daily = emptyList<DatedValue>()) })
        assertEquals(0, WidgetLayout.spec(TopBlock(id = "t"), none, 318f, 1f).rows)
    }

    @Test
    fun listRowsAreTallerWithASecondLine() {
        val one = WidgetLayout.spec(ListBlock(id = "l", metrics = listOf("d1_rows_read"), showProjected = false, showPercent = false), live, 300f, 1f)
        val two = WidgetLayout.spec(ListBlock(id = "l", metrics = listOf("d1_rows_read")), live, 300f, 1f)
        assertTrue(two.rowH > one.rowH)
        val header = WidgetLayout.spec(HeaderBlock(id = "h", cycle = false), live, 300f, 1f)
        assertEquals(WidgetLayout.TITLE, header.base)
    }
}
