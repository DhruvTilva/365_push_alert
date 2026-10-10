package com.dhruvtilva.pushalert.discord

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket

/** Exercises the real HttpURLConnection transport against throw-away local servers (never Discord). */
class UrlConnectionTransportTest {
    private var server: HttpServer? = null
    private val extra = mutableListOf<ServerSocket>()

    @After fun tearDown() { server?.stop(0); extra.forEach { it.close() } }

    private fun serve(path: String = "/hook", handler: (com.sun.net.httpserver.HttpExchange) -> Unit): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext(path) { ex -> try { handler(ex) } finally { ex.close() } }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}$path"
    }

    private fun reply(ex: com.sun.net.httpserver.HttpExchange, code: Int, body: String, vararg headers: Pair<String, String>) {
        val bytes = body.toByteArray()
        headers.forEach { ex.responseHeaders.add(it.first, it.second) }
        ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
    }

    @Test fun postsUtf8JsonAndReadsResponseAndHeaders() {
        var seenBody = ""; var seenType = ""
        val url = serve { ex ->
            seenBody = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            seenType = ex.requestHeaders.getFirst("Content-Type")
            reply(ex, 200, """{"id":"77"}""", "X-RateLimit-Remaining" to "4")
        }
        val r = UrlConnectionTransport().execute(HttpRequest("POST", url, """{"content":"héllo ✓"}"""))
        r as HttpResult.Response
        assertEquals(200, r.code)
        assertEquals("""{"id":"77"}""", r.body)
        assertEquals("4", r.headers["x-ratelimit-remaining"])
        assertEquals("""{"content":"héllo ✓"}""", seenBody)
        assertTrue(seenType.startsWith("application/json"))
    }

    @Test fun errorStatusBodiesAreReturned() {
        val url = serve { reply(it, 429, """{"retry_after":1.5}""") }
        val r = UrlConnectionTransport().execute(HttpRequest("POST", url, "{}")) as HttpResult.Response
        assertEquals(429, r.code)
        assertTrue(r.body.contains("retry_after"))
    }

    @Test fun redirectsAreNotFollowed() {
        val url = serve { reply(it, 302, "", "Location" to "http://127.0.0.1:1/elsewhere") }
        val r = UrlConnectionTransport().execute(HttpRequest("POST", url, "{}")) as HttpResult.Response
        assertEquals(302, r.code)
    }

    @Test fun slowServerAfterRequestSentIsUncertainTimeout() {
        val url = serve { Thread.sleep(1500); reply(it, 200, "{}") }
        val r = UrlConnectionTransport(connectTimeoutMs = 2000, readTimeoutMs = 300).execute(HttpRequest("POST", url, "{}"))
        assertEquals(HttpResult.Uncertain(UncertainKind.TIMEOUT), r)
    }

    @Test fun connectionRefusedMeansNothingWasSent() {
        val port = ServerSocket(0).use { it.localPort }
        val r = UrlConnectionTransport().execute(HttpRequest("POST", "http://127.0.0.1:$port/hook", "{}"))
        assertTrue(r.toString(), r is HttpResult.NotSent)
    }

    @Test fun unresolvableHostMeansNothingWasSent() {
        val r = UrlConnectionTransport(connectTimeoutMs = 3000).execute(HttpRequest("POST", "http://no-such-host.invalid/hook", "{}"))
        assertTrue(r.toString(), r is HttpResult.NotSent)
    }

    @Test fun connectionDroppedWithoutAnswerIsUncertain() {
        val ss = ServerSocket(0).also { extra += it }
        Thread {
            try { ss.accept().use { s -> s.getInputStream().read(ByteArray(4096)) } } catch (_: Exception) {}
        }.start()
        val r = UrlConnectionTransport(readTimeoutMs = 3000).execute(HttpRequest("POST", "http://127.0.0.1:${ss.localPort}/hook", "{}"))
        assertTrue(r.toString(), r is HttpResult.Uncertain)
    }

    @Test fun malformedUrlIsNotSent() {
        assertTrue(UrlConnectionTransport().execute(HttpRequest("POST", "not a url", "{}")) is HttpResult.NotSent)
    }
}
