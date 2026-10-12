package io.github.pablocollantes.cfusage.ui.charts

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/** Chart geometry with no Android dependency, shared by Compose charts and widget bitmaps. */
object ChartMath {
    /**
     * Sums consecutive buckets so a series fits [target] points: hourly counts over 7 days become
     * 28 six-hour sums. Summing (not averaging) keeps each point a real count of requests.
     */
    fun downsampleSum(values: List<Double>, target: Int): List<Double> {
        if (target <= 0 || values.isEmpty()) return emptyList()
        if (values.size <= target) return values
        val out = DoubleArray(target)
        // Bucket i covers [i*n/target, (i+1)*n/target): every input lands in exactly one bucket.
        for ((i, v) in values.withIndex()) {
            val b = ((i.toLong() * target) / values.size).toInt().coerceAtMost(target - 1)
            out[b] += v
        }
        return out.toList()
    }

    /**
     * The first input index of each [downsampleSum] bucket, so labels name the right time:
     * input i lands in bucket floor(i * target / size), so bucket b starts at ceil(b * size / target).
     */
    fun bucketStarts(size: Int, target: Int): List<Int> {
        if (size <= 0 || target <= 0) return emptyList()
        if (size <= target) return (0 until size).toList()
        return List(target) { b -> ((b.toLong() * size + target - 1) / target).toInt() }
    }

    /** A "nice" axis maximum and step: 0, 2.5k, 5k, 7.5k, 10k rather than 0, 2.3k, 4.6k… */
    data class Ticks(val max: Double, val step: Double) {
        val values: List<Double> get() = generateSequence(0.0) { it + step }.takeWhile { it <= max + step / 1e6 }.toList()
    }

    fun niceTicks(dataMax: Double, count: Int = 4): Ticks {
        if (dataMax <= 0 || dataMax.isNaN()) return Ticks(1.0, 0.25)
        val rough = dataMax / count
        val mag = 10.0.pow(floor(log10(rough)))
        val norm = rough / mag
        val nice = when {
            norm <= 1 -> 1.0
            norm <= 2 -> 2.0
            norm <= 2.5 -> 2.5
            norm <= 5 -> 5.0
            else -> 10.0
        }
        val step = nice * mag
        return Ticks(ceil(dataMax / step - 1e-9) * step, step)
    }

    /**
     * Tangents for a monotone cubic (Fritsch–Carlson), the curve shadcn/Recharts call "monotone":
     * smooth, but never overshooting into negative counts or fake peaks between points.
     */
    fun monotoneTangents(xs: FloatArray, ys: FloatArray): FloatArray {
        val n = xs.size
        val m = FloatArray(n)
        if (n < 2) return m
        val d = FloatArray(n - 1) { i -> (ys[i + 1] - ys[i]) / max(xs[i + 1] - xs[i], 1e-6f) }
        m[0] = d[0]
        m[n - 1] = d[n - 2]
        for (i in 1 until n - 1) {
            m[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
        }
        for (i in 0 until n - 1) {
            if (d[i] == 0f) {
                m[i] = 0f; m[i + 1] = 0f
                continue
            }
            val a = m[i] / d[i]
            val b = m[i + 1] / d[i]
            val s = a * a + b * b
            if (s > 9f) {
                val t = 3f / kotlin.math.sqrt(s)
                m[i] = t * a * d[i]
                m[i + 1] = t * b * d[i]
            }
        }
        return m
    }

    /** Index of the point closest to x when [count] points are spread evenly over [width]. */
    fun nearestIndex(x: Float, width: Float, count: Int): Int {
        if (count <= 1 || width <= 0f) return 0
        val step = width / (count - 1)
        return floor(x / step + 0.5f).toInt().coerceIn(0, count - 1)
    }

    /** Index of the bar under x when [count] bars share [width] equally. */
    fun barIndex(x: Float, width: Float, count: Int): Int {
        if (count <= 0 || width <= 0f) return 0
        return (x / (width / count)).toInt().coerceIn(0, count - 1)
    }

    /** Which labels to draw on an x axis of [count] points so at most [maxLabels] appear. */
    fun labelIndices(count: Int, maxLabels: Int = 4): List<Int> {
        if (count <= 0) return emptyList()
        if (count <= maxLabels) return (0 until count).toList()
        val step = (count - 1).toDouble() / (maxLabels - 1)
        return (0 until maxLabels).map { (it * step).toInt() }.distinct()
    }
}
