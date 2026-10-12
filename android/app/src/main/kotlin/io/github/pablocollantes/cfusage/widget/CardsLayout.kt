package io.github.pablocollantes.cfusage.widget

import io.github.pablocollantes.cfusage.data.Metric
import io.github.pablocollantes.cfusage.data.MetricCatalog
import io.github.pablocollantes.cfusage.data.Summary
import io.github.pablocollantes.cfusage.data.byProduct
import io.github.pablocollantes.cfusage.util.MetricText
import kotlin.math.floor
import kotlin.math.max

/**
 * Fits the Overview's product cards into a block. Pure, so it is unit tested.
 *
 * Every card first gets its minimum, in order, until the next one doesn't fit: name, status and
 * rings, or one row when rings are off. Rows are then added card by card, top to bottom, so a
 * small widget shows every product at a glance before it shows any one in detail.
 */
object CardsLayout {
    /**
     * Sizes in dp. Text-based ones are already scaled by the design's text size. The renderer gives
     * every line these exact heights, so the estimates here are what the widget really draws.
     */
    data class Dims(
        val padH: Float,
        val padTop: Float,
        val padBottom: Float,
        val header: Float,
        val ring: Float,
        val tile: Float,
        /** The code under a ring. */
        val code: Float,
        /** The ring strip: a gap, the ring, its code underneath, a gap. */
        val rings: Float,
        /** One row's single line, with its padding and the hairline above it. */
        val row: Float,
        /** The projection's own line under a row (Comfortable only; Compact puts it inline). */
        val projLine: Float,
        val gap: Float,
        val note: Float,
    )

    fun dims(density: Density, s: Float): Dims = when (density) {
        Density.Comfortable -> Dims(
            padH = 12f, padTop = 7f, padBottom = 4f, header = 19f * s, ring = 36f * s, tile = 50f * s, code = 13f * s,
            rings = (3f + 36f + 2f + 13f + 2f) * s, row = (4f + 16f + 4f) * s + 1f, projLine = 13f * s, gap = 6f, note = 15f * s,
        )
        Density.Compact -> Dims(
            padH = 10f, padTop = 5f, padBottom = 3f, header = 18f * s, ring = 26f * s, tile = 38f * s, code = 12f * s,
            rings = (3f + 26f + 1f + 12f + 2f) * s, row = (2f + 16f + 2f) * s + 1f, projLine = 0f, gap = 5f, note = 14f * s,
        )
    }

    /** One product's metrics. */
    data class Card(val group: String, val metrics: List<Metric>) {
        /** A ring per metric in use, as on the Overview. */
        val rings: List<Metric> get() = metrics.filter { it.used > 0 }

        /** Rows for the metrics in use; every metric when none is (as the Overview does). */
        val rows: List<Metric> get() = rings.ifEmpty { metrics }
    }

    /** All in use: products with usage, in catalog order. Chosen: those products, in that order. */
    fun cards(b: CardsBlock, summary: Summary): List<Card> {
        val all = summary.byProduct()
        if (b.products.isEmpty()) return all.filter { (_, ms) -> ms.any { it.used > 0 } }.map { Card(it.first, it.second) }
        return b.products.distinct().mapNotNull { g -> all.firstOrNull { it.first == g }?.let { Card(g, it.second) } }
    }

    /** A card as drawn: the rings and rows that fit, or a "not used" note. */
    data class Fit(val card: Card, val rings: List<Metric>, val rows: List<Metric>, val note: Boolean)

    fun rowHeight(b: CardsBlock, d: Dims) = d.row + if (b.showProjected && b.density == Density.Comfortable) d.projLine else 0f

    fun ringsPerLine(width: Float, d: Dims) = floor((width - 2 * d.padH - 2f) / d.tile).toInt().coerceIn(1, 9)

    private fun showsRings(b: CardsBlock, c: Card) = b.content != CardContent.Rows && c.rings.isNotEmpty()

    /** A rings-only card for a product with nothing in use shows one muted line instead. */
    private fun isNote(b: CardsBlock, c: Card) = b.content == CardContent.Rings && c.rings.isEmpty()

    private fun showsRows(b: CardsBlock) = b.content != CardContent.Rings

