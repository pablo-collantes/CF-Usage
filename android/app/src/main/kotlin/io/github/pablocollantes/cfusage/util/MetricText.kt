package io.github.pablocollantes.cfusage.util

import io.github.pablocollantes.cfusage.data.Metric
import io.github.pablocollantes.cfusage.data.isStorage
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Short metric figures for rows and widgets. Storage is a gauge: `used` is the current size and
 * `projected` the cycle's billed average (GB-month), so its projection is labelled "billed avg".
 */
object MetricText {
    /** 4.92 GB · 347 MB · 5.4M · 1.2M ms. */
    fun short(v: Double, unit: String): String = when (unit) {
        "gb" -> gb(v)
        "ms" -> Fmt.compact(v) + " ms"
        "gbs" -> Fmt.compact(v) + " GB-s"
        else -> Fmt.compact(v)
    }

    /** "5.4M / 50M", "4.92 GB / 10 GB", "672k / 30M ms": the unit appears once for ms and GB-s. */
    fun ofTotal(used: Double, included: Double, unit: String, sep: String = " / "): String = when (unit) {
        "gb" -> gb(used) + sep + gb(included)
        "ms" -> Fmt.compact(used) + sep + Fmt.compact(included) + " ms"
        "gbs" -> Fmt.compact(used) + sep + Fmt.compact(included) + " GB-s"
        else -> Fmt.compact(used) + sep + Fmt.compact(included)
    }

    fun ofTotal(m: Metric, sep: String = " / ") = ofTotal(m.used, m.included, m.unit, sep)

    /** "Projected" for flows, "Billed avg" for storage. */
    fun projectionLabel(m: Metric) = if (m.isStorage) "Billed avg" else "Projected"

    /** "proj. 43.7M · 87%" or "billed avg 3.63 GB · 36%". */
    fun projection(m: Metric): String =
        (if (m.isStorage) "billed avg " else "proj. ") + short(m.projected, m.unit) + " · " + Fmt.pct(m.projectedPct)

    /** A product row's second line: "Proj. 43.7M · 87%", plus " · $1.20 over" when it costs. */
    fun projectionLine(m: Metric): String =
        projection(m).replaceFirstChar { it.uppercase() } + if (m.overage.usd > 0) " · ${Fmt.usd(m.overage.usd)} over" else ""

    /** The projection as a bare percentage for tight spaces: "→87%" or "avg 36%". */
    fun projectionPct(m: Metric): String = (if (m.isStorage) "avg " else "→") + Fmt.pct(m.projectedPct)

    /** GB without trailing zeros, switching to MB under 1 GB: 4.92 GB, 10 GB, 347 MB. */
    fun gb(v: Double): String = when {
        v <= 0.0 -> "0 GB"
        v < 0.001 -> "<1 MB"
        v < 1 -> "${(v * 1000).roundToLong()} MB"
        v < 10 -> trim(String.format(Locale.US, "%.2f", v)) + " GB"
        v < 100 -> trim(String.format(Locale.US, "%.1f", v)) + " GB"
        else -> String.format(Locale.US, "%.0f GB", v)
    }

    private fun trim(s: String) = if ('.' in s) s.trimEnd('0').trimEnd('.') else s
}
