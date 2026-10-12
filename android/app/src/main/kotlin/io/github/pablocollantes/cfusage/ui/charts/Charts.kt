package io.github.pablocollantes.cfusage.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pablocollantes.cfusage.ui.theme.Cf
import io.github.pablocollantes.cfusage.ui.theme.CfColors
import io.github.pablocollantes.cfusage.util.Fmt
import kotlin.math.max

data class Series(val name: String, val values: List<Double>, val color: Color)

private val AxisStyle = TextStyle(fontSize = 10.sp)
private val TipTitle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
private val TipBody = TextStyle(fontSize = 11.sp, fontFeatureSettings = "tnum")

/** Press anywhere on the chart and drag: the selected index follows the finger, cleared on release. */
private fun Modifier.scrubbing(onIndex: (Float?) -> Unit) = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        onIndex(down.position.x)
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull() ?: break
            if (!change.pressed) break
            onIndex(change.position.x)
            // Horizontal drags belong to the chart; vertical ones still scroll the screen.
            val dx = change.position.x - change.previousPosition.x
            val dy = change.position.y - change.previousPosition.y
            if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) change.consume()
        }
        onIndex(null)
    }
}

private data class Plot(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

private fun DrawScope.plotArea(tm: TextMeasurer, ticks: ChartMath.Ticks, format: (Double) -> String, showY: Boolean): Plot {
    val yLabelWidth = if (showY) ticks.values.maxOf { tm.measure(format(it), AxisStyle).size.width }.toFloat() + 8.dp.toPx() else 0f
    return Plot(yLabelWidth, 6.dp.toPx(), size.width, size.height - 20.dp.toPx())
}

private fun DrawScope.grid(tm: TextMeasurer, plot: Plot, ticks: ChartMath.Ticks, format: (Double) -> String, colors: CfColors, showY: Boolean) {
    val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
    for (v in ticks.values) {
        val y = plot.bottom - (v / ticks.max).toFloat() * plot.height
        drawLine(colors.border, Offset(plot.left, y), Offset(plot.right, y), 1.dp.toPx(), pathEffect = if (v == 0.0) null else dash)
        if (showY) {
            val layout = tm.measure(format(v), AxisStyle.copy(color = colors.mutedForeground))
            drawText(layout, topLeft = Offset(plot.left - layout.size.width - 6.dp.toPx(), y - layout.size.height / 2f))
        }
    }
}

private fun DrawScope.xLabels(tm: TextMeasurer, plot: Plot, labels: List<String>, xAt: (Int) -> Float, colors: CfColors) {
    if (labels.isEmpty()) return
    for (i in ChartMath.labelIndices(labels.size, 4)) {
        val layout = tm.measure(labels[i], AxisStyle.copy(color = colors.mutedForeground), maxLines = 1)
        val x = (xAt(i) - layout.size.width / 2f).coerceIn(plot.left, plot.right - layout.size.width)
        drawText(layout, topLeft = Offset(x, plot.bottom + 5.dp.toPx()))
    }
}

/** A shadcn-style tooltip card drawn on the canvas next to the cursor. */
private fun DrawScope.tooltip(tm: TextMeasurer, x: Float, plot: Plot, title: String, rows: List<Triple<Color, String, String>>, colors: CfColors) {
    val pad = 8.dp.toPx()
    val swatch = 8.dp.toPx()
    val titleL = tm.measure(title, TipTitle.copy(color = colors.foreground), maxLines = 1)
    val nameLs = rows.map { tm.measure(it.second, TipBody.copy(color = colors.mutedForeground), maxLines = 1, overflow = TextOverflow.Ellipsis) }
    val valLs = rows.map { tm.measure(it.third, TipBody.copy(color = colors.foreground, fontWeight = FontWeight.Medium), maxLines = 1) }
    val rowW = rows.indices.maxOfOrNull { swatch + 6.dp.toPx() + nameLs[it].size.width + 12.dp.toPx() + valLs[it].size.width } ?: 0f
    val w = max(titleL.size.width.toFloat(), rowW) + pad * 2
    val lineH = (valLs.maxOfOrNull { it.size.height } ?: 0).toFloat() + 2.dp.toPx()
    val h = pad * 2 + titleL.size.height + rows.size * lineH + if (rows.isNotEmpty()) 4.dp.toPx() else 0f
    val gap = 10.dp.toPx()
    val left = if (x + gap + w <= plot.right) x + gap else (x - gap - w).coerceAtLeast(0f)
    val top = plot.top
    drawRoundRect(Color.Black.copy(alpha = if (colors.dark) 0.5f else 0.08f), Offset(left, top + 2.dp.toPx()), Size(w, h), CornerRadius(8.dp.toPx()))
    drawRoundRect(colors.background, Offset(left, top), Size(w, h), CornerRadius(8.dp.toPx()))
    drawRoundRect(colors.border, Offset(left, top), Size(w, h), CornerRadius(8.dp.toPx()), style = Stroke(1.dp.toPx()))
    drawText(titleL, topLeft = Offset(left + pad, top + pad))
    var y = top + pad + titleL.size.height + 4.dp.toPx()
    rows.forEachIndexed { i, (color, _, _) ->
        drawRoundRect(color, Offset(left + pad, y + (lineH - swatch) / 2f), Size(swatch, swatch), CornerRadius(2.dp.toPx()))
        drawText(nameLs[i], topLeft = Offset(left + pad + swatch + 6.dp.toPx(), y))
        drawText(valLs[i], topLeft = Offset(left + w - pad - valLs[i].size.width, y))
        y += lineH
    }
}

/** Monotone cubic through the points, the "type=monotone" curve of shadcn's area charts. */
internal fun monotonePath(xs: FloatArray, ys: FloatArray): Path {
    val path = Path()
    if (xs.isEmpty()) return path
    path.moveTo(xs[0], ys[0])
    val m = ChartMath.monotoneTangents(xs, ys)
    for (i in 0 until xs.size - 1) {
        val dx = (xs[i + 1] - xs[i]) / 3f
        path.cubicTo(xs[i] + dx, ys[i] + m[i] * dx, xs[i + 1] - dx, ys[i + 1] - m[i + 1] * dx, xs[i + 1], ys[i + 1])
    }
    return path
}

/**
 * Area chart, optionally stacked, with dashed grid, muted axes, an optional dashed reference line
 * (e.g. the included allowance) and a press-and-drag tooltip.
 */
@Composable
fun AreaChart(
    series: List<Series>,
    labels: List<String>,
    axisLabels: List<String> = labels,
    format: (Double) -> String = Fmt::compact,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
    stacked: Boolean = false,
    reference: Pair<Double, String>? = null,
    showYAxis: Boolean = true,
) {
    val colors = Cf.colors
    val tm = rememberTextMeasurer()
    var touchX by remember { mutableStateOf<Float?>(null) }
    val n = series.maxOfOrNull { it.values.size } ?: 0
    Canvas(modifier.fillMaxWidth().height(height).scrubbing { touchX = it }) {
        if (n == 0) return@Canvas
        val tops = Array(series.size) { DoubleArray(n) }
        for (i in 0 until n) {
            var acc = 0.0
            series.forEachIndexed { s, ser ->
                val v = ser.values.getOrElse(i) { 0.0 }
                acc = if (stacked) acc + v else v
                tops[s][i] = acc
            }
        }
        val dataMax = tops.maxOf { it.maxOrNull() ?: 0.0 }
        // The reference joins the scale only when it is near the data; otherwise it would flatten it.
        val ref = reference?.takeIf { it.first <= max(dataMax, 1e-9) * 1.6 }
        val ticks = ChartMath.niceTicks(max(dataMax, ref?.first ?: 0.0))
        val plot = plotArea(tm, ticks, format, showYAxis)
        grid(tm, plot, ticks, format, colors, showYAxis)
        val xAt = { i: Int -> if (n == 1) plot.left + plot.width / 2 else plot.left + plot.width * i / (n - 1) }
        val yAt = { v: Double -> plot.bottom - (v / ticks.max).toFloat() * plot.height }
        val xs = FloatArray(n) { xAt(it) }
        // Draw back to front so the first series sits on top when not stacked.
        for (s in series.indices.reversed()) {
            val color = series[s].color
            val ys = FloatArray(n) { yAt(tops[s][it]) }
            if (n == 1) {
                drawCircle(color, 3.dp.toPx(), Offset(xs[0], ys[0]))
                continue
            }
            val line = monotonePath(xs, ys)
            val fill = Path().apply {
                addPath(line)
                lineTo(xs.last(), plot.bottom)
                lineTo(xs.first(), plot.bottom)
                close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.32f), color.copy(alpha = 0.03f)), startY = plot.top, endY = plot.bottom))
            drawPath(line, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        if (ref != null) {
            val y = yAt(ref.first)
            drawLine(colors.chart4, Offset(plot.left, y), Offset(plot.right, y), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
            val l = tm.measure(ref.second, AxisStyle.copy(color = colors.chart4, fontWeight = FontWeight.Medium))
            drawText(l, topLeft = Offset(plot.right - l.size.width, (y - l.size.height - 2.dp.toPx()).coerceAtLeast(0f)))
        }
        xLabels(tm, plot, axisLabels, xAt, colors)
        val tx = touchX
        if (tx != null) {
            val i = ChartMath.nearestIndex(tx - plot.left, plot.width, n)
            val x = xs[i]
            drawLine(colors.mutedForeground.copy(alpha = 0.6f), Offset(x, plot.top), Offset(x, plot.bottom), 1.dp.toPx())
            series.forEachIndexed { s, ser ->
                val y = yAt(tops[s][i])
                drawCircle(colors.background, 4.5.dp.toPx(), Offset(x, y))
                drawCircle(ser.color, 3.dp.toPx(), Offset(x, y))
            }
            tooltip(tm, x, plot, labels.getOrElse(i) { "" }, series.map { Triple(it.color, it.name, format(it.values.getOrElse(i) { 0.0 })) }, colors)
        }
    }
}

/** Bar chart; with several series the bars stack, and only the top segment gets rounded corners. */
@Composable
fun BarChart(
    series: List<Series>,
    labels: List<String>,
    axisLabels: List<String> = labels,
    format: (Double) -> String = Fmt::compact,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
    showYAxis: Boolean = true,
) {
    val colors = Cf.colors
    val tm = rememberTextMeasurer()
    var touchX by remember { mutableStateOf<Float?>(null) }
    val n = series.maxOfOrNull { it.values.size } ?: 0
    Canvas(modifier.fillMaxWidth().height(height).scrubbing { touchX = it }) {
        if (n == 0) return@Canvas
        val totals = DoubleArray(n) { i -> series.sumOf { it.values.getOrElse(i) { 0.0 } } }
        val ticks = ChartMath.niceTicks(totals.maxOrNull() ?: 0.0)
        val plot = plotArea(tm, ticks, format, showYAxis)
        grid(tm, plot, ticks, format, colors, showYAxis)
        val slot = plot.width / n
        val barW = (slot * 0.72f).coerceAtMost(28.dp.toPx())
        val radius = (barW / 3f).coerceAtMost(4.dp.toPx())
        val tx = touchX
        val sel = tx?.let { ChartMath.barIndex(it - plot.left, plot.width, n) }
        if (sel != null) {
            drawRoundRect(colors.muted, Offset(plot.left + slot * sel, plot.top), Size(slot, plot.height), CornerRadius(4.dp.toPx()))
        }
        for (i in 0 until n) {
            val x = plot.left + slot * i + (slot - barW) / 2f
            var base = plot.bottom
            val topSeries = series.indices.lastOrNull { series[it].values.getOrElse(i) { 0.0 } > 0 }
            series.forEachIndexed { s, ser ->
                val v = ser.values.getOrElse(i) { 0.0 }
                if (v <= 0) return@forEachIndexed
                val h = (v / ticks.max).toFloat() * plot.height
                val top = base - h
                val r = if (s == topSeries) radius else 0f
                val path = Path().apply {
                    addRoundRect(
                        RoundRect(
                            left = x, top = top, right = x + barW, bottom = base,
                            topLeftCornerRadius = CornerRadius(r), topRightCornerRadius = CornerRadius(r),
                            bottomLeftCornerRadius = CornerRadius.Zero, bottomRightCornerRadius = CornerRadius.Zero,
                        ),
                    )
                }
                drawPath(path, ser.color)
                base = top
            }
        }
        xLabels(tm, plot, axisLabels, { plot.left + slot * it + slot / 2f }, colors)
        if (sel != null) {
            val rows = series.map { Triple(it.color, it.name, format(it.values.getOrElse(sel) { 0.0 })) }
            tooltip(tm, plot.left + slot * sel + slot / 2f, plot, labels.getOrElse(sel) { "" }, rows, colors)
        }
    }
}

/** Ring progress for "% of allowance", shadcn's radial chart with a rounded end cap. */
@Composable
fun RadialProgress(
    fraction: Double,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    stroke: Dp = 7.dp,
    center: (@Composable () -> Unit)? = null,
) {
    val track = Cf.colors.muted
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = stroke.toPx()
            val arcSize = Size(this.size.width - s, this.size.height - s)
            val tl = Offset(s / 2, s / 2)
            drawArc(track, 0f, 360f, false, tl, arcSize, style = Stroke(s))
            val f = fraction.toFloat().coerceIn(0f, 1f)
            if (f > 0f) drawArc(color, -90f, max(360f * f, 2f), false, tl, arcSize, style = Stroke(s, cap = StrokeCap.Round))
        }
        center?.invoke()
    }
}