    private fun chrome(d: Dims) = d.padTop + d.padBottom + d.header + 2f

    private fun minCard(b: CardsBlock, c: Card, d: Dims) = chrome(d) + when {
        showsRings(b, c) -> d.rings
        isNote(b, c) -> d.note
        showsRows(b) -> rowHeight(b, d)
        else -> 0f
    }

    private fun fullCard(b: CardsBlock, c: Card, d: Dims) = chrome(d) +
        (if (showsRings(b, c)) d.rings else 0f) +
        (if (isNote(b, c)) d.note else 0f) +
        (if (showsRows(b)) c.rows.size.coerceAtMost(MAX_CARD_ROWS) * rowHeight(b, d) else 0f)

    /** The height with nothing left out, and the least the block needs to show anything. */
    fun fullHeight(b: CardsBlock, summary: Summary, s: Float): Float {
        val d = dims(b.density, s)
        val cs = cards(b, summary).take(MAX_CARDS)
        if (cs.isEmpty()) return EMPTY * s
        return cs.sumOf { fullCard(b, it, d).toDouble() }.toFloat() + d.gap * (cs.size - 1)
    }

    fun minHeight(b: CardsBlock, summary: Summary, s: Float): Float {
        val d = dims(b.density, s)
        val first = cards(b, summary).firstOrNull() ?: return EMPTY * s
        return minCard(b, first, d)
    }

    fun fit(b: CardsBlock, summary: Summary, width: Float, height: Float, s: Float): List<Fit> {
        val d = dims(b.density, s)
        val perLine = ringsPerLine(width, d)
        val rowH = rowHeight(b, d)
        var left = height
        val fits = mutableListOf<Fit>()
        val rowsShown = mutableListOf<Int>()
        for (c in cards(b, summary).take(MAX_CARDS)) {
            val need = minCard(b, c, d) + if (fits.isEmpty()) 0f else d.gap
            if (need > left + 0.5f) break
            left -= need
            val rings = if (showsRings(b, c)) c.rings.take(perLine) else emptyList()
            // Without rings, a card's minimum already includes its first row.
            rowsShown += if (showsRows(b) && rings.isEmpty() && !isNote(b, c)) 1 else 0
            fits += Fit(c, rings, emptyList(), isNote(b, c))
        }
        if (showsRows(b)) {
            for (i in fits.indices) {
                val total = fits[i].card.rows.size.coerceAtMost(MAX_CARD_ROWS)
                val extra = floor((left + 0.5f) / rowH).toInt().coerceIn(0, total - rowsShown[i])
                rowsShown[i] += extra
                left -= extra * rowH
            }
        }
        return fits.mapIndexed { i, f -> f.copy(rows = f.card.rows.take(rowsShown[i])) }
    }

    /**
     * Which columns a card's rows have room for: the percent always, then used / included while
     * the name keeps a readable width, then the name itself.
     */
    data class Columns(val badge: Float, val pct: Float, val proj: Float, val ofTotal: Boolean, val name: Boolean)

    fun columns(b: CardsBlock, rows: List<Metric>, width: Float, s: Float): Columns {
        val d = dims(b.density, s)
        val inner = width - 2 * d.padH - 2f
        val char = if (b.density == Density.Compact) 6.1f * s else 6.6f * s
        val codeChars = rows.maxOfOrNull { MetricCatalog.short(it.key).length } ?: 2
        val badge = max(24f, codeChars * 6.2f + 10f) * s
        val pct = 40f * s
        val proj = if (b.density == Density.Compact && b.showProjected) 46f * s else 0f
        val ofW = (rows.maxOfOrNull { MetricText.ofTotal(it).length } ?: 0) * char + 6f
        val fixed = badge + 8f + pct + proj
        val ofTotal = inner - fixed - ofW >= 60f * s
        val name = inner - fixed - (if (ofTotal) ofW else 0f) >= 28f * s
        return Columns(badge, pct, proj, ofTotal, name)
    }

    /** Glance keeps at most 10 children per container. */
    const val MAX_CARDS = 9
    const val MAX_CARD_ROWS = 10

    /** "Nothing in use yet", when no product has usage. */
    private const val EMPTY = 15f
}
