package io.github.pablocollantes.cfusage.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.LruCache
import io.github.pablocollantes.cfusage.ui.charts.ChartMath
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Widget charts drawn with android.graphics into bitmaps, in the same style as the app's Compose
 * charts (Glance can't run a Compose Canvas). Bitmaps carry no text: labels stay Glance Text so
 * they follow the system font and theme. [dark] is the widget's resolved theme, which can differ
 * from the system's when a design forces light or dark.
 */
object WidgetCharts {
    const val BLUE = 0xFF2563EB.toInt()
    const val GREEN = 0xFF16A34A.toInt()
    const val AMBER = 0xFFF59E0B.toInt()
    const val RED = 0xFFDC2626.toInt()
    const val VIOLET = 0xFF8B5CF6.toInt()
    const val ZINC = 0xFF71717A.toInt()

    /**
     * Pixels per dp for widget bitmaps. Capped below the screen density: every update ships the
     * bitmaps through a binder transaction, and soft-edged flat shapes look the same at 1.5×.
     */
    fun scale(context: Context) = context.resources.displayMetrics.density.coerceAtMost(1.5f)

    fun isNight(context: Context) =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun trackColor(dark: Boolean) = if (dark) 0xFF27272A.toInt() else 0xFFF4F4F5.toInt()

    fun statusColor(status: String) = when (status) {
        "over", "critical" -> RED
        "warn" -> AMBER
        "info" -> BLUE
        else -> GREEN
    }

    fun accentColor(accent: Accent) = when (accent) {
        Accent.Blue -> BLUE
        Accent.Green -> GREEN
        Accent.Violet -> VIOLET
        Accent.Amber -> AMBER
        Accent.Zinc -> ZINC
    }

    /** A metric's color: traffic light, or the accent until it reaches warning. */
    fun metricColor(status: String, mode: ColorMode, accent: Accent) = when {
        mode == ColorMode.Status -> statusColor(status)
        status == "warn" -> AMBER
        status == "over" || status == "critical" -> RED
        else -> accentColor(accent)
    }

    // Identical rings share one Bitmap: RemoteViews ships each distinct Bitmap object once.
    private val rings = LruCache<String, Bitmap>(96)

    /** Ring progress, round cap, muted track. [strokeRatio] is the stroke as a share of the size. */
    fun ring(sizePx: Int, fraction: Double, color: Int, dark: Boolean, strokeRatio: Float = 0.11f): Bitmap {
        val s = sizePx.coerceIn(12, 600)
        val f = (fraction.toFloat().coerceIn(0f, 1f) * 200).roundToInt() / 200f
        val key = "$s|$f|$color|$dark|$strokeRatio"
        rings.get(key)?.let { return it }
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val stroke = max(1.5f, s * strokeRatio)
        val r = RectF(stroke / 2, stroke / 2, s - stroke / 2, s - stroke / 2)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke }
        p.color = trackColor(dark)
        c.drawArc(r, 0f, 360f, false, p)
        if (f > 0f) {
            p.color = color
            p.strokeCap = Paint.Cap.ROUND
            c.drawArc(r, -90f, max(360f * f, 3f), false, p)
        }
        rings.put(key, bmp)
        return bmp
    }

    /** Area chart with gradient fill and a monotone line; optional dashed reference line. */
    fun area(w: Int, h: Int, values: List<Double>, color: Int, density: Float, reference: Double? = null): Bitmap {
        val width = w.coerceIn(40, 1200)
        val height = h.coerceIn(20, 600)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        if (values.isEmpty()) return bmp
        val c = Canvas(bmp)
        val pad = 2f * density
        val mx = max(values.maxOrNull() ?: 0.0, reference ?: 0.0).takeIf { it > 0 } ?: 1.0
        val n = values.size
        val xs = FloatArray(n) { if (n == 1) width / 2f else width * it / (n - 1).toFloat() }
        val ys = FloatArray(n) { height - pad - (values[it] / mx).toFloat() * (height - pad * 2) }
        val m = ChartMath.monotoneTangents(xs, ys)
        val line = Path().apply {
            moveTo(xs[0], ys[0])
            for (i in 0 until n - 1) {
                val dx = (xs[i + 1] - xs[i]) / 3f
                cubicTo(xs[i] + dx, ys[i] + m[i] * dx, xs[i + 1] - dx, ys[i + 1] - m[i + 1] * dx, xs[i + 1], ys[i + 1])
            }
        }
        val fill = Path(line).apply { lineTo(xs.last(), height.toFloat()); lineTo(xs.first(), height.toFloat()); close() }
        val fp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 0f, height.toFloat(), withAlpha(color, 0.32f), withAlpha(color, 0.02f), Shader.TileMode.CLAMP)
        }
        c.drawPath(fill, fp)
        val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2f * density; this.color = color
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        c.drawPath(line, lp)
        if (reference != null && reference <= mx) {
            val y = height - pad - (reference / mx).toFloat() * (height - pad * 2)
            val dp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 1.5f * density; this.color = RED
                pathEffect = DashPathEffect(floatArrayOf(5f * density, 4f * density), 0f)
            }
            c.drawLine(0f, y, width.toFloat(), y, dp)
        }
        return bmp
    }

    /** Daily bars, rounded tops; the last (today, still filling) bar is drawn lighter. */
    fun bars(w: Int, h: Int, values: List<Double>, color: Int, density: Float): Bitmap {
        val width = w.coerceIn(40, 1200)
        val height = h.coerceIn(20, 600)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        if (values.isEmpty()) return bmp
        val c = Canvas(bmp)
        val mx = values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        val slot = width / values.size.toFloat()
        val barW = min(slot * 0.7f, 14f * density)
        val radius = min(barW / 3f, 3f * density)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        values.forEachIndexed { i, v ->
            val bh = max(if (v > 0) 2f * density else 0f, (v / mx).toFloat() * (height - 1f))
            if (bh <= 0f) return@forEachIndexed
            val x = slot * i + (slot - barW) / 2f
            p.color = if (i == values.lastIndex) withAlpha(color, 0.5f) else color
            c.drawRoundRect(RectF(x, height - bh, x + barW, height.toFloat()), radius, radius, p)
            if (bh > radius) c.drawRect(RectF(x, height - radius, x + barW, height.toFloat()), p)
        }
        return bmp
    }

    /** One bar-list row background: a tinted rounded bar covering [fraction] of the width. */
    fun bar(w: Int, h: Int, fraction: Double, color: Int, dark: Boolean, density: Float): Bitmap {
        val width = w.coerceIn(10, 1200)
        val height = h.coerceIn(4, 200)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val radius = min(6f * density, height / 2f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = withAlpha(color, if (dark) 0.28f else 0.16f) }
        val fw = (width * fraction.toFloat().coerceIn(0.02f, 1f))
        c.drawRoundRect(RectF(0f, 0f, fw, height.toFloat()), radius, radius, p)
        return bmp
    }

    private fun withAlpha(color: Int, a: Float) = (color and 0x00FFFFFF) or ((a * 255).toInt().coerceIn(0, 255) shl 24)
}
