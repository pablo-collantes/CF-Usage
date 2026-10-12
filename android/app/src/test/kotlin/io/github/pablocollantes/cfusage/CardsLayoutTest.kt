package io.github.pablocollantes.cfusage

import io.github.pablocollantes.cfusage.data.ApiJson
import io.github.pablocollantes.cfusage.data.Summary
import io.github.pablocollantes.cfusage.widget.CardContent
import io.github.pablocollantes.cfusage.widget.CardsBlock
import io.github.pablocollantes.cfusage.widget.CardsLayout
import io.github.pablocollantes.cfusage.widget.Density
import io.github.pablocollantes.cfusage.widget.HeaderBlock
import io.github.pablocollantes.cfusage.widget.SizePreset
import io.github.pablocollantes.cfusage.widget.Templates
import io.github.pablocollantes.cfusage.widget.WidgetDesign
import io.github.pablocollantes.cfusage.widget.WidgetLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardsLayoutTest {
    private val live by lazy {
        ApiJson.decodeFromString(Summary.serializer(), javaClass.classLoader!!.getResource("fixtures/summary-live.json")!!.readText())
    }

    /** Content size of a widget preset, as WidgetLayout sees it. */
    private fun w(p: SizePreset) = p.widthDp - WidgetLayout.PADDING * 2
    private fun h(p: SizePreset) = p.heightDp - WidgetLayout.PADDING * 2

    @Test
    fun allInUseFollowsTheOverviewAndChosenKeepsItsOrder() {
        val all = CardsLayout.cards(CardsBlock(), live).map { it.group }
        assertEquals(listOf("Workers", "Durable Objects", "D1", "R2", "KV", "Queues"), all)
        val chosen = CardsLayout.cards(CardsBlock(products = listOf("R2", "D1")), live).map { it.group }
        assertEquals(listOf("R2", "D1"), chosen)
    }

    @Test
    fun ringsAndRowsAreTheMetricsInUseLikeTheOverview() {
        val dobj = CardsLayout.cards(CardsBlock(products = listOf("Durable Objects")), live).single()
        // The legacy KV-backed metrics have no usage, so they get no ring and no row.
        assertEquals(listOf("do_requests", "do_duration_gbs", "do_sql_rows_read", "do_sql_rows_written", "do_sql_storage_gb"), dobj.rows.map { it.key })
        assertEquals(dobj.rows, dobj.rings)
        // R2's rows follow the catalog: reads (Class B) before writes.
        val r2 = CardsLayout.cards(CardsBlock(products = listOf("R2")), live).single()
        assertEquals(listOf("r2_class_b", "r2_class_a", "r2_storage_gb"), r2.rows.map { it.key })
    }

    @Test
    fun aSmallWidgetShowsEveryProductBeforeAnyRows() {
        val b = CardsBlock()
        val fits = CardsLayout.fit(b, live, w(SizePreset.Tall), h(SizePreset.Tall), 1f)
        assertTrue("at least three cards at 4×4, got ${fits.size}", fits.size >= 3)
        // Rings for every card shown; rows only go to the first cards once all minimums are in.
        assertTrue(fits.all { it.rings.isNotEmpty() })
        val rowsAt = fits.indexOfFirst { it.rows.isEmpty() }
        if (rowsAt >= 0) assertTrue(fits.drop(rowsAt).all { it.rows.isEmpty() })
    }

    @Test
    fun aTallSingleProductCardShowsAllItsRows() {
        val b = CardsBlock(products = listOf("R2"))
        val fit = CardsLayout.fit(b, live, w(SizePreset.Large), h(SizePreset.Large), 1f).single()
        assertEquals(3, fit.rings.size)
        assertEquals(3, fit.rows.size)
    }

    @Test
    fun rowsOnlyCardsStartWithOneRowEach() {
        val b = CardsBlock(content = CardContent.Rows, showProjected = false, density = Density.Compact)
        val fits = CardsLayout.fit(b, live, w(SizePreset.Wide), h(SizePreset.Wide), 1f)
        assertTrue(fits.isNotEmpty())
        assertTrue(fits.all { it.rings.isEmpty() && it.rows.isNotEmpty() })
    }

    @Test
    fun ringsOnlyCardsHaveNoRowsAndUnusedProductsGetANote() {
        val unused = live.copy(metrics = live.metrics.map { if (it.group == "Queues") it.copy(used = 0.0, pct = 0.0) else it })
        val b = CardsBlock(products = listOf("Queues", "R2"), content = CardContent.Rings)
        val fits = CardsLayout.fit(b, unused, w(SizePreset.Tall), h(SizePreset.Tall), 1f)
        assertTrue(fits[0].note)
        assertTrue(fits.all { it.rows.isEmpty() })
        // All-in-use leaves the unused product out entirely.
        assertFalse(CardsLayout.cards(CardsBlock(), unused).any { it.group == "Queues" })
    }

    @Test
    fun nothingFitsBelowTheFirstCardsMinimum() {
        val b = CardsBlock()
        val min = CardsLayout.minHeight(b, live, 1f)
        assertEquals(0, CardsLayout.fit(b, live, 300f, min - 1f, 1f).size)
        assertEquals(1, CardsLayout.fit(b, live, 300f, min, 1f).size)
    }

    @Test
    fun narrowCardsDropUsedOfTotalBeforeTheName() {
        val b = CardsBlock(products = listOf("Durable Objects"))
        val rows = CardsLayout.cards(b, live).single().rows
        val wide = CardsLayout.columns(b, rows, w(SizePreset.Wide), 1f)
        assertTrue(wide.ofTotal && wide.name)
        val small = CardsLayout.columns(b, rows, w(SizePreset.Small), 1f)
        assertFalse(small.ofTotal)
    }

    @Test
    fun theLayoutGivesCardsTheRoomLeftUnderAHeader() {
        val d = WidgetDesign("c", "c", listOf(HeaderBlock(id = "h"), CardsBlock(id = "c")), size = SizePreset.Tall)
        val r = WidgetLayout.layout(d, live, SizePreset.Tall.widthDp, SizePreset.Tall.heightDp)
        val (_, alloc) = r.blocks.single { it.first is CardsBlock }
        assertTrue(alloc.shown)
        // The cards get everything below the header and footer, not just their minimum.
        assertTrue(alloc.rows > CardsLayout.minHeight(CardsBlock(), live, 1f))
        assertTrue(alloc.height <= SizePreset.Tall.heightDp)
    }

    @Test
    fun cardTemplates() {
        val all = Templates.overviewCards()
        assertEquals(SizePreset.Tall, all.size)
        assertFalse(all.style.border)
        val r2 = Templates.productCard("R2", live)
        assertEquals("R2 card", r2.name)
        assertEquals(listOf("R2"), (r2.blocks.single() as CardsBlock).products)
        assertEquals(SizePreset.Large, r2.size)
        // Durable Objects has five metrics in use, so its card starts at 4×4.
        assertEquals(SizePreset.Tall, Templates.productCard("Durable Objects", live).size)
    }

    @Test
    fun eachProductCardShowsEveryRowAtItsTemplateSize() {
        for (g in listOf("Workers", "Durable Objects", "D1", "R2", "KV", "Queues")) {
            val d = Templates.productCard(g, live)
            val r = WidgetLayout.layout(d, live, d.size.widthDp, d.size.heightDp)
            val (block, alloc) = r.blocks.single()
            val fit = CardsLayout.fit(block as CardsBlock, live, w(d.size), alloc.rows.toFloat(), 1f).single()
            assertEquals("$g at ${d.size.label}", fit.card.rows.size, fit.rows.size)
        }
    }
}
