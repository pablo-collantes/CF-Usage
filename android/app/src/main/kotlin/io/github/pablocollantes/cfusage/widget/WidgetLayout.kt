package io.github.pablocollantes.cfusage.widget

import io.github.pablocollantes.cfusage.data.Metric
import io.github.pablocollantes.cfusage.data.MetricCatalog
import io.github.pablocollantes.cfusage.data.Summary
import io.github.pablocollantes.cfusage.data.isStorage
import io.github.pablocollantes.cfusage.util.Fmt
import io.github.pablocollantes.cfusage.util.MetricText
import io.github.pablocollantes.cfusage.util.Times
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * Fits a design's blocks into a widget's height. Glance can't measure text, so every block
 * estimates its height from line heights (dp at the design's text size). Pure, so it is tested.
 *
 * Pass 1 gives each block, in order, its minimum (one row, or the smallest chart) and skips a
 * block that doesn't fit. Pass 2 grows list-like blocks a row at a time, again in order. Whatever
 * is left goes to the charts.
 */
object WidgetLayout {
    /** A block's needs: [base] + [rowH] per row, up to [rows]; [flex] blocks add a chart of at least [flexMin]. */
    data class Spec(
        val base: Float,
        val rowH: Float = 0f,
        val rows: Int = 0,
        val minRows: Int = if (rows > 0) 1 else 0,
        val flex: Boolean = false,
        val flexMin: Float = 0f,
    )

    data class Alloc(val shown: Boolean, val rows: Int, val height: Float, val chart: Float = 0f)

    fun allocate(specs: List<Spec>, available: Float, gap: Float): List<Alloc> {
        val n = specs.size
        val shown = BooleanArray(n)
        val rows = IntArray(n)
        var remaining = available
        var any = false
        for (i in 0 until n) {
            val s = specs[i]
            val need = s.base + s.rowH * s.minRows + (if (s.flex) s.flexMin else 0f) + (if (any) gap else 0f)
            if (need <= remaining + 0.5f) {
                shown[i] = true
                rows[i] = s.minRows
                remaining -= need
                any = true
            }
        }
        for (i in 0 until n) {
            val s = specs[i]
            if (!shown[i] || s.rowH <= 0f || rows[i] >= s.rows) continue
            val extra = floor((remaining + 0.5f) / s.rowH).toInt().coerceIn(0, s.rows - rows[i])
            rows[i] += extra
            remaining -= extra * s.rowH
        }
        val flexCount = (0 until n).count { shown[it] && specs[it].flex }
        val share = if (flexCount > 0) max(0f, remaining) / flexCount else 0f
        return List(n) { i ->
            val s = specs[i]
            if (!shown[i]) {
                Alloc(false, 0, 0f)
            } else {
                val chart = if (s.flex) s.flexMin + share else 0f
                Alloc(true, rows[i], s.base + s.rowH * rows[i] + chart, chart)
            }
        }
    }

    // Line heights in dp at text factor 1 (Glance text is sp, about 1.3× the size with padding).
    const val TITLE = 18f
    const val BODY = 16f
    const val CAPTION = 15f
    const val MICRO = 13f
    const val BIG = 30f
    const val GAP = 5f
    const val PADDING = 11f

    /** Height of one tile in the Tiles layout, and how many fit across. */
    fun tileHeight(value: ValueStyle, s: Float) = (32f + 2f + MICRO + if (value != ValueStyle.Percent) MICRO else 0f) * s + 4f
    fun tilesPerRow(width: Float, s: Float) = max(1, floor(width / (54f * s)).toInt())

    /** True when the widget shows its "updated" time on the first header's line, not a footer. */
    fun updatedInHeader(design: WidgetDesign) =
        design.style.showUpdated && (design.blocks.firstOrNull() as? HeaderBlock)?.bill == false

    /**
     * How the Rows layout fits a width: full product names or short tags, rings or not, and how
     * many items per line. Wider first: drop to tags, then drop the rings, then wrap a product
     * onto more lines (keeping rings only if they cost no extra lines).
     */
    data class SectionRowsPlan(val compactLabels: Boolean, val labelW: Float, val perLine: Int, val indicator: Boolean, val lines: Int)

    fun sectionRows(block: SectionsBlock, width: Float, s: Float): SectionRowsPlan {
        val products = block.products.filter { it.metrics.isNotEmpty() }
        val maxItems = (products.maxOfOrNull { it.metrics.size } ?: 1).coerceIn(1, 9)
        fun labelWidth(compact: Boolean) =
            (products.maxOfOrNull { (if (compact) MetricCatalog.groupTag(it.group) else MetricCatalog.groupShort(it.group)).length } ?: 2) * 6.5f * s + 6f
        val compact = labelWidth(false) > width * 0.22f
        val labelW = labelWidth(compact)
        val codeChars = products.flatMap { it.metrics }.maxOfOrNull { MetricCatalog.short(it).length } ?: 1
        val valueChars = when (block.value) {
            ValueStyle.Percent -> 4.5f
            ValueStyle.Used -> 5f
            ValueStyle.UsedOfTotal -> 11f
            ValueStyle.Projected -> 6f
        }
        val item = (codeChars * 5.8f + valueChars * 6.2f + 4f) * s
        val ring = 15f * s
        val avail = width - labelW
        fun lines(perLine: Int) = products.sumOf { (it.metrics.size + perLine - 1) / perLine }
        val perNoRing = (avail / item).toInt().coerceIn(1, maxItems)
        if (block.indicator == Indicator.None) return SectionRowsPlan(compact, labelW, perNoRing, false, lines(perNoRing))
        val perRing = (avail / (item + ring)).toInt().coerceIn(1, maxItems)
        val withRing = lines(perRing)
        val withoutRing = lines(perNoRing)
        return if (withRing <= withoutRing) {
            SectionRowsPlan(compact, labelW, perRing, true, withRing)
        } else {
            SectionRowsPlan(compact, labelW, perNoRing, false, withoutRing)
        }
    }

    /** Status blocks set to hide when all clear take no room at all then. */
    fun visible(block: Block, summary: Summary): Boolean = when (block) {
        is StatusBlock -> !block.hideWhenClear || summary.alerts.isNotEmpty() || summary.kills.isNotEmpty()
        else -> true
    }

    fun spec(block: Block, summary: Summary, width: Float, s: Float): Spec = when (block) {
        is HeaderBlock -> Spec(base = (TITLE + if (block.cycle) CAPTION else 0f) * s)
        is BillBlock -> Spec(base = (CAPTION + BIG + (if (block.cycleProgress) 10f else 0f) + (if (block.overage) CAPTION else 0f)) * s)
        is StatusBlock -> Spec(base = BODY * s)
        is SectionsBlock -> {
            val products = block.products.filter { it.metrics.isNotEmpty() }
            when (block.layout) {
                SectionLayout.Rows -> Spec(base = 0f, rowH = 20f * s, rows = sectionRows(block, width, s).lines)
                SectionLayout.Columns -> Spec(base = CAPTION * s, rowH = 18f * s, rows = products.maxOfOrNull { it.metrics.size } ?: 0)
                SectionLayout.Tiles -> {
                    val n = products.sumOf { it.metrics.size }
                    Spec(base = 0f, rowH = tileHeight(block.value, s), rows = ceil(n / tilesPerRow(width, s).toFloat()).toInt())
                }
            }
        }
        is ListBlock -> {
            val rowH = (BODY + (if (block.showProjected || block.showPercent && block.value != ValueStyle.Percent) MICRO else 0f) + 2f) * s +
                if (block.indicator == Indicator.Bar) 5f else 0f
            Spec(base = 0f, rowH = rowH, rows = block.metrics.size)
        }
        // Cards aren't uniform rows, so they ask for dp: a "row" here is 1 dp, from the least that
        // shows one card up to every card in full. CardsLayout.fit decides what goes in it.
        is CardsBlock -> Spec(
            base = 0f,
            rowH = 1f,
            rows = ceil(CardsLayout.fullHeight(block, summary, s)).toInt(),
            minRows = ceil(CardsLayout.minHeight(block, summary, s)).toInt(),
        )
        is TopBlock -> {
            val n = minOf(block.count, summary.metric(block.key)?.top?.size ?: 0)
            if (n == 0) Spec(base = (TITLE + CAPTION) * s) else Spec(base = TITLE * s, rowH = 22f * s, rows = n)
        }
        is MetricBlock -> {
            val text = (MICRO + BIG + CAPTION + if (block.showProjected) CAPTION else 0f) * s
            when (block.visual) {
                MetricVisual.Ring -> Spec(base = max(text, (MICRO + 54f) * s))
                MetricVisual.None -> Spec(base = text)
                else -> Spec(base = text + 4f, flex = true, flexMin = 24f)
            }
        }
    }

    /** Lays out a whole design for a widget of [widthDp]×[heightDp]. */
    fun layout(design: WidgetDesign, summary: Summary, widthDp: Float, heightDp: Float, fontScale: Float = 1f): Result {
        val s = design.style.text.factor * fontScale.coerceIn(0.85f, 1.3f)
        val contentW = widthDp - PADDING * 2
        val footer = if (design.style.showUpdated && !updatedInHeader(design)) MICRO * s + GAP else 0f
        val blocks = design.blocks.filter { visible(it, summary) }
        val specs = blocks.map { spec(it, summary, contentW, s) }
        val allocs = allocate(specs, heightDp - PADDING * 2 - footer, GAP)
        return Result(s, blocks.zip(allocs), footer > 0f)
    }

    data class Result(val scale: Float, val blocks: List<Pair<Block, Alloc>>, val footer: Boolean)
}

/** The strings widgets show. Pure, so they are tested alongside the layout. */
object WidgetText {
    fun value(m: Metric?, style: ValueStyle): String = when {
        m == null -> "–"
        style == ValueStyle.Percent -> Fmt.pct(m.pct)
        style == ValueStyle.UsedOfTotal -> MetricText.ofTotal(m, "/")
        style == ValueStyle.Used -> MetricText.short(m.used, m.unit)
        else -> MetricText.projectionPct(m)
    }

    /** A list row's second line: the percent (when the value isn't it) and the projection. */
    fun secondary(m: Metric, b: ListBlock): String = listOfNotNull(
        Fmt.pct(m.pct).takeIf { b.showPercent && b.value != ValueStyle.Percent },
        MetricText.projection(m).takeIf { b.showProjected },
    ).joinToString(" · ")

    /** Full labels, or product-less names when every metric comes from one product. */
    fun names(keys: List<String>): Map<String, String> {
        val oneGroup = keys.map { MetricCatalog.entry(it)?.group }.distinct().size == 1
        return keys.associateWith { if (oneGroup) MetricCatalog.name(it) else MetricCatalog.label(it) }
    }

    /** "Workers by requests", "Databases by rows written". */
    fun topTitle(key: String): String {
        val e = MetricCatalog.entry(key) ?: return MetricCatalog.label(key)
        val noun = MetricCatalog.group(e.group)?.noun ?: e.group
        return "$noun by ${e.name.replaceFirstChar { it.lowercase() }}"
    }

    fun status(s: Summary, b: StatusBlock): String {
        val parts = buildList {
            if (b.alerts && s.alerts.isNotEmpty()) add("${s.alerts.size} alert${if (s.alerts.size == 1) "" else "s"}")
            if (b.kills && s.kills.isNotEmpty()) add("${s.kills.size} kill switch${if (s.kills.size == 1) "" else "es"} on")
        }
        return parts.joinToString(" · ").ifEmpty { "All clear" }
    }

    fun cycleLine(s: Summary) = "${s.cycle.daysLeft.toInt()} days left · resets ${Times.utcDate(s.cycle.end)}"

    /** Values for the metric block's chart: daily use, or the running total for Cumulative. */
    fun chartValues(m: Metric, visual: MetricVisual): List<Double> {
        val daily = m.daily.map { it.value }
        if (visual != MetricVisual.Cumulative || m.isStorage) return daily
        var run = 0.0
        return daily.map { run += it; run }
    }

    /** The allowance line joins a cumulative chart only when it's near the data (else it flattens it). */
    fun reference(m: Metric, visual: MetricVisual, values: List<Double>): Double? {
        if (visual != MetricVisual.Cumulative) return null
        val mx = values.maxOrNull() ?: 0.0
        return m.included.takeIf { it > 0 && it <= mx.coerceAtLeast(1e-9) * 1.6 }
    }
}
