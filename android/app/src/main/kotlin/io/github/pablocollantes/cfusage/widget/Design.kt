package io.github.pablocollantes.cfusage.widget

import io.github.pablocollantes.cfusage.data.MetricCatalog
import io.github.pablocollantes.cfusage.data.Summary
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * A widget the user designed: a vertical stack of blocks plus a style. Every home-screen widget
 * shows one design; several widgets can share one. Pure data, so it is unit tested.
 */
@Serializable
data class WidgetDesign(
    val id: String,
    val name: String,
    val blocks: List<Block> = emptyList(),
    val style: WidgetStyle = WidgetStyle(),
    /** The size the editor previews at, and the provider "Add to home screen" pins. */
    val size: SizePreset = SizePreset.Wide,
)

/** Home-screen sizes in cells, with the dp a typical phone launcher gives them. */
@Serializable
enum class SizePreset(val label: String, val widthDp: Float, val heightDp: Float) {
    Small("2×2", 160f, 160f),
    Wide("4×2", 340f, 160f),
    Large("4×3", 340f, 250f),
    Tall("4×4", 340f, 340f),
}

@Serializable enum class WidgetTheme { System, Light, Dark }
@Serializable enum class Corners(val dp: Float) { Square(0f), Rounded(16f), Round(28f) }
@Serializable enum class Accent { Blue, Green, Violet, Amber, Zinc }
@Serializable enum class TextSize(val factor: Float) { Compact(0.9f), Default(1f), Large(1.15f) }

@Serializable
data class WidgetStyle(
    val theme: WidgetTheme = WidgetTheme.System,
    /** Background opacity in percent: 100, 85, 70, 50 or 0. */
    val opacity: Int = 100,
    val corners: Corners = Corners.Rounded,
    val accent: Accent = Accent.Blue,
    val text: TextSize = TextSize.Default,
    val border: Boolean = true,
    val showUpdated: Boolean = true,
)

/** How a metric shows its share of the allowance. */
@Serializable enum class Indicator { Ring, Dot, Bar, None }

/** Which figure a compact item shows. */
@Serializable enum class ValueStyle { Percent, UsedOfTotal, Used, Projected }

@Serializable enum class SectionLayout { Rows, Columns, Tiles }

/** Status: traffic light. Accent: the accent color until a metric reaches warning. */
@Serializable enum class ColorMode { Status, Accent }

@Serializable enum class MetricVisual { Ring, Daily, Bars, Cumulative, None }

@Serializable
sealed class Block {
    abstract val id: String
}

@Serializable
@SerialName("header")
data class HeaderBlock(
    override val id: String = newId(),
    val title: String = "CF Usage",
    val statusDot: Boolean = true,
    val bill: Boolean = true,
    val cycle: Boolean = true,
) : Block()

/** One product and the metrics chosen from it, in catalog order. */
@Serializable
data class ProductSel(val group: String, val metrics: List<String>)

/** Products side by side or stacked, each metric a small indicator, code and figure. */
@Serializable
@SerialName("sections")
data class SectionsBlock(
    override val id: String = newId(),
    val products: List<ProductSel> = emptyList(),
    val layout: SectionLayout = SectionLayout.Rows,
    val indicator: Indicator = Indicator.Ring,
    val value: ValueStyle = ValueStyle.Percent,
    val colors: ColorMode = ColorMode.Status,
) : Block()

/** Detail rows: name, used / included, percent and the projection. */
@Serializable
@SerialName("list")
data class ListBlock(
    override val id: String = newId(),
    val metrics: List<String> = emptyList(),
    val indicator: Indicator = Indicator.Ring,
    val value: ValueStyle = ValueStyle.UsedOfTotal,
    val showPercent: Boolean = true,
    val showProjected: Boolean = true,
) : Block()

/** One metric, large, with an optional chart of the cycle. */
@Serializable
@SerialName("metric")
data class MetricBlock(
    override val id: String = newId(),
    val key: String = "workers_requests",
    val visual: MetricVisual = MetricVisual.Daily,
    val showProjected: Boolean = true,
) : Block()