@Composable
fun Sparkline(values: List<Double>, color: Color, modifier: Modifier = Modifier, fill: Boolean = true) {
    Canvas(modifier) {
        if (values.size < 2) {
            drawLine(color.copy(alpha = 0.4f), Offset(0f, size.height - 1), Offset(size.width, size.height - 1), 1.dp.toPx())
            return@Canvas
        }
        val mx = values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        val pad = 2.dp.toPx()
        val xs = FloatArray(values.size) { size.width * it / (values.size - 1) }
        val ys = FloatArray(values.size) { size.height - pad - (values[it] / mx).toFloat() * (size.height - pad * 2) }
        val line = monotonePath(xs, ys)
        if (fill) {
            val area = Path().apply { addPath(line); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
            drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.25f), color.copy(alpha = 0f))))
        }
        drawPath(line, color, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** A "bar list": each row's background bar is its share of the largest row. */
data class BarItem(val label: String, val value: Double, val display: String, val leading: String? = null)

@Composable
fun BarList(items: List<BarItem>, color: Color = Cf.colors.chart1, modifier: Modifier = Modifier, onClick: ((BarItem) -> Unit)? = null) {
    val mx = items.maxOfOrNull { it.value }?.takeIf { it > 0 } ?: 1.0
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (item in items) {
            Box(Modifier.fillMaxWidth().height(30.dp)) {
                Box(
                    Modifier.fillMaxWidth((item.value / mx).toFloat().coerceIn(0.01f, 1f)).fillMaxHeight()
                        .clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.16f)),
                )
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 8.dp)
                        .then(if (onClick != null) Modifier.clickable { onClick(item) } else Modifier),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (item.leading != null) {
                        Text(item.leading, style = Cf.type.small)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(item.label, Modifier.weight(1f), style = Cf.type.small, color = Cf.colors.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(8.dp))
                    Text(item.display, style = Cf.type.figure.copy(fontSize = 13.sp), color = Cf.colors.foreground)
                }
            }
        }
    }
}

/** Legend row under multi-series charts. */
@Composable
fun Legend(series: List<Series>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
        series.forEachIndexed { i, s ->
            if (i > 0) Spacer(Modifier.width(16.dp))
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(s.color).align(Alignment.CenterVertically))
            Spacer(Modifier.width(6.dp))
            Text(s.name, style = Cf.type.caption, color = Cf.colors.mutedForeground)
        }
    }
}
