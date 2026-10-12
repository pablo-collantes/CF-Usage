package io.github.pablocollantes.cfusage

import io.github.pablocollantes.cfusage.data.Alert
import io.github.pablocollantes.cfusage.data.ApiJson
import io.github.pablocollantes.cfusage.data.Health
import io.github.pablocollantes.cfusage.data.KillState
import io.github.pablocollantes.cfusage.data.MetricDetail
import io.github.pablocollantes.cfusage.data.ServerSettings
import io.github.pablocollantes.cfusage.data.SiteDetail
import io.github.pablocollantes.cfusage.data.SiteRow
import io.github.pablocollantes.cfusage.data.Summary
import io.github.pablocollantes.cfusage.data.WorkerDetail
import io.github.pablocollantes.cfusage.data.WorkerRow
import io.github.pablocollantes.cfusage.data.MetricCatalog
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ModelsTest {
    private fun fixture(name: String) = javaClass.classLoader!!.getResource("fixtures/$name")!!.readText()

    @Test
    fun summaryParses() {
        val s = ApiJson.decodeFromString(Summary.serializer(), fixture("summary.json"))
        assertEquals(23, s.cycle.cycleDay)
        assertEquals("warn", s.status)
        assertEquals(3, s.metrics.size)
        assertNull(s.pollError)
        val wr = s.metric("workers_requests")!!
        assertEquals(10_000_000.0, wr.included, 0.0)
        assertTrue(wr.sampled)
        assertEquals("mailbox", wr.top.first().name)
        assertEquals(1, s.alerts.single().suggested.size)
        assertEquals("2026-10-06T21:30:18Z", s.kills.single().verifiedAt)
    }

    @Test
    fun catalogHasShortCodesForEveryMetric() {
        val live = ApiJson.decodeFromString(Summary.serializer(), fixture("summary-live.json"))
        // Every metric the server sends is in the catalog, with a widget code and a product.
        for (m in live.metrics) {
            val e = MetricCatalog.entry(m.key)
            assertTrue("${m.key} missing from catalog", e != null)
            assertTrue(e!!.short.isNotBlank())
            assertEquals(m.group, e.group)
            assertTrue("${e.group} has no product entry", MetricCatalog.group(e.group) != null)
        }
        assertEquals(live.metrics.size, MetricCatalog.all.size)
        assertEquals("R", MetricCatalog.short("r2_class_b"))
        assertEquals("W", MetricCatalog.short("r2_class_a"))
        assertEquals("rq", MetricCatalog.short("do_requests"))
        assertEquals("DO", MetricCatalog.groupShort("Durable Objects"))
        // Codes are unique within a product, so a section never shows two "R"s.
        for (g in MetricCatalog.groups) {
            val codes = MetricCatalog.inGroup(g.name).map { it.short }
            assertEquals(codes.size, codes.toSet().size)
        }
    }

    @Test
    fun everyEndpointShapeParses() {
        ApiJson.decodeFromString(MetricDetail.serializer(), fixture("metric.json")).also { assertEquals(2, it.cumulative.size) }
        ApiJson.decodeFromString(ListSerializer(WorkerRow.serializer()), fixture("workers.json")).also { assertEquals("pages", it[1].kind) }
        ApiJson.decodeFromString(WorkerDetail.serializer(), fixture("worker.json")).also { assertNull(it.site) }
        ApiJson.decodeFromString(ListSerializer(SiteRow.serializer()), fixture("sites.json")).also { assertNull(it[1].service) }
        ApiJson.decodeFromString(SiteDetail.serializer(), fixture("site.json")).also { assertEquals(403, it.statuses[1].status) }
        ApiJson.decodeFromString(KillState.serializer(), fixture("kill.json")).also { assertEquals("restored", it.kills.single().state) }
        ApiJson.decodeFromString(ListSerializer(Alert.serializer()), fixture("alerts.json")).also { assertNull(it[1].metric) }
        ApiJson.decodeFromString(ServerSettings.serializer(), fixture("settings.json")).also { assertEquals(listOf("kv_lists"), it.muted) }
    }

    @Test
    fun nullsForDefaultedFieldsBecomeDefaults() {
        val json = """{"ok": true, "version": null, "lastPollAt": null, "pollError": null}"""
        val h = ApiJson.decodeFromString(Health.serializer(), json)
        assertEquals("", h.version)
    }

    /** Responses captured from the Go server, in docs/fixtures. */
    @Test
    fun serverFixturesParseIfPresent() {
        val dir = listOf(File("../../docs/fixtures"), File("../docs/fixtures")).firstOrNull { it.isDirectory } ?: return
        // Longest prefix first, so "workers-24h" isn't taken for a single worker.
        val serializers: List<Pair<String, KSerializer<*>>> = listOf(
            "summary" to Summary.serializer(),
            "metric" to MetricDetail.serializer(),
            "workers" to ListSerializer(WorkerRow.serializer()),
            "worker" to WorkerDetail.serializer(),
            "sites" to ListSerializer(SiteRow.serializer()),
            "site" to SiteDetail.serializer(),
            "kill" to KillState.serializer(),
            "alerts" to ListSerializer(Alert.serializer()),
            "settings" to ServerSettings.serializer(),
            "health" to Health.serializer(),
        )
        val files = dir.listFiles { f -> f.extension == "json" }.orEmpty()
        for (f in files) {
            val ser = serializers.firstOrNull { f.nameWithoutExtension.startsWith(it.first) }?.second
                ?: throw AssertionError("no model for fixture ${f.name}")
            ApiJson.decodeFromString(ser, f.readText())
        }
        File(dir, "summary.json").takeIf { it.exists() }?.let {
            val s = ApiJson.decodeFromString(Summary.serializer(), it.readText())
            assertEquals("the metric table has 23 rows", 23, s.metrics.size)
        }
        // The SSE sample: every complete data line must decode as the model its event names.
        // (The sample may be cut short for size; a line that doesn't close its object is skipped.)
        File(dir, "events-sample.txt").takeIf { it.exists() }?.let { sample ->
            var event = "message"
            for (line in sample.readLines()) {
                when {
                    line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                    line.startsWith("data:") && line.trimEnd().endsWith("}") -> {
                        val data = line.removePrefix("data:").trim()
                        when (event) {
                            "summary" -> ApiJson.decodeFromString(Summary.serializer(), data)
                            "alert" -> ApiJson.decodeFromString(Alert.serializer(), data)
                            "kill" -> ApiJson.decodeFromString(io.github.pablocollantes.cfusage.data.Kill.serializer(), data)
                        }
                    }
                }
            }
        }
        assertTrue(files.isNotEmpty())
    }
}