/** A metric's biggest contributors: Workers, namespaces, databases, buckets. */
@Serializable
@SerialName("top")
data class TopBlock(
    override val id: String = newId(),
    val key: String = "workers_requests",
    val count: Int = 5,
    val bars: Boolean = true,
    val share: Boolean = false,
) : Block()

/** What a product card shows under its name and status. */
@Serializable enum class CardContent { RingsAndRows, Rings, Rows }

@Serializable enum class Density { Comfortable, Compact }

/**
 * The Overview screen's product cards: one card per product with its name and worst status, a
 * ring per metric in use, then a row per metric with used / included, percent and the projection.
 */
@Serializable
@SerialName("cards")
data class CardsBlock(
    override val id: String = newId(),
    /** Empty: every product in use, in catalog order. Otherwise these products, in this order. */
    val products: List<String> = emptyList(),
    val content: CardContent = CardContent.RingsAndRows,
    val showProjected: Boolean = true,
    val density: Density = Density.Comfortable,
) : Block()

@Serializable
@SerialName("bill")
data class BillBlock(
    override val id: String = newId(),
    val cycleProgress: Boolean = true,
    val overage: Boolean = true,
) : Block()

@Serializable
@SerialName("status")
data class StatusBlock(
    override val id: String = newId(),
    val alerts: Boolean = true,
    val kills: Boolean = true,
    val hideWhenClear: Boolean = false,
) : Block()

fun newId(): String = UUID.randomUUID().toString().take(8)

val DesignJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "type"
}

val DesignListSerializer = ListSerializer(WidgetDesign.serializer())

/** The three home-screen providers. Each falls back to its own default design. */
enum class ProviderKind(val defaultDesign: String, val preset: SizePreset) {
    Wide(Templates.OVERVIEW, SizePreset.Wide),
    Small(Templates.SPOTLIGHT, SizePreset.Small),
    Large(Templates.TOP_WORKERS, SizePreset.Large),
    ;

    companion object {
        fun forPreset(p: SizePreset) = when (p) {
            SizePreset.Small -> Small
            SizePreset.Wide -> Wide
            SizePreset.Large, SizePreset.Tall -> Large
        }
    }
}

/** The starting designs and the "new widget" templates. */
object Templates {
    const val OVERVIEW = "overview"
    const val TOP_WORKERS = "top-workers"
    const val SPOTLIGHT = "spotlight"

    /** Product details for the seeds and the "Product stack" action. */
    private val detailIds = mapOf("D1" to "d1", "R2" to "r2", "Durable Objects" to "do")

    fun overview(id: String = OVERVIEW) = WidgetDesign(
        id = id,
        name = "Overview",
        blocks = listOf(
            HeaderBlock(id = "h"),
            SectionsBlock(
                id = "s",
                products = listOf(
                    ProductSel("Workers", listOf("workers_requests", "workers_cpu_ms")),
                    ProductSel("Durable Objects", listOf("do_requests", "do_sql_rows_read", "do_sql_rows_written")),
                    ProductSel("D1", listOf("d1_rows_read", "d1_rows_written")),
                    ProductSel("R2", listOf("r2_class_b", "r2_class_a", "r2_storage_gb")),
                ),
            ),
        ),
        size = SizePreset.Wide,
    )

    fun topWorkers(id: String = TOP_WORKERS) = WidgetDesign(
        id = id,
        name = "Top Workers",
        blocks = listOf(HeaderBlock(id = "h", title = "Top Workers", bill = false, cycle = false), TopBlock(id = "t", key = "workers_requests", count = 5)),
        size = SizePreset.Large,
    )

    fun spotlight(key: String = "d1_rows_written", id: String = SPOTLIGHT) = WidgetDesign(
        id = id,
        name = MetricCatalog.label(key),
        blocks = listOf(MetricBlock(id = "m", key = key, visual = MetricVisual.Daily)),
        size = SizePreset.Small,
    )

