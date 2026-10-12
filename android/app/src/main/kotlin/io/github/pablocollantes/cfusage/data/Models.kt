package io.github.pablocollantes.cfusage.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Mirrors docs/API.md. Field names and nullability follow the contract exactly. */

val ApiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    encodeDefaults = true
}

@Serializable
data class NamedValue(val name: String, val value: Double)

@Serializable
data class DatedValue(val date: String, val value: Double)

@Serializable
data class TimedValue(val t: String, val value: Double)

@Serializable
data class Overage(val units: Double = 0.0, val usd: Double = 0.0)

@Serializable
data class Metric(
    val key: String,
    val label: String,
    val group: String,
    val unit: String,
    val kind: String,
    val used: Double,
    val included: Double,
    val pct: Double,
    val projected: Double,
    val projectedPct: Double,
    val rate24h: Double = 0.0,
    val status: String = "ok",
    val overage: Overage = Overage(),
    val price: String = "",
    val sampled: Boolean = false,
    val top: List<NamedValue> = emptyList(),
    val daily: List<DatedValue> = emptyList(),
)

@Serializable
data class Cycle(
    val start: String,
    val end: String,
    val cycleDay: Int,
    val elapsed: Double,
    val daysLeft: Double,
)

@Serializable
data class KillTarget(
    val kind: String,
    val target: String,
    val label: String,
    val description: String,
)

@Serializable
data class Kill(
    val id: String,
    val kind: String,
    val target: String,
    val label: String,
    val description: String,
    val state: String,
    val appliedAt: String,
    val verifiedAt: String? = null,
    val restoredAt: String? = null,
    val reason: String? = null,
    val error: String? = null,
) {
    val asTarget get() = KillTarget(kind, target, label, description)
}

@Serializable
data class Alert(
    val id: String,
    val kind: String,
    val severity: String,
    val metric: String? = null,
    val title: String,
    val body: String,
    val createdAt: String,
    val ackedAt: String? = null,
    val suggested: List<KillTarget> = emptyList(),
)

@Serializable
data class Summary(
    val generatedAt: String,
    val lastPollAt: String? = null,
    val pollError: String? = null,
    val dataAsOf: String? = null,
    val cycle: Cycle,
    val status: String = "ok",
    val estimatedBillUsd: Double = 0.0,
    val metrics: List<Metric> = emptyList(),
    val alerts: List<Alert> = emptyList(),
    val kills: List<Kill> = emptyList(),
) {
    fun metric(key: String) = metrics.firstOrNull { it.key == key }
}

@Serializable
data class ResourceShare(val name: String, val value: Double, val share: Double = 0.0)

@Serializable
data class MetricDetail(
    val metric: Metric,
    val range: String,
    val bucket: String,
    val series: List<TimedValue> = emptyList(),
    val cumulative: List<TimedValue> = emptyList(),
    val byResource: List<ResourceShare> = emptyList(),
)

@Serializable
data class WorkerRow(
    val name: String,
    val kind: String,
    val requests: Double = 0.0,
    val errors: Double = 0.0,
    val errorRate: Double = 0.0,
    val cpuMs: Double = 0.0,
    val cpuMsPerRequest: Double = 0.0,
    val subrequests: Double = 0.0,
    val shareOfRequests: Double = 0.0,
    val hosts: List<String> = emptyList(),
    val spark: List<Double> = emptyList(),
    val killable: List<KillTarget> = emptyList(),
)

@Serializable
data class WorkerPoint(val t: String, val requests: Double = 0.0, val errors: Double = 0.0, val cpuMs: Double = 0.0)

@Serializable
data class ColoCount(val colo: String, val requests: Double)

@Serializable
data class WorkerDetail(
    val row: WorkerRow,
    val bucket: String,
    val series: List<WorkerPoint> = emptyList(),
    val colos: List<ColoCount> = emptyList(),
    val site: SiteDetail? = null,
)

@Serializable
data class SiteRow(
    val host: String,
    val zone: String = "",
    val requests: Double = 0.0,
    val bytes: Double = 0.0,
    val blocked: Double = 0.0,
    val cachedShare: Double = 0.0,
    val status4xx: Double = 0.0,
    val status5xx: Double = 0.0,
    val service: String? = null,
    val spark: List<Double> = emptyList(),
    val killable: List<KillTarget> = emptyList(),
)

@Serializable
data class SitePoint(val t: String, val requests: Double = 0.0, val blocked: Double = 0.0)

@Serializable
data class PathCount(val path: String, val requests: Double)

@Serializable
data class CountryCount(val country: String, val requests: Double)

@Serializable
data class StatusCount(val status: Int, val requests: Double)

@Serializable
data class ActionCount(val action: String, val requests: Double)

@Serializable
data class AgentCount(val agent: String, val requests: Double)

@Serializable
data class CacheCount(val status: String, val requests: Double)

@Serializable
data class SiteDetail(
    val row: SiteRow,
    val bucket: String,
    val series: List<SitePoint> = emptyList(),
    val paths: List<PathCount> = emptyList(),
    val countries: List<CountryCount> = emptyList(),
    val statuses: List<StatusCount> = emptyList(),
    val securityActions: List<ActionCount> = emptyList(),
    val userAgents: List<AgentCount> = emptyList(),
    val cache: List<CacheCount> = emptyList(),
)

@Serializable
data class KillState(val available: List<KillTarget> = emptyList(), val kills: List<Kill> = emptyList())

@Serializable
data class KillRequest(val kind: String, val target: String, val reason: String?)

@Serializable
data class Thresholds(val info: Double = 0.5, val warn: Double = 0.75, val critical: Double = 0.9)

