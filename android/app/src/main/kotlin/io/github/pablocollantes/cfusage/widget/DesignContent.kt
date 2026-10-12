package io.github.pablocollantes.cfusage.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import io.github.pablocollantes.cfusage.MainActivity
import io.github.pablocollantes.cfusage.R
import io.github.pablocollantes.cfusage.container
import io.github.pablocollantes.cfusage.data.Metric
import io.github.pablocollantes.cfusage.data.MetricCatalog
import io.github.pablocollantes.cfusage.data.Summary
import io.github.pablocollantes.cfusage.data.worstStatus
import io.github.pablocollantes.cfusage.ui.theme.statusLabel
import io.github.pablocollantes.cfusage.util.Fmt
import io.github.pablocollantes.cfusage.util.FreshnessRules
import io.github.pablocollantes.cfusage.util.MetricText
import io.github.pablocollantes.cfusage.util.Times
import androidx.glance.color.ColorProvider as DayNight

/**
 * Renders any [WidgetDesign]. Shared by the three home-screen providers and the in-app previews
 * (which pass interactive = false, so no PendingIntents are built).
 *
 * Glance truncates a Row/Column/Box to 10 children, so blocks are separated by padding rather
 * than Spacers, designs hold at most [MAX_BLOCKS] blocks, and lists stop at 10 rows.
 */
const val MAX_BLOCKS = 8
private const val MAX_ROWS = 10

/** Resolved colors for one design: forced light/dark, or day/night providers for System. */
private class Palette(
    val fg: ColorProvider,
    val muted: ColorProvider,
    val track: ColorProvider,
    val border: ColorProvider,
    val bg: ColorProvider?,
    /** The background at full opacity: a card's fill when the widget itself has none. */
    val solid: ColorProvider,
    val dark: Boolean,
    val accent: Accent,
)

private val LightFg = Color(0xFF09090B)
private val DarkFg = Color(0xFFFAFAFA)
private val LightMuted = Color(0xFF71717A)
private val DarkMuted = Color(0xFFA1A1AA)
private val LightTrack = Color(0xFFF4F4F5)
private val DarkTrack = Color(0xFF27272A)
private val LightBorder = Color(0xFFE4E4E7)
private val DarkBorder = Color(0xFF27272A)
private val LightBg = Color(0xFFFFFFFF)
private val DarkBg = Color(0xFF09090B)

private fun palette(style: WidgetStyle, context: Context): Palette {
    val alpha = style.opacity.coerceIn(0, 100) / 100f
    fun pick(light: Color, dark: Color): ColorProvider = when (style.theme) {
        WidgetTheme.System -> DayNight(light, dark)
        WidgetTheme.Light -> ColorProvider(light)
        WidgetTheme.Dark -> ColorProvider(dark)
    }
    val dark = when (style.theme) {
        WidgetTheme.System -> WidgetCharts.isNight(context)
        WidgetTheme.Light -> false
        WidgetTheme.Dark -> true
    }
    return Palette(
        fg = pick(LightFg, DarkFg),
        muted = pick(LightMuted, DarkMuted),
        track = pick(LightTrack, DarkTrack),
        border = pick(LightBorder, DarkBorder),
        bg = if (alpha <= 0f) null else pick(LightBg.copy(alpha = alpha), DarkBg.copy(alpha = alpha)),
        solid = pick(LightBg, DarkBg),
        dark = dark,
        accent = style.accent,
    )
}

private fun borderRes(c: Corners) = when (c) {
    Corners.Square -> R.drawable.widget_border_0
    Corners.Rounded -> R.drawable.widget_border_16
    Corners.Round -> R.drawable.widget_border_28
}

/** Product cards inside a widget get smaller corners than the widget itself, as nested cards do. */
private fun cardRadius(c: Corners) = when (c) {
    Corners.Square -> 0f
    Corners.Rounded -> 10f
    Corners.Round -> 18f
}

private fun cardBorderRes(c: Corners) = when (c) {
    Corners.Square -> R.drawable.widget_border_0
    Corners.Rounded -> R.drawable.widget_card_10
    Corners.Round -> R.drawable.widget_card_18
}

/** Text sizes scale with the design's text size; [s] is that factor. */
private class Type(val s: Float, val p: Palette) {
    fun style(size: Float, weight: FontWeight = FontWeight.Normal, color: ColorProvider = p.fg, align: TextAlign? = null) =
        TextStyle(color = color, fontSize = (size * s).sp, fontWeight = weight, textAlign = align)

