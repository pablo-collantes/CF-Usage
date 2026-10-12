package io.github.pablocollantes.cfusage.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** Number formatting shared by the app and the widgets. Pure, so it is unit tested. */
object Fmt {
    /** 950 · 9.4k · 127k · 1.2M · 25B. Three significant figures at most. */
    fun compact(v: Double): String {
        if (v.isNaN()) return "–"
        val a = abs(v)
        val sign = if (v < 0) "-" else ""
        return when {
            a >= 1e12 -> sign + scaled(a / 1e12) + "T"
            a >= 1e9 -> sign + scaled(a / 1e9) + "B"
            a >= 1e6 -> sign + scaled(a / 1e6) + "M"
            a >= 1e3 -> sign + scaled(a / 1e3) + "k"
            a == 0.0 -> "0"
            a < 1 -> sign + trimZeros(String.format(Locale.US, "%.2f", a))
            a < 10 && a != a.roundToLong().toDouble() -> sign + trimZeros(String.format(Locale.US, "%.1f", a))
            else -> sign + a.roundToLong().toString()
        }
    }

    // 1.23 → "1.2", 12.34 → "12.3", 127.4 → "127". 999.6k rounds to "1000k" rather than "1M",
    // which is good enough for a dashboard.
    private fun scaled(x: Double): String =
        if (x >= 100) x.roundToLong().toString() else trimZeros(String.format(Locale.US, "%.1f", x))

    private fun trimZeros(s: String) = if ('.' in s) s.trimEnd('0').trimEnd('.') else s

    fun gb(v: Double): String = when {
        v <= 0.0 -> "0 GB"
        v < 0.01 -> String.format(Locale.US, "%.0f MB", v * 1000)
        v < 10 -> String.format(Locale.US, "%.2f GB", v)
        v < 100 -> String.format(Locale.US, "%.1f GB", v)
        else -> String.format(Locale.US, "%.0f GB", v)
    }

    fun bytes(v: Double): String = when {
        v >= 1e12 -> String.format(Locale.US, "%.1f TB", v / 1e12)
        v >= 1e9 -> String.format(Locale.US, "%.1f GB", v / 1e9)
        v >= 1e6 -> String.format(Locale.US, "%.1f MB", v / 1e6)
        v >= 1e3 -> String.format(Locale.US, "%.0f kB", v / 1e3)
        else -> String.format(Locale.US, "%.0f B", v)
    }

    /** A metric value in its API unit: count · ms · gbs (GB-seconds) · gb (GB-month). */
    fun unit(v: Double, unit: String): String = when (unit) {
        "ms" -> compact(v) + " ms"
        "gbs" -> compact(v) + " GB-s"
        "gb" -> gb(v)
        else -> compact(v)
    }

    /** 0.0127 → "1.3%", 0.42 → "42%", 0 → "0%", 0.0004 → "<0.1%". */
    fun pct(fraction: Double): String {
        if (fraction.isNaN()) return "–"
        val p = fraction * 100
        return when {
            p == 0.0 -> "0%"
            p < 0.1 -> "<0.1%"
            p < 10 -> trimZeros(String.format(Locale.US, "%.1f", p)) + "%"
            else -> p.roundToLong().toString() + "%"
        }
    }

    fun usd(v: Double): String = when {
        v == 0.0 -> "$0.00"
        v < 0.01 -> "<$0.01"
        v < 1000 -> String.format(Locale.US, "$%.2f", v)
        else -> "$" + compact(v)
    }

    fun ms(v: Double): String = when {
        v < 10 -> String.format(Locale.US, "%.2f ms", v)
        v < 100 -> String.format(Locale.US, "%.1f ms", v)
        else -> compact(v) + " ms"
    }

    /** "3 min ago" style ages. */
    fun ago(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return when {
            s < 45 -> "just now"
            s < 3600 -> "${(s + 30) / 60} min ago"
            s < 86_400 -> "${s / 3600} h ago"
            else -> "${s / 86_400} d ago"
        }
    }
}

/** RFC 3339 helpers. Server times are UTC; display uses the device zone except for cycle dates. */
object Times {
    private val hm = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    private val monthDay = DateTimeFormatter.ofPattern("MMM d", Locale.US)
    private val dayHm = DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.US)
    private val weekdayHour = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.US)

    fun parse(s: String?): Instant? = s?.let { runCatching { Instant.parse(it) }.getOrNull() }
    fun epochMs(s: String?): Long? = parse(s)?.toEpochMilli()

    fun hhmm(i: Instant, zone: ZoneId = ZoneId.systemDefault()): String = hm.format(i.atZone(zone))
    fun dayTime(i: Instant, zone: ZoneId = ZoneId.systemDefault()): String = dayHm.format(i.atZone(zone))

    /** Cycle boundaries are 00:00 UTC, so they are shown as UTC dates ("Sep 23"). */
    fun utcDate(s: String): String = parse(s)?.let { monthDay.format(it.atZone(ZoneOffset.UTC)) } ?: s

    fun isoDate(s: String): String = runCatching { monthDay.format(LocalDate.parse(s)) }.getOrDefault(s)

    /** Axis/tooltip label for a series bucket. */
    fun bucketLabel(t: String, bucket: String, zone: ZoneId = ZoneId.systemDefault()): String {
        val i = parse(t) ?: return isoDate(t)
        return when (bucket) {
            "day" -> monthDay.format(i.atZone(ZoneOffset.UTC))
            "minute5" -> hm.format(i.atZone(zone))
            else -> weekdayHour.format(i.atZone(zone))
        }
    }

    fun bucketAxisLabel(t: String, bucket: String, zone: ZoneId = ZoneId.systemDefault()): String {
        val i = parse(t) ?: return isoDate(t)
        return when (bucket) {
            "day" -> monthDay.format(i.atZone(ZoneOffset.UTC))
            else -> hm.format(i.atZone(zone))
        }
    }
}

/** ISO 3166 alpha-2 → flag emoji (regional indicator pair). Unknown codes get a white flag. */
fun flagEmoji(code: String): String {
    val c = code.trim().uppercase(Locale.US)
    if (c.length != 2 || !c.all { it in 'A'..'Z' }) return "🏳"
    val first = Character.codePointAt(c, 0) - 'A'.code + 0x1F1E6
    val second = Character.codePointAt(c, 1) - 'A'.code + 0x1F1E6
    return String(Character.toChars(first)) + String(Character.toChars(second))
}
