package ru.blockscan.app

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import ru.blockscan.app.data.HttpCheckApi
import ru.blockscan.app.domain.*

/** Реальный HTTP-стек (OkHttp) против локального сервера, который отдаёт реальные снятые ответы. */
class HttpCheckApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: HttpCheckApi

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        api = HttpCheckApi(server.url("/").toString().trimEnd('/'))
    }

    @After fun tearDown() { runCatching { server.shutdown() } }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun <T> expectApi(block: suspend () -> T): ApiException = runBlocking {
        try { block(); fail("ожидалось ApiException"); throw IllegalStateException() } catch (e: ApiException) { e }
    }

    @Test fun checkSendsTargetAsQueryParameterAndReturnsRawBody() = runBlocking {
        val body = Res.text("check_1.1.1.1.json")
        server.enqueue(json(body))
        assertEquals(body, api.check("1.1.1.1"))
        val req = server.takeRequest()
        assertEquals("/api/v1/check?target=1.1.1.1", req.path)
        assertTrue(req.getHeader("User-Agent")!!.startsWith("BlockScan"))
        assertEquals("application/json", req.getHeader("Accept"))
    }

    @Test fun ipv6TargetReachesServerIntact() = runBlocking {
        server.enqueue(json("{}"))
        api.check("2606:4700::1111")
        val url = server.takeRequest().requestUrl!!
        assertEquals("2606:4700::1111", url.queryParameter("target"))
    }

    @Test fun statusAndHealthcheck() = runBlocking {
        server.enqueue(json(Res.text("status.json")))
        assertEquals("1.5.5", api.status().version)
        server.enqueue(MockResponse().setHeader("Content-Type", "text/plain; charset=utf-8").setBody("OK"))
        assertTrue(api.healthcheck())
    }

    @Test fun httpErrorsAreMappedToKinds() {
        server.enqueue(json("""{"code":404,"info":"Not Found"}""").setResponseCode(404))
        assertEquals(ApiException.Kind.NOT_FOUND, expectApi { api.check("999.1.1.1") }.kind)

        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "7"))
        val rl = expectApi { api.check("1.1.1.1") }
        assertEquals(ApiException.Kind.RATE_LIMITED, rl.kind)
        assertEquals(7_000L, rl.retryAfterMs)

        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(ApiException.Kind.SERVER, expectApi { api.check("1.1.1.1") }.kind)

        server.enqueue(MockResponse().setResponseCode(400))
        assertEquals(ApiException.Kind.CLIENT, expectApi { api.check("1.1.1.1") }.kind)
    }

    @Test fun connectionFailureIsNetworkError() {
        val dead = HttpCheckApi("http://127.0.0.1:1")
        assertEquals(ApiException.Kind.NETWORK, expectApi { dead.check("1.1.1.1") }.kind)
    }

    @Test fun sseRealStreamIsParsedIntoEvents() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setHeader("Cache-Control", "no-cache")
            .setBody(Res.text("probe_1.1.1.1.sse")))
        val ev = api.probe("abc").toList()
        assertEquals(39, ev.size)
        assertTrue(ev.first() is ProbeEvent.Started)
        assertTrue(ev.last() is ProbeEvent.Done)
        assertEquals(37, ev.count { it is ProbeEvent.Result })
        val req = server.takeRequest()
        assertEquals("/api/v1/probe/abc", req.path)
        assertEquals("text/event-stream", req.getHeader("Accept"))
    }

    @Test fun sseCutOffStreamHasNoDoneEvent() = runBlocking {
        val cut = Res.text("probe_1.1.1.1.sse").lines().take(12).joinToString("\n") // started + 2 результата, без done
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(cut))
        val ev = api.probe("abc").toList()
        assertTrue(ev.none { it is ProbeEvent.Done })
        assertTrue(ev.first() is ProbeEvent.Started)
        assertTrue(ev.count { it is ProbeEvent.Result } in 1..3)
    }

    @Test fun sseInStreamErrorEvent() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("event:error\ndata:{\"code\":429,\"info\":\"Too Many Requests\"}\n\n"))
        val ev = api.probe("abc").toList()
        assertEquals(429, (ev.single() as ProbeEvent.ServerError).code)
    }

    @Test fun sseHttpErrorIsThrown() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30"))
        val e = expectApi { api.probe("abc").toList() }
        assertEquals(ApiException.Kind.RATE_LIMITED, e.kind)
        assertEquals(30_000L, e.retryAfterMs)
    }
}