    fun dp(v: Float): Dp = (v * s).dp
}

private fun color(c: Int) = ColorProvider(Color(c))

/** Opens the app at a route (metric detail, alerts); null when the widget isn't interactive. */
private fun openRoute(context: Context, interactive: Boolean, route: String, arg: String? = null): Action? {
    if (!interactive) return null
    val intent = Intent(context, MainActivity::class.java)
        .setData(Uri.parse("cfusage://$route/${arg.orEmpty()}")) // distinct PendingIntent per target
        .putExtra(MainActivity.EXTRA_ROUTE, route)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    if (arg != null) intent.putExtra(MainActivity.EXTRA_ARG, arg)
    return actionStartActivity(intent)
}

private fun GlanceModifier.onTap(action: Action?) = if (action != null) clickable(action) else this

@Composable
fun DesignContent(design: WidgetDesign, summary: Summary?, interactive: Boolean) {
    val context = LocalContext.current
    val p = palette(design.style, context)
    var root = GlanceModifier.fillMaxSize().cornerRadius(design.style.corners.dp.dp)
    p.bg?.let { root = root.background(it) }
    if (interactive) root = root.clickable(actionStartActivity<MainActivity>())
    Box(root) {
        var inner = GlanceModifier.fillMaxSize()
        if (design.style.border) {
            inner = inner.background(ImageProvider(borderRes(design.style.corners)), colorFilter = ColorFilter.tint(p.border))
        }
        Box(inner.padding(WidgetLayout.PADDING.dp)) {
            if (summary == null) NoData(p) else Blocks(design, summary, p, interactive)
        }
    }
}

@Composable
private fun NoData(p: Palette) {
    val t = Type(1f, p)
    Column(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("CF Usage", style = t.style(14f, FontWeight.Bold))
        Text("Open the app to connect", style = t.style(12f, color = p.muted))
    }
}

@Composable
private fun Blocks(design: WidgetDesign, summary: Summary, p: Palette, interactive: Boolean) {
    val context = LocalContext.current
    val size = LocalSize.current
    val fontScale = context.resources.configuration.fontScale
    val layout = WidgetLayout.layout(design, summary, size.width.value, size.height.value, fontScale)
    val t = Type(layout.scale, p)
    val width = size.width.value - WidgetLayout.PADDING * 2
    val inHeader = WidgetLayout.updatedInHeader(design)
    Column(GlanceModifier.fillMaxSize()) {
        var first = true
        for ((block, alloc) in layout.blocks.take(MAX_BLOCKS)) {
            if (!alloc.shown) continue
            val top = if (first) 0.dp else WidgetLayout.GAP.dp
            val isFirst = first
            first = false
            Box(GlanceModifier.fillMaxWidth().padding(top = top)) {
                when (block) {
                    is HeaderBlock -> Header(block, summary, t, updatedHere = isFirst && inHeader)
                    is SectionsBlock -> Sections(block, summary, alloc.rows, width, t)
                    is CardsBlock -> ProductCards(block, summary, alloc.rows.toFloat(), width, design.style, t)
                    is ListBlock -> MetricList(block, summary, alloc.rows, t)
                    is MetricBlock -> Spotlight(block, summary, alloc, width, t, openRoute(context, interactive, "metric", block.key))
                    is TopBlock -> Top(block, summary, alloc.rows, width, t)
                    is BillBlock -> Bill(block, summary, t)
                    is StatusBlock -> Status(block, summary, t, openRoute(context, interactive, "alerts"))
                }
            }
        }
        if (layout.footer) {
            Spacer(GlanceModifier.defaultWeight())
            Updated(summary, t)
        }
    }
}

@Composable
private fun Updated(summary: Summary, t: Type) {
    val last = Times.parse(summary.lastPollAt)
    val every = LocalContext.current.container.streamEverySeconds() * 1000L
    val stale = FreshnessRules.widgetStale(System.currentTimeMillis(), last?.toEpochMilli(), every)
    val text = last?.let { "updated ${Times.hhmm(it)}" } ?: "no data"
    Text(text, style = t.style(10f, color = if (stale) color(WidgetCharts.RED) else t.p.muted), maxLines = 1)
}

