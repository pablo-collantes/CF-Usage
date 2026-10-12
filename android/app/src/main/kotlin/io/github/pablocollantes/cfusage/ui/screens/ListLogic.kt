package io.github.pablocollantes.cfusage.ui.screens

import io.github.pablocollantes.cfusage.data.SiteRow
import io.github.pablocollantes.cfusage.data.WorkerRow

/** Sorting and filtering for the Workers and Sites lists. Pure, so it is unit tested. */
enum class WorkerSort(val label: String) {
    Requests("Requests"), Errors("Errors"), ErrorRate("Error rate"), Cpu("CPU time"), CpuPerRequest("CPU / request"), Name("Name")
}

enum class KindFilter(val label: String) { All("All"), Workers("Workers"), Pages("Pages") }

fun filterWorkers(rows: List<WorkerRow>, query: String, kind: KindFilter, sort: WorkerSort, descending: Boolean): List<WorkerRow> {
    val q = query.trim().lowercase()
    val filtered = rows.filter { r ->
        (kind == KindFilter.All || (kind == KindFilter.Workers && r.kind == "worker") || (kind == KindFilter.Pages && r.kind == "pages")) &&
            (q.isEmpty() || q in r.name.lowercase() || r.hosts.any { q in it.lowercase() })
    }
    val cmp: Comparator<WorkerRow> = when (sort) {
        WorkerSort.Requests -> compareBy { it.requests }
        WorkerSort.Errors -> compareBy { it.errors }
        WorkerSort.ErrorRate -> compareBy { it.errorRate }
        WorkerSort.Cpu -> compareBy { it.cpuMs }
        WorkerSort.CpuPerRequest -> compareBy { it.cpuMsPerRequest }
        WorkerSort.Name -> compareBy { it.name.lowercase() }
    }
    // Ties fall back to the name, ascending either way, so the order stays stable while numbers tick.
    return filtered.sortedWith((if (descending) cmp.reversed() else cmp).thenBy { it.name })
}

enum class SiteSort(val label: String) {
    Requests("Requests"), Bytes("Bandwidth"), Blocked("Blocked"), Cached("Cached share"), Status4xx("4xx share"), Status5xx("5xx share"), Host("Host")
}

enum class ServiceFilter(val label: String) { All("All"), Workers("Workers"), Pages("Pages"), Other("Other") }

fun filterSites(rows: List<SiteRow>, query: String, service: ServiceFilter, sort: SiteSort, descending: Boolean): List<SiteRow> {
    val q = query.trim().lowercase()
    val filtered = rows.filter { r ->
        val svc = r.service.orEmpty()
        val okService = when (service) {
            ServiceFilter.All -> true
            ServiceFilter.Workers -> svc.startsWith("worker:")
            ServiceFilter.Pages -> svc.startsWith("pages:")
            ServiceFilter.Other -> svc.isEmpty()
        }
        okService && (q.isEmpty() || q in r.host.lowercase() || q in svc.lowercase())
    }
    val cmp: Comparator<SiteRow> = when (sort) {
        SiteSort.Requests -> compareBy { it.requests }
        SiteSort.Bytes -> compareBy { it.bytes }
        SiteSort.Blocked -> compareBy { it.blocked }
        SiteSort.Cached -> compareBy { it.cachedShare }
        SiteSort.Status4xx -> compareBy { it.status4xx }
        SiteSort.Status5xx -> compareBy { it.status5xx }
        SiteSort.Host -> compareBy { it.host }
    }
    return filtered.sortedWith((if (descending) cmp.reversed() else cmp).thenBy { it.host })
}