    /**
     * One product in detail: every metric with used / included and the projection. Durable
     * Objects' legacy KV-backed metrics only join when they have usage.
     */
    fun product(group: String, summary: Summary? = null, id: String = detailIds[group] ?: newId()): WidgetDesign {
        val metrics = productMetrics(group, summary)
        val short = MetricCatalog.groupShort(group)
        return WidgetDesign(
            id = id,
            name = if (short == group) group else "$group ($short)",
            blocks = listOf(
                HeaderBlock(id = "h", title = group, bill = false, cycle = false),
                ListBlock(id = "l", metrics = metrics),
            ),
            size = if (metrics.size > 3) SizePreset.Large else SizePreset.Wide,
        )
    }

    fun productMetrics(group: String, summary: Summary?): List<String> =
        MetricCatalog.inGroup(group)
            .filter { e -> !e.key.startsWith("do_kv_") || (summary?.metric(e.key)?.used ?: 0.0) > 0 }
            .map { it.key }

    /** Cards look like the app when the widget is the "screen": no outer hairline of its own. */
    private val cardStyle = WidgetStyle(border = false)

    /** Every product in use as the Overview shows it. */
    fun overviewCards(id: String = newId()) = WidgetDesign(
        id = id,
        name = "Overview cards",
        blocks = listOf(CardsBlock(id = "c")),
        style = cardStyle,
        size = SizePreset.Tall,
    )

    /**
     * One product's Overview card. 4×3 fits the rings and three rows; 4×2 only fits the rings
     * (Compact density fits two rows there).
     */
    fun productCard(group: String, summary: Summary? = null, id: String = newId()): WidgetDesign {
        val active = summary?.metrics?.count { it.group == group && it.used > 0 } ?: 3
        return WidgetDesign(
            id = id,
            name = "${MetricCatalog.groupShort(group)} card",
            blocks = listOf(CardsBlock(id = "c", products = listOf(group))),
            style = cardStyle,
            size = if (active > 3) SizePreset.Tall else SizePreset.Large,
        )
    }

    fun billAndStatus(id: String = newId()) = WidgetDesign(
        id = id,
        name = "Bill & status",
        blocks = listOf(BillBlock(id = "b"), StatusBlock(id = "st")),
        size = SizePreset.Small,
    )

    fun blank(id: String = newId()) = WidgetDesign(id = id, name = "New widget", blocks = listOf(HeaderBlock(id = "h")))

    /** Written on first run. Stable ids, so the providers' fallbacks find them. */
    fun seeds(): List<WidgetDesign> = listOf(
        overview(),
        topWorkers(),
        spotlight(),
        product("D1"),
        product("R2"),
        product("Durable Objects"),
    )

    fun byId(id: String): WidgetDesign? = when (id) {
        OVERVIEW -> overview()
        TOP_WORKERS -> topWorkers()
        SPOTLIGHT -> spotlight()
        else -> null
    }

    /** Products with any usage this cycle (all of them before the first summary arrives). */
    fun productsInUse(summary: Summary?): List<String> = MetricCatalog.groups.map { it.name }.filter { g ->
        summary == null || MetricCatalog.inGroup(g).any { (summary.metric(it.key)?.used ?: 0.0) > 0 }
    }
}

object Designs {
    /**
     * The design a widget shows: its assigned one; else a legacy Metric widget's metric; else the
     * provider's default (rebuilt from the template if the user deleted it).
     */
    fun resolve(designs: List<WidgetDesign>, assigned: String?, kind: ProviderKind, legacyMetric: String? = null): WidgetDesign {
        designs.firstOrNull { it.id == assigned }?.let { return it }
        if (assigned == null && legacyMetric != null) return Templates.spotlight(legacyMetric, id = "legacy-$legacyMetric")
        return designs.firstOrNull { it.id == kind.defaultDesign } ?: Templates.byId(kind.defaultDesign)!!
    }

    /** A copy with a fresh id and a "copy" suffix. */
    fun duplicate(d: WidgetDesign): WidgetDesign = d.copy(id = newId(), name = "${d.name} copy")

    fun decode(json: String): List<WidgetDesign> = DesignJson.decodeFromString(DesignListSerializer, json)
    fun encode(designs: List<WidgetDesign>): String = DesignJson.encodeToString(DesignListSerializer, designs)
}
