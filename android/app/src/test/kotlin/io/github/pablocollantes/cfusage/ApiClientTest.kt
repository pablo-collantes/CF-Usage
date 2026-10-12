package io.github.pablocollantes.cfusage

import io.github.pablocollantes.cfusage.data.ApiClient
import io.github.pablocollantes.cfusage.data.ApiException
import io.github.pablocollantes.cfusage.data.KillTarget
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ApiClient

    private fun fixture(name: String) = javaClass.classLoader!!.getResource("fixtures/$name")!!.readText()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val base = server.url("/").toString()
        api = ApiClient(baseUrl = { base })
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun pathsAndQueriesAreEncoded() = runBlocking {
        server.enqueue(MockResponse().setBody(fixture("worker.json")))
        api.worker("pages:my/site", "7d")
        val req = server.takeRequest()
        // One path segment per name, even with ':' and '/' in it.
        assertEquals("/api/v1/workers/pages:my%2Fsite?range=7d", req.path)
    }

    @Test
    fun killPostsKindTargetReason() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(202).setBody(
            """{"id":"waf_block:a.example.com","kind":"waf_block","target":"a.example.com","label":"Block","description":"d","state":"applying","appliedAt":"2026-10-06T21:00:00Z"}""",
        ))
        val k = api.applyKill(KillTarget("waf_block", "a.example.com", "Block", "d"), "spike")
        assertEquals("applying", k.state)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v1/kill", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"kind\":\"waf_block\"") && body.contains("\"target\":\"a.example.com\"") && body.contains("\"reason\":\"spike\""))
    }

    @Test
    fun restoreIsDelete() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(202).setBody(
            """{"id":"cron_off:x","kind":"cron_off","target":"x","label":"l","description":"d","state":"restoring","appliedAt":"2026-10-06T21:00:00Z"}""",
        ))
        api.restoreKill("cron_off:x")
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/api/v1/kill/cron_off:x", req.path)
    }

    @Test
    fun errorBodyBecomesMessage() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"login not allowed"}"""))
        try {
            api.summary()
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals("login not allowed", e.message)
            assertEquals(403, e.code)
        }
    }

    @Test
    fun summaryParsesFromServer() = runBlocking {
        server.enqueue(MockResponse().setBody(fixture("summary.json")))
        assertEquals(23, api.summary().cycle.cycleDay)
        assertEquals("/api/v1/summary", server.takeRequest().path)
    }
}