@Composable
private fun Dot(c: Int, sizeDp: Dp = 7.dp) {
    Box(GlanceModifier.size(sizeDp).cornerRadius(sizeDp / 2).background(color(c))) {}
}

/** Ring, dot or bar for one metric, sized for compact rows. */
@Composable
private fun IndicatorView(m: Metric?, kind: Indicator, colorInt: Int, t: Type, ringDp: Float) {
    val context = LocalContext.current
    when (kind) {
        Indicator.Ring -> {
            val px = (ringDp * t.s * WidgetCharts.scale(context)).toInt()
            val bmp = WidgetCharts.ring(px, m?.pct ?: 0.0, colorInt, t.p.dark, strokeRatio = 0.18f)
            Image(ImageProvider(bmp), contentDescription = m?.let { Fmt.pct(it.pct) }, modifier = GlanceModifier.size(t.dp(ringDp)))
        }
        Indicator.Dot -> Dot(colorInt, t.dp(6f))
        Indicator.Bar -> LinearProgressIndicator(
            progress = (m?.pct ?: 0.0).toFloat().coerceIn(0f, 1f),
            modifier = GlanceModifier.width(t.dp(16f)).height(3.dp).cornerRadius(2.dp),
            color = color(colorInt),
            backgroundColor = t.p.track,
        )
        Indicator.None -> {}
    }
}

private fun metricColor(m: Metric?, mode: ColorMode, p: Palette): Int {
    val c = WidgetCharts.metricColor(m?.status ?: "ok", mode, p.accent)
    return if (c == WidgetCharts.ZINC && p.dark) 0xFFA1A1AA.toInt() else c
}

@Composable
private fun Header(b: HeaderBlock, s: Summary, t: Type, updatedHere: Boolean) {
    Column(GlanceModifier.fillMaxWidth()) {
        Row(GlanceModifier.fillMaxWidth().height(t.dp(WidgetLayout.TITLE)), verticalAlignment = Alignment.CenterVertically) {
            if (b.statusDot) {
                Dot(WidgetCharts.statusColor(s.status))
                Spacer(GlanceModifier.width(6.dp))
            }
            Text(b.title, style = t.style(13f, FontWeight.Medium), maxLines = 1, modifier = GlanceModifier.defaultWeight())
            if (b.bill) Text(Fmt.usd(s.estimatedBillUsd), style = t.style(13f, FontWeight.Bold), maxLines = 1)
            else if (updatedHere) Updated(s, t)
        }
        if (b.cycle) Text(WidgetText.cycleLine(s), style = t.style(11f, color = t.p.muted), maxLines = 1)
    }
}

@Composable
private fun Sections(b: SectionsBlock, s: Summary, rows: Int, width: Float, t: Type) {
    val products = b.products.filter { it.metrics.isNotEmpty() }
    when (b.layout) {
        SectionLayout.Rows -> SectionRows(b, s, rows, width, t)
        SectionLayout.Columns -> SectionColumns(b, products.take(6), s, rows, width, t)
        SectionLayout.Tiles -> SectionTiles(b, products, s, rows, width, t)
    }
}

@Composable
private fun SectionRows(b: SectionsBlock, s: Summary, rows: Int, width: Float, t: Type) {
    val plan = WidgetLayout.sectionRows(b, width, t.s)
    val indicator = if (plan.indicator) b.indicator else Indicator.None
    // One line per product, or several when its items wrap; the label sits on the first.
    val lines = b.products.filter { it.metrics.isNotEmpty() }.flatMap { prod ->
        prod.metrics.chunked(plan.perLine).mapIndexed { i, keys -> Triple(prod.group, i == 0, keys) }
    }.take(minOf(rows, MAX_ROWS))
    Column(GlanceModifier.fillMaxWidth()) {
        for ((group, first, keys) in lines) {
            Row(GlanceModifier.fillMaxWidth().height(t.dp(20f)), verticalAlignment = Alignment.CenterVertically) {
                val label = if (!first) "" else if (plan.compactLabels) MetricCatalog.groupTag(group) else MetricCatalog.groupShort(group)
                Text(label, style = t.style(10f, FontWeight.Medium, t.p.muted), maxLines = 1, modifier = GlanceModifier.width(plan.labelW.dp))
                for (key in keys) SectionItem(s.metric(key), key, b, indicator, t)
                repeat(plan.perLine - keys.size) { Spacer(GlanceModifier.defaultWeight()) }
            }
        }
    }
}

