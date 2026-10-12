package io.github.pablocollantes.cfusage

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/**
 * The server gzips the event stream and flushes the compressor after every event. OkHttp must
 * hand each event over as soon as its bytes arrive, not hold it until more compressed data
 * follows, or a compressed stream would delay alerts.
 */
class GzipStreamTest {
    private val server = MockWebServer()

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun eachFlushedEventArrivesWithoutWaitingForTheNext() {
        val bytes = ByteArrayOutputStream()
        val gz = GZIPOutputStream(bytes, true) // syncFlush, like Go's gzip.Writer.Flush
        gz.write("event: summary\ndata: {\"n\":1}\n\n".toByteArray())
        gz.flush()
        val first = bytes.size().toLong()
        gz.write("event: alert\ndata: {\"n\":2}\n\n".toByteArray())
        gz.finish()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setHeader("Content-Encoding", "gzip")
                .setBody(Buffer().write(bytes.toByteArray()))
                // The first event's bytes, then a 2 s pause before the rest.
                .throttleBody(first, 2, TimeUnit.SECONDS),
        )
        server.start()

        val got = LinkedBlockingQueue<Triple<String?, String, Long>>()
        val t0 = System.nanoTime()
        val source = EventSources.createFactory(OkHttpClient()).newEventSource(
            Request.Builder().url(server.url("/api/v1/events?every=60")).header("Accept", "text/event-stream").build(),
            object : EventSourceListener() {
                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    got.add(Triple(type, data, (System.nanoTime() - t0) / 1_000_000))
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {}
            },
        )
        try {
            val e1 = got.poll(5, TimeUnit.SECONDS)
            assertNotNull("no first event", e1)
            assertEquals("summary" to "{\"n\":1}", e1!!.first to e1.second)
            assertTrue("the first event waited for the second (${e1.third} ms)", e1.third < 1_500)
            val e2 = got.poll(5, TimeUnit.SECONDS)
            assertEquals("alert" to "{\"n\":2}", e2!!.first to e2.second)
            assertTrue("the second event came before the pause ended (${e2.third} ms)", e2.third >= 1_500)
            // OkHttp asked for gzip itself, which is what makes it decompress transparently.
            assertEquals("gzip", server.takeRequest().getHeader("Accept-Encoding"))
        } finally {
            source.cancel()
        }
    }
}
