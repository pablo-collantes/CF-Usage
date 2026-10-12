package io.github.pablocollantes.cfusage

import io.github.pablocollantes.cfusage.data.ApiJson
import io.github.pablocollantes.cfusage.data.Summary
import io.github.pablocollantes.cfusage.ui.screens.toggleProductMetric
import io.github.pablocollantes.cfusage.widget.Accent
import io.github.pablocollantes.cfusage.widget.CardContent
import io.github.pablocollantes.cfusage.widget.CardsBlock
import io.github.pablocollantes.cfusage.widget.Density
import io.github.pablocollantes.cfusage.widget.Designs
import io.github.pablocollantes.cfusage.widget.HeaderBlock
import io.github.pablocollantes.cfusage.widget.ListBlock
import io.github.pablocollantes.cfusage.widget.MetricBlock
import io.github.pablocollantes.cfusage.widget.ProductSel
import io.github.pablocollantes.cfusage.widget.ProviderKind
import io.github.pablocollantes.cfusage.widget.SectionLayout
import io.github.pablocollantes.cfusage.widget.SectionsBlock
import io.github.pablocollantes.cfusage.widget.SizePreset
import io.github.pablocollantes.cfusage.widget.StatusBlock
import io.github.pablocollantes.cfusage.widget.Templates
import io.github.pablocollantes.cfusage.widget.TopBlock
import io.github.pablocollantes.cfusage.widget.WidgetDesign
import io.github.pablocollantes.cfusage.widget.WidgetStyle
import io.github.pablocollantes.cfusage.widget.WidgetTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetDesignTest {
    private val live by lazy {
        ApiJson.decodeFromString(Summary.serializer(), javaClass.classLoader!!.getResource("fixtures/summary-live.json")!!.readText())
    }

    @Test
    fun seedsRoundTripThroughJson() {
        val custom = WidgetDesign(
            id = "x",
            name = "Mine",
            blocks = listOf(
                SectionsBlock(id = "s", products = listOf(ProductSel("R2", listOf("r2_storage_gb"))), layout = SectionLayout.Tiles),
                StatusBlock(id = "st", hideWhenClear = true),
                CardsBlock(
                    id = "c",
                    products = listOf("R2", "D1"),
                    content = CardContent.Rows,
                    density = Density.Compact,
                ),
            ),
            style = WidgetStyle(theme = WidgetTheme.Dark, opacity = 70, accent = Accent.Violet),
            size = SizePreset.Tall,
        )
        val all = Templates.seeds() + custom
        val json = Designs.encode(all)
        assertEquals(all, Designs.decode(json))
        // Blocks are tagged, so the file stays readable and stable across versions.
        assertTrue(json.contains("\"type\":\"sections\""))
        assertTrue(json.contains("\"type\":\"cards\""))
    }

    @Test
    fun decodeIgnoresUnknownFields() {
        val json = """[{"id":"a","name":"A","future":1,"blocks":[{"type":"header","id":"h","title":"T","extra":true}]}]"""
        val d = Designs.decode(json).single()
        assertEquals("T", (d.blocks.single() as HeaderBlock).title)
        assertEquals(SizePreset.Wide, d.size)
    }

    @Test
    fun seedIdsAreStableAndDistinct() {
        val ids = Templates.seeds().map { it.id }
        assertEquals(listOf("overview", "top-workers", "spotlight", "d1", "r2", "do"), ids)
        // The providers' fallbacks all exist among the seeds.
        for (k in ProviderKind.entries) assertTrue(k.defaultDesign in ids)
    }

    @Test
    fun overviewSeedIsTheProductRings() {
        val s = Templates.overview().blocks.filterIsInstance<SectionsBlock>().single()
        assertEquals(listOf("Workers", "Durable Objects", "D1", "R2"), s.products.map { it.group })
        assertEquals(listOf("r2_class_b", "r2_class_a", "r2_storage_gb"), s.products.last().metrics)
    }

    @Test
    fun resolvePrefersAssignedThenLegacyThenDefault() {
        val seeds = Templates.seeds()
        val mine = Templates.blank(id = "mine")
        val designs = seeds + mine
        assertEquals("mine", Designs.resolve(designs, "mine", ProviderKind.Wide).id)
        // Unassigned: the provider's default.
        assertEquals("overview", Designs.resolve(designs, null, ProviderKind.Wide).id)
        assertEquals("top-workers", Designs.resolve(designs, null, ProviderKind.Large).id)
        // An old Metric widget keeps showing its metric until it gets a design.
        val legacy = Designs.resolve(designs, null, ProviderKind.Small, legacyMetric = "kv_reads")
        assertEquals("kv_reads", (legacy.blocks.single() as MetricBlock).key)
        // A deleted design falls back to the default, not the legacy metric.
        assertEquals("spotlight", Designs.resolve(designs, "gone", ProviderKind.Small, legacyMetric = "kv_reads").id)
        // Default deleted too: rebuilt from the template.
        val rebuilt = Designs.resolve(listOf(mine), "gone", ProviderKind.Large)
        assertEquals("top-workers", rebuilt.id)
        assertTrue(rebuilt.blocks.any { it is TopBlock })
    }

    @Test
    fun productTemplateSkipsIdleLegacyMetrics() {
        val doDesign = Templates.product("Durable Objects", live)
        val keys = doDesign.blocks.filterIsInstance<ListBlock>().single().metrics
        assertFalse(keys.any { it.startsWith("do_kv_") }) // all zero in the live data
        assertTrue("do_sql_rows_written" in keys)
        assertEquals(SizePreset.Large, doDesign.size)
        assertEquals(SizePreset.Wide, Templates.product("D1", live).size)
        assertEquals("do", doDesign.id)
        assertNotEquals("d1", Templates.product("D1", live, id = "other").id)
    }

    @Test
    fun productsInUseFollowsUsage() {
        assertEquals(listOf("Workers", "Durable Objects", "D1", "R2", "KV", "Queues"), Templates.productsInUse(live))
        val noKv = live.copy(metrics = live.metrics.map { if (it.group == "KV") it.copy(used = 0.0) else it })
        assertFalse("KV" in Templates.productsInUse(noKv))
    }

    @Test
    fun duplicateGetsANewId() {
        val d = Templates.overview()
        val copy = Designs.duplicate(d)
        assertNotEquals(d.id, copy.id)
        assertEquals("Overview copy", copy.name)
        assertEquals(d.blocks, copy.blocks)
    }

    @Test
    fun toggleKeepsCatalogOrderAndDropsEmptyProducts() {
        var p = listOf(ProductSel("D1", listOf("d1_rows_written")))
        p = toggleProductMetric(p, "d1_rows_read")
        assertEquals(listOf("d1_rows_read", "d1_rows_written"), p.single().metrics)
        p = toggleProductMetric(p, "r2_storage_gb")
        assertEquals(listOf("D1", "R2"), p.map { it.group })
        p = toggleProductMetric(p, "r2_storage_gb")
        assertEquals(listOf("D1"), p.map { it.group })
    }
}