@Composable
private fun RowScope.SectionItem(m: Metric?, key: String, b: SectionsBlock, indicator: Indicator, t: Type) {
    Row(GlanceModifier.defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
        if (indicator != Indicator.None) {
            IndicatorView(m, indicator, metricColor(m, b.colors, t.p), t, 13f)
            Spacer(GlanceModifier.width(3.dp))
        }
        Text(MetricCatalog.short(key), style = t.style(10f, color = t.p.muted), maxLines = 1)
        Spacer(GlanceModifier.width(3.dp))
        Text(WidgetText.value(m, b.value), style = t.style(11f, FontWeight.Medium), maxLines = 1)
    }
}

@Composable
private fun SectionColumns(b: SectionsBlock, products: List<ProductSel>, s: Summary, rows: Int, width: Float, t: Type) {
    val colW = width / products.size.coerceAtLeast(1)
    // Narrow columns use two-letter product tags and leave out the rings.
    val compact = products.any { MetricCatalog.groupShort(it.group).length * 6.5f * t.s > colW }
    val indicator = if (colW < 58f * t.s) Indicator.None else b.indicator
    Row(GlanceModifier.fillMaxWidth()) {
        for (prod in products) {
            Column(GlanceModifier.defaultWeight()) {
                Text(
                    if (compact) MetricCatalog.groupTag(prod.group) else MetricCatalog.groupShort(prod.group),
                    style = t.style(10f, FontWeight.Medium, t.p.muted),
                    maxLines = 1,
                    modifier = GlanceModifier.height(t.dp(WidgetLayout.CAPTION)),
                )
                for (key in prod.metrics.take(minOf(rows, 9))) {
                    val m = s.metric(key)
                    Row(GlanceModifier.fillMaxWidth().height(t.dp(18f)), verticalAlignment = Alignment.CenterVertically) {
                        if (indicator != Indicator.None) {
                            IndicatorView(m, indicator, metricColor(m, b.colors, t.p), t, 13f)
                            Spacer(GlanceModifier.width(3.dp))
                        }
                        Text(MetricCatalog.short(key), style = t.style(10f, color = t.p.muted), maxLines = 1)
                        Spacer(GlanceModifier.width(3.dp))
                        Text(WidgetText.value(m, b.value), style = t.style(11f, FontWeight.Medium), maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTiles(b: SectionsBlock, products: List<ProductSel>, s: Summary, rows: Int, width: Float, t: Type) {
    val context = LocalContext.current
    val items = products.flatMap { prod -> prod.metrics.map { prod.group to it } }
    val perRow = WidgetLayout.tilesPerRow(width, t.s).coerceAtMost(9)
    val ringDp = 32f
    Column(GlanceModifier.fillMaxWidth()) {
        for (chunk in items.chunked(perRow).take(minOf(rows, MAX_ROWS))) {
            Row(GlanceModifier.fillMaxWidth().padding(bottom = 4.dp)) {
                for ((group, key) in chunk) {
                    val m = s.metric(key)
                    Column(GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(GlanceModifier.size(t.dp(ringDp)), contentAlignment = Alignment.Center) {
                            if (b.indicator != Indicator.None) {
                                val px = (ringDp * t.s * WidgetCharts.scale(context)).toInt()
                                val bmp = WidgetCharts.ring(px, m?.pct ?: 0.0, metricColor(m, b.colors, t.p), t.p.dark, strokeRatio = 0.12f)
                                Image(ImageProvider(bmp), contentDescription = null, modifier = GlanceModifier.fillMaxSize())
                            }
                            Text(m?.let { Fmt.pct(it.pct) } ?: "–", style = t.style(9f, FontWeight.Bold), maxLines = 1)
                        }
                        Spacer(GlanceModifier.height(2.dp))
                        Text(
                            "${MetricCatalog.groupShort(group)} ${MetricCatalog.short(key)}",
                            style = t.style(10f, color = t.p.muted, align = TextAlign.Center),
                            maxLines = 1,
                        )
                        if (b.value != ValueStyle.Percent) {
                            Text(WidgetText.value(m, b.value), style = t.style(10f, FontWeight.Medium, align = TextAlign.Center), maxLines = 1)
                        }
                    }
                }
                repeat(perRow - chunk.size) { Spacer(GlanceModifier.defaultWeight()) }
            }
        }
    }
}

@Composable
private fun MetricList(b: ListBlock, s: Summary, rows: Int, t: Type) {
    val names = WidgetText.names(b.metrics)
    val ringDp = 14f
    Column(GlanceModifier.fillMaxWidth()) {
        for (key in b.metrics.take(minOf(rows, MAX_ROWS))) {
            val m = s.metric(key)
            val c = metricColor(m, ColorMode.Status, t.p)
            Column(GlanceModifier.fillMaxWidth().padding(bottom = 2.dp)) {
                Row(GlanceModifier.fillMaxWidth().height(t.dp(WidgetLayout.BODY)), verticalAlignment = Alignment.CenterVertically) {
                    if (b.indicator == Indicator.Ring || b.indicator == Indicator.Dot) {
                        IndicatorView(m, b.indicator, c, t, ringDp)
                        Spacer(GlanceModifier.width(6.dp))
                    }
                    Text(names[key] ?: key, style = t.style(12f), maxLines = 1, modifier = GlanceModifier.defaultWeight())
                    Text(WidgetText.value(m, b.value), style = t.style(12f, FontWeight.Medium), maxLines = 1)
                }
                val second = m?.let { WidgetText.secondary(it, b) }.orEmpty()
                if (second.isNotEmpty()) {
                    Row(GlanceModifier.fillMaxWidth().height(t.dp(WidgetLayout.MICRO))) {
                        if (b.indicator == Indicator.Ring || b.indicator == Indicator.Dot) {
                            Spacer(GlanceModifier.width(t.dp(if (b.indicator == Indicator.Ring) ringDp else 6f) + 6.dp))
                        }
                        val hot = (m?.projectedPct ?: 0.0) >= 1.0 && b.showProjected
                        Text(second, style = t.style(10f, color = if (hot) color(WidgetCharts.RED) else t.p.muted), maxLines = 1)
                    }
                }
                if (b.indicator == Indicator.Bar) {
                    LinearProgressIndicator(
                        progress = (m?.pct ?: 0.0).toFloat().coerceIn(0f, 1f),
                        modifier = GlanceModifier.fillMaxWidth().height(3.dp).cornerRadius(2.dp),
                        color = color(c),
                        backgroundColor = t.p.track,
                    )
                }
            }
        }
    }
}

@Composable
private fun Spotlight(b: MetricBlock, s: Summary, alloc: WidgetLayout.Alloc, width: Float, t: Type, tap: Action?) {
    val context = LocalContext.current
    val m = s.metric(b.key)
    if (m == null) {
        Text("${MetricCatalog.label(b.key)}: no data", style = t.style(12f, color = t.p.muted))
        return
    }
    val density = WidgetCharts.scale(context)
    val colorInt = metricColor(m, ColorMode.Accent, t.p)
    Column(GlanceModifier.fillMaxWidth().onTap(tap)) {
        Text(m.label, style = t.style(11f, FontWeight.Medium, t.p.muted), maxLines = 1)
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(GlanceModifier.defaultWeight()) {
                Text(MetricText.short(m.used, m.unit), style = t.style(22f, FontWeight.Bold), maxLines = 1)
                Text("${Fmt.pct(m.pct)} of ${MetricText.short(m.included, m.unit)}", style = t.style(11f, color = t.p.muted), maxLines = 1)
                if (b.showProjected) {
                    val hot = m.projectedPct >= 1.0
                    Text(MetricText.projection(m), style = t.style(11f, color = if (hot) color(WidgetCharts.RED) else t.p.muted), maxLines = 1)
                }
            }
            if (b.visual == MetricVisual.Ring) {
                val ringDp = 52f
                val px = (ringDp * t.s * density).toInt()
                Box(GlanceModifier.size(t.dp(ringDp)), contentAlignment = Alignment.Center) {
                    Image(ImageProvider(WidgetCharts.ring(px, m.pct, colorInt, t.p.dark)), contentDescription = null, modifier = GlanceModifier.fillMaxSize())
                    Text(Fmt.pct(m.pct), style = t.style(11f, FontWeight.Bold), maxLines = 1)
                }
            }
        }
        if (alloc.chart > 0f && b.visual != MetricVisual.Ring && b.visual != MetricVisual.None) {
            val values = WidgetText.chartValues(m, b.visual)
            val wPx = (width * density).toInt()
            val hPx = (alloc.chart * density).toInt()
            val bmp = if (b.visual == MetricVisual.Bars) {
                WidgetCharts.bars(wPx, hPx, values, colorInt, density)
            } else {
                WidgetCharts.area(wPx, hPx, values, colorInt, density, WidgetText.reference(m, b.visual, values))
            }
            Spacer(GlanceModifier.height(4.dp))
            Image(
                ImageProvider(bmp),
                contentDescription = null,
                modifier = GlanceModifier.fillMaxWidth().height(alloc.chart.dp),
                contentScale = ContentScale.FillBounds,
            )
        }
    }
}

@Composable
private fun Top(b: TopBlock, s: Summary, rows: Int, width: Float, t: Type) {
    val context = LocalContext.current
    val density = WidgetCharts.scale(context)
    val m = s.metric(b.key)
    val top = m?.top.orEmpty().take(minOf(rows, b.count, 9))
    val max = top.maxOfOrNull { it.value }?.takeIf { it > 0 } ?: 1.0
    val total = m?.used?.takeIf { it > 0 } ?: 1.0
    val accent = WidgetCharts.accentColor(t.p.accent)
    Column(GlanceModifier.fillMaxWidth()) {
        Row(GlanceModifier.fillMaxWidth().height(t.dp(WidgetLayout.TITLE)), verticalAlignment = Alignment.CenterVertically) {
            Text(WidgetText.topTitle(b.key), style = t.style(12f, FontWeight.Medium), maxLines = 1, modifier = GlanceModifier.defaultWeight())
            if (m != null) Text("${MetricText.short(m.used, m.unit)} · ${Fmt.pct(m.pct)}", style = t.style(10f, color = t.p.muted), maxLines = 1)
        }
        if (top.isEmpty()) {
            Text("Nothing used yet", style = t.style(11f, color = t.p.muted), maxLines = 1)
            return@Column
        }
        val rowH = 20f * t.s
        val wPx = (width * density).toInt()
        val hPx = (rowH * density).toInt()
        for (item in top) {
            Box(GlanceModifier.fillMaxWidth().height(t.dp(22f)).padding(vertical = 1.dp)) {
                if (b.bars) {
                    val bmp = WidgetCharts.bar(wPx, hPx, item.value / max, accent, t.p.dark, density)
                    Image(ImageProvider(bmp), contentDescription = null, modifier = GlanceModifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                }
                Row(GlanceModifier.fillMaxSize().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(item.name, style = t.style(11f), maxLines = 1, modifier = GlanceModifier.defaultWeight())
                    val v = if (b.share) Fmt.pct(item.value / total) else MetricText.short(item.value, m?.unit ?: "count")
                    Text(v, style = t.style(11f, FontWeight.Medium), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun Bill(b: BillBlock, s: Summary, t: Type) {
    Column(GlanceModifier.fillMaxWidth()) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(GlanceModifier.defaultWeight()) {
                Text("Estimated bill", style = t.style(11f, color = t.p.muted), maxLines = 1)
                Text(Fmt.usd(s.estimatedBillUsd), style = t.style(22f, FontWeight.Bold), maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${s.cycle.daysLeft.toInt()} days left", style = t.style(11f, color = t.p.muted), maxLines = 1)
                Text("resets ${Times.utcDate(s.cycle.end)}", style = t.style(11f, color = t.p.muted), maxLines = 1)
            }
        }
        if (b.cycleProgress) {
            Spacer(GlanceModifier.height(4.dp))
            LinearProgressIndicator(
                progress = s.cycle.elapsed.toFloat().coerceIn(0f, 1f),
                modifier = GlanceModifier.fillMaxWidth().height(4.dp).cornerRadius(2.dp),
                color = t.p.fg,
                backgroundColor = t.p.track,
            )
            Spacer(GlanceModifier.height(2.dp))
        }
        if (b.overage) {
            val over = (s.estimatedBillUsd - 5.0).coerceAtLeast(0.0)
            Text(
                if (over > 0) "$5 base + ${Fmt.usd(over)} overage" else "Nothing billable projected",
                style = t.style(11f, color = if (over > 0) color(WidgetCharts.RED) else t.p.muted),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun Status(b: StatusBlock, s: Summary, t: Type, tap: Action?) {
    val bad = (b.alerts && s.alerts.isNotEmpty()) || (b.kills && s.kills.isNotEmpty())
    Row(GlanceModifier.fillMaxWidth().height(t.dp(WidgetLayout.BODY)).onTap(tap), verticalAlignment = Alignment.CenterVertically) {
        Dot(if (bad) WidgetCharts.RED else WidgetCharts.GREEN)
        Spacer(GlanceModifier.width(6.dp))
        Text(WidgetText.status(s, b), style = t.style(12f, if (bad) FontWeight.Medium else FontWeight.Normal), maxLines = 1)
    }
}

@Composable
private fun ProductCards(b: CardsBlock, s: Summary, height: Float, width: Float, style: WidgetStyle, t: Type) {
    val fits = CardsLayout.fit(b, s, width, height, t.s)
    if (fits.isEmpty()) {
        Text("Nothing in use yet", style = t.style(11f, color = t.p.muted), maxLines = 1)
        return
    }
    val d = CardsLayout.dims(b.density, t.s)
    Column(GlanceModifier.fillMaxWidth()) {
        fits.forEachIndexed { i, f ->
            Box(GlanceModifier.fillMaxWidth().padding(top = if (i == 0) 0.dp else d.gap.dp)) {
                ProductCard(f, b, d, width, style, t)
            }
        }
    }
}

/** One Overview card: name, overage and status; a ring per metric in use; then the rows. */
@Composable
private fun ProductCard(f: CardsLayout.Fit, b: CardsBlock, d: CardsLayout.Dims, width: Float, style: WidgetStyle, t: Type) {
    val p = t.p
    var outer = GlanceModifier.fillMaxWidth().cornerRadius(cardRadius(style.corners).dp)
    // With no widget background the cards float on the wallpaper, so they carry their own.
    if (style.opacity <= 0) outer = outer.background(p.solid)
    Box(outer) {
        Box(GlanceModifier.fillMaxWidth().background(ImageProvider(cardBorderRes(style.corners)), colorFilter = ColorFilter.tint(p.border))) {
            Column(GlanceModifier.fillMaxWidth().padding(start = d.padH.dp, end = d.padH.dp, top = (d.padTop + 1f).dp, bottom = (d.padBottom + 1f).dp)) {
                CardHeader(f.card, d, t)
                if (f.rings.isNotEmpty()) RingStrip(f.rings, b.density, d, t)
                if (f.note) {
                    Text("Not used this cycle", style = t.style(11f, color = p.muted), maxLines = 1, modifier = GlanceModifier.height(d.note.dp))
                }
                if (f.rows.isNotEmpty()) CardRows(f.rows, b, d, CardsLayout.columns(b, f.rows, width, t.s), t)
            }
        }
    }
}

@Composable
private fun CardHeader(c: CardsLayout.Card, d: CardsLayout.Dims, t: Type) {
    val overage = c.metrics.sumOf { it.overage.usd }
    Row(GlanceModifier.fillMaxWidth().height(d.header.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(c.group, style = t.style(13f, FontWeight.Medium), maxLines = 1, modifier = GlanceModifier.defaultWeight())
        if (overage > 0) {
            Text("+${Fmt.usd(overage)}", style = t.style(10f, FontWeight.Bold, color(WidgetCharts.RED)), maxLines = 1)
            Spacer(GlanceModifier.width(6.dp))
        }
        StatusPill(worstStatus(c.metrics), t)
    }
}

/** The Overview's status badge: an outlined pill with a dot, or solid red once critical. */
@Composable
private fun StatusPill(status: String, t: Type) {
    val label = statusLabel(status)
    if (status == "over" || status == "critical") {
        Box(GlanceModifier.background(color(WidgetCharts.RED)).cornerRadius(9.dp).padding(horizontal = 7.dp, vertical = 1.dp)) {
            Text(label, style = t.style(10f, FontWeight.Medium, color(0xFFFFFFFF.toInt())), maxLines = 1)
        }
        return
    }
    Box(
        GlanceModifier.background(ImageProvider(R.drawable.widget_pill), colorFilter = ColorFilter.tint(t.p.border))
            .padding(horizontal = 7.dp, vertical = 1.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(WidgetCharts.statusColor(status), 6.dp)
            Spacer(GlanceModifier.width(4.dp))
            Text(label, style = t.style(10f, FontWeight.Medium), maxLines = 1)
        }
    }
}

/** A ring per metric in use with its percent inside and its code underneath. */
@Composable
private fun RingStrip(rings: List<Metric>, density: Density, d: CardsLayout.Dims, t: Type) {
    val context = LocalContext.current
    val compact = density == Density.Compact
    val gap = if (compact) 1f else 2f
    Row(GlanceModifier.fillMaxWidth().padding(top = (3f * t.s).dp, bottom = (2f * t.s).dp)) {
        for (m in rings) {
            Column(GlanceModifier.width(d.tile.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(GlanceModifier.size(d.ring.dp), contentAlignment = Alignment.Center) {
                    val px = (d.ring * WidgetCharts.scale(context)).toInt()
                    val bmp = WidgetCharts.ring(px, m.pct, metricColor(m, ColorMode.Accent, t.p), t.p.dark, strokeRatio = 0.12f)
                    Image(ImageProvider(bmp), contentDescription = "${m.label} ${Fmt.pct(m.pct)}", modifier = GlanceModifier.fillMaxSize())
                    Text(Fmt.pct(m.pct), style = t.style(if (compact) 7.5f else 9f, FontWeight.Bold), maxLines = 1)
                }
                Spacer(GlanceModifier.height((gap * t.s).dp))
                Text(
                    MetricCatalog.short(m.key),
                    style = t.style(if (compact) 9f else 10f, FontWeight.Medium, t.p.muted, TextAlign.Center),
                    maxLines = 1,
                    modifier = GlanceModifier.height(d.code.dp),
                )
            }
        }
    }
}

/**
 * The Overview's rows: code badge, name, used / included and percent, with the projection on a
 * second line (Comfortable) or as a short last column (Compact). Each row has a hairline above it.
 */
@Composable
private fun CardRows(rows: List<Metric>, b: CardsBlock, d: CardsLayout.Dims, cols: CardsLayout.Columns, t: Type) {
    val compact = b.density == Density.Compact
    val size = if (compact) 11f else 12f
    val padTop = (if (compact) 2f else 4f) * t.s
    val padBottom = (if (compact) 2f else 4f) * t.s
    Column(GlanceModifier.fillMaxWidth()) {
        for (m in rows) {
            val hot = b.showProjected && m.projectedPct >= 1.0
            Column(GlanceModifier.fillMaxWidth()) {
                Box(GlanceModifier.fillMaxWidth().height(1.dp).background(t.p.border)) {}
                Column(GlanceModifier.fillMaxWidth().padding(top = padTop.dp, bottom = padBottom.dp)) {
                Row(
                    GlanceModifier.fillMaxWidth().height((16f * t.s).dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        GlanceModifier.width(cols.badge.dp).height((14f * t.s).dp).background(t.p.track).cornerRadius(4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(MetricCatalog.short(m.key), style = t.style(if (compact) 9f else 10f, FontWeight.Bold), maxLines = 1)
                    }
                    Spacer(GlanceModifier.width(8.dp))
                    if (cols.name) {
                        Text(MetricCatalog.name(m.key), style = t.style(size), maxLines = 1, modifier = GlanceModifier.defaultWeight())
                    } else {
                        Spacer(GlanceModifier.defaultWeight())
                    }
                    if (cols.ofTotal) {
                        Text(MetricText.ofTotal(m), style = t.style(size, color = t.p.muted), maxLines = 1)
                    }
                    Text(
                        Fmt.pct(m.pct),
                        style = t.style(size, FontWeight.Medium, align = TextAlign.End),
                        maxLines = 1,
                        modifier = GlanceModifier.width(cols.pct.dp),
                    )
                    if (compact && b.showProjected) {
                        Text(
                            MetricText.projectionPct(m),
                            style = t.style(10f, color = if (hot) color(WidgetCharts.RED) else t.p.muted, align = TextAlign.End),
                            maxLines = 1,
                            modifier = GlanceModifier.width(cols.proj.dp),
                        )
                    }
                }
                if (!compact && b.showProjected) {
                    Text(
                        MetricText.projectionLine(m),
                        style = t.style(10f, color = if (hot) color(WidgetCharts.RED) else t.p.muted),
                        maxLines = 1,
                        modifier = GlanceModifier.height(d.projLine.dp).padding(start = (cols.badge + 8f).dp),
                    )
                }
                }
            }
        }
    }
}
