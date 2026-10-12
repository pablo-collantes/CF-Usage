package io.github.pablocollantes.cfusage.util

/** How much to trust what's on screen. Pure, so the thresholds are unit tested. */
enum class FreshnessLevel { LIVE, RECENT, STALE, DISCONNECTED, NONE }

data class Freshness(val level: FreshnessLevel, val ageMs: Long, val label: String) {
    /** Red in the UI: the numbers may be wrong right now. */
    val alarming get() = level == FreshnessLevel.STALE || level == FreshnessLevel.DISCONNECTED || level == FreshnessLevel.NONE
}

object FreshnessRules {
    const val LIVE_MS = 3 * 60_000L
    const val STALE_MS = 10 * 60_000L

    /**
     * The thresholds widen with the update rate ([everyMs] between pushed summaries): with
     * 15-minute updates, numbers 12 minutes old are on schedule, not stale.
     */
    fun liveMs(everyMs: Long) = maxOf(LIVE_MS, everyMs + 2 * 60_000L)
    fun staleMs(everyMs: Long) = maxOf(STALE_MS, everyMs + 5 * 60_000L)

    /**
     * [lastPollAtMs] is the server's last successful poll. Age is measured from it rather than from
     * when the phone received the summary: a connected stream relaying a stuck poller is still stale.
     */
    fun of(nowMs: Long, lastPollAtMs: Long?, connected: Boolean, pollError: String?, everyMs: Long = 60_000L): Freshness {
        if (lastPollAtMs == null) return Freshness(FreshnessLevel.NONE, Long.MAX_VALUE, if (connected) "Waiting for data" else "No data")
        val age = (nowMs - lastPollAtMs).coerceAtLeast(0)
        val ago = Fmt.ago(age)
        return when {
            !connected -> Freshness(FreshnessLevel.DISCONNECTED, age, "Offline · $ago")
            age > staleMs(everyMs) -> Freshness(FreshnessLevel.STALE, age, if (pollError != null) "Poll failing · $ago" else "Stale · $ago")
            age <= liveMs(everyMs) && pollError == null -> Freshness(FreshnessLevel.LIVE, age, "Live")
            else -> Freshness(FreshnessLevel.RECENT, age, "Updated $ago")
        }
    }

    /** Widgets have no stream of their own: they only go red once the data itself is old. */
    fun widgetStale(nowMs: Long, lastPollAtMs: Long?, everyMs: Long = 60_000L): Boolean =
        lastPollAtMs == null || nowMs - lastPollAtMs > staleMs(everyMs)
}

/** Reconnect delays: 1 s doubling to 60 s, reset once a connection opens. */
class Backoff(private val initialMs: Long = 1_000, private val maxMs: Long = 60_000) {
    private var next = initialMs

    fun nextDelayMs(): Long {
        val d = next
        next = (next * 2).coerceAtMost(maxMs)
        return d
    }

    fun reset() {
        next = initialMs
    }
}
