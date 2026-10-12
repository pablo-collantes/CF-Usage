package io.github.pablocollantes.cfusage

import io.github.pablocollantes.cfusage.data.SiteRow
import io.github.pablocollantes.cfusage.data.WorkerRow
import io.github.pablocollantes.cfusage.ui.screens.KindFilter
import io.github.pablocollantes.cfusage.ui.screens.ServiceFilter
import io.github.pablocollantes.cfusage.ui.screens.SiteSort
import io.github.pablocollantes.cfusage.ui.screens.WorkerSort
import io.github.pablocollantes.cfusage.ui.screens.filterSites
import io.github.pablocollantes.cfusage.ui.screens.filterWorkers
import org.junit.Assert.assertEquals
import org.junit.Test

class ListLogicTest {
    private val workers = listOf(
        WorkerRow("b-worker", "worker", requests = 100.0, errors = 5.0, errorRate = 0.05, cpuMs = 10.0, hosts = listOf("b.example.com")),
        WorkerRow("a-worker", "worker", requests = 100.0, errors = 1.0, errorRate = 0.01, cpuMs = 50.0),
        WorkerRow("pages:site", "pages", requests = 300.0),
    )

    @Test
    fun workersSortWithStableTies() {
        val byReq = filterWorkers(workers, "", KindFilter.All, WorkerSort.Requests, descending = true).map { it.name }
        // Equal request counts fall back to the name, ascending, in both directions.
        assertEquals(listOf("pages:site", "a-worker", "b-worker"), byReq)
        val asc = filterWorkers(workers, "", KindFilter.All, WorkerSort.Requests, descending = false).map { it.name }
        assertEquals(listOf("a-worker", "b-worker", "pages:site"), asc)
    }

    @Test
    fun workersFilterByKindAndQuery() {
        assertEquals(listOf("pages:site"), filterWorkers(workers, "", KindFilter.Pages, WorkerSort.Name, false).map { it.name })
        // Matches the hostname too, not only the script name.
        assertEquals(listOf("b-worker"), filterWorkers(workers, "B.EXAMPLE", KindFilter.All, WorkerSort.Name, false).map { it.name })
        assertEquals(listOf("b-worker", "a-worker"), filterWorkers(workers, "", KindFilter.Workers, WorkerSort.ErrorRate, true).map { it.name })
    }

    @Test
    fun sitesFilterAndSort() {
        val sites = listOf(
            SiteRow("mail.example.com", requests = 10.0, blocked = 3.0, service = "worker:mailbox"),
            SiteRow("example.com", requests = 50.0, service = null),
            SiteRow("docs.example.com", requests = 20.0, service = "pages:docs"),
        )
        assertEquals(listOf("example.com", "docs.example.com", "mail.example.com"), filterSites(sites, "", ServiceFilter.All, SiteSort.Requests, true).map { it.host })
        assertEquals(listOf("mail.example.com"), filterSites(sites, "", ServiceFilter.Workers, SiteSort.Requests, true).map { it.host })
        assertEquals(listOf("example.com"), filterSites(sites, "", ServiceFilter.Other, SiteSort.Requests, true).map { it.host })
        assertEquals(listOf("mail.example.com"), filterSites(sites, "mailbox", ServiceFilter.All, SiteSort.Host, false).map { it.host })
        assertEquals("mail.example.com", filterSites(sites, "", ServiceFilter.All, SiteSort.Blocked, true).first().host)
    }
}