@Serializable
data class ServerSettings(
    val cycleDay: Int = 1,
    val thresholds: Thresholds = Thresholds(),
    val projectionAlert: Boolean = true,
    val spikeFactor: Double = 5.0,
    val spikeMinShare: Double = 0.01,
    val muted: List<String> = emptyList(),
)

@Serializable
data class Health(
    val ok: Boolean,
    val version: String = "",
    val lastPollAt: String? = null,
    val pollError: String? = null,
)

@Serializable
data class ApiError(val error: String)

/** The fixed metric table from docs/API.md, for pickers that must work before any data arrives. */
object MetricCatalog {
    /**
     * [short] is the widget code ("rq", "R", "W", "S"); [name] is the label without the product,
     * for places where the product is already shown.
     */
    data class Entry(val key: String, val label: String, val group: String, val short: String, val name: String)

    /**
     * A product: [short] is the widget name, [tag] the two-letter form for narrow widgets, [noun]
     * what its resources are called in top lists.
     */
    data class Group(val name: String, val short: String, val tag: String, val noun: String)

    val groups = listOf(
        Group("Workers", "Workers", "Wk", "Workers"),
        Group("Durable Objects", "DO", "DO", "Namespaces"),
        Group("D1", "D1", "D1", "Databases"),
        Group("R2", "R2", "R2", "Buckets"),
        Group("KV", "KV", "KV", "Namespaces"),
        Group("Queues", "Queues", "Q", "Queues"),
    )

    val all = listOf(
        Entry("workers_requests", "Workers requests", "Workers", "rq", "Requests"),
        Entry("workers_cpu_ms", "Workers CPU time", "Workers", "CPU", "CPU time"),
        Entry("do_requests", "DO requests", "Durable Objects", "rq", "Requests"),
        Entry("do_duration_gbs", "DO duration", "Durable Objects", "dur", "Duration"),
        Entry("do_sql_rows_read", "DO SQLite rows read", "Durable Objects", "R", "SQLite rows read"),
        Entry("do_sql_rows_written", "DO SQLite rows written", "Durable Objects", "W", "SQLite rows written"),
        Entry("do_sql_storage_gb", "DO SQLite storage", "Durable Objects", "S", "SQLite storage"),
        Entry("do_kv_read_units", "DO KV read units", "Durable Objects", "KV R", "KV read units"),
        Entry("do_kv_write_units", "DO KV write units", "Durable Objects", "KV W", "KV write units"),
        Entry("do_kv_deletes", "DO KV deletes", "Durable Objects", "KV D", "KV deletes"),
        Entry("do_kv_storage_gb", "DO KV storage", "Durable Objects", "KV S", "KV storage"),
        Entry("d1_rows_read", "D1 rows read", "D1", "R", "Rows read"),
        Entry("d1_rows_written", "D1 rows written", "D1", "W", "Rows written"),
        Entry("d1_storage_gb", "D1 storage", "D1", "S", "Storage"),
        // Class B operations are the reads (GET, HEAD); Class A the writes and lists.
        Entry("r2_class_b", "R2 Class B ops", "R2", "R", "Class B ops (reads)"),
        Entry("r2_class_a", "R2 Class A ops", "R2", "W", "Class A ops (writes, lists)"),
        Entry("r2_storage_gb", "R2 storage", "R2", "S", "Storage"),
        Entry("kv_reads", "KV reads", "KV", "R", "Reads"),
        Entry("kv_writes", "KV writes", "KV", "W", "Writes"),
        Entry("kv_deletes", "KV deletes", "KV", "D", "Deletes"),
        Entry("kv_lists", "KV lists", "KV", "L", "Lists"),
        Entry("kv_storage_gb", "KV storage", "KV", "S", "Storage"),
        Entry("queues_ops", "Queues operations", "Queues", "ops", "Operations"),
    )

    private val byKey = all.associateBy { it.key }

    fun entry(key: String) = byKey[key]
    fun label(key: String) = byKey[key]?.label ?: key
    fun short(key: String) = byKey[key]?.short ?: key.take(3)
    fun name(key: String) = byKey[key]?.name ?: label(key)
    fun group(name: String) = groups.firstOrNull { it.name == name }
    fun groupShort(name: String) = group(name)?.short ?: name
    fun groupTag(name: String) = group(name)?.tag ?: name.take(2)
    fun inGroup(group: String) = all.filter { it.group == group }
}

/** Status order for "worst of" comparisons. */
fun statusRank(status: String) = when (status) {
    "over" -> 4
    "critical" -> 3
    "warn" -> 2
    "info" -> 1
    else -> 0
}

/** Storage metrics are gauges: `used` is the current size, `projected` the cycle's billed average. */
val Metric.isStorage get() = kind == "storage"

fun worstStatus(metrics: List<Metric>) = metrics.maxByOrNull { statusRank(it.status) }?.status ?: "ok"

/**
 * The summary's metrics by product, as the Overview and the card widgets show them: products and
 * metrics in catalog order, products the catalog doesn't know last.
 */
fun Summary.byProduct(): List<Pair<String, List<Metric>>> {
    val known = MetricCatalog.groups.map { it.name }
    val order = known + metrics.map { it.group }.distinct().filterNot { it in known }
    val rank = MetricCatalog.all.withIndex().associate { (i, e) -> e.key to i }
    return order.mapNotNull { g ->
        metrics.filter { it.group == g }.sortedBy { rank[it.key] ?: Int.MAX_VALUE }.takeIf { it.isNotEmpty() }?.let { g to it }
    }
}
