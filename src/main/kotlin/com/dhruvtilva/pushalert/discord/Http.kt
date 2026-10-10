package com.dhruvtilva.pushalert.discord

import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

data class HttpRequest(val method: String, val url: String, val body: String? = null)

enum class NotSentKind { NO_NETWORK, DNS, CONNECT_TIMEOUT, TLS, OTHER }
enum class UncertainKind { TIMEOUT, CONNECTION_LOST }

/**
 * Result of one HTTP exchange. The split between [NotSent] and [Uncertain] is the heart of the
 * delivery semantics: a failure *before* the request body was written proves Discord never saw
 * it; a failure afterwards means the message may or may not have been created.
 */
sealed interface HttpResult {
    data class Response(val code: Int, val body: String, val headers: Map<String, String>) : HttpResult
    data class NotSent(val kind: NotSentKind) : HttpResult
    data class Uncertain(val kind: UncertainKind) : HttpResult
}

/** Blocking transport; callers run it on an IO dispatcher. */
interface HttpTransport {
    fun execute(request: HttpRequest): HttpResult
}

class UrlConnectionTransport(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 20_000,
) : HttpTransport {

    override fun execute(request: HttpRequest): HttpResult {
        val conn: HttpURLConnection = try {
            URL(request.url).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return HttpResult.NotSent(NotSentKind.OTHER)
        }
        try {
            conn.requestMethod = request.method
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.instanceFollowRedirects = false // never forward the webhook token to another host
            conn.useCaches = false
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            val payload = request.body?.toByteArray(Charsets.UTF_8)
            if (payload != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setFixedLengthStreamingMode(payload.size)
            }

            try {
                conn.connect()
            } catch (e: UnknownHostException) {
                return HttpResult.NotSent(NotSentKind.DNS)
            } catch (e: SocketTimeoutException) {
                return HttpResult.NotSent(NotSentKind.CONNECT_TIMEOUT)
            } catch (e: SSLException) {
                return HttpResult.NotSent(NotSentKind.TLS)
            } catch (e: ConnectException) {
                return HttpResult.NotSent(NotSentKind.NO_NETWORK)
            } catch (e: NoRouteToHostException) {
                return HttpResult.NotSent(NotSentKind.NO_NETWORK)
            } catch (e: IOException) {
                return HttpResult.NotSent(NotSentKind.OTHER)
            }

            // From here on the request may have reached the server.
            return try {
                if (payload != null) conn.outputStream.use { it.write(payload) }
                val code = conn.responseCode
                val stream = if (code >= 400) conn.errorStream else conn.inputStream
                val body = stream?.use { readLimited(it) } ?: ""
                val headers = HashMap<String, String>()
                for ((k, v) in conn.headerFields) {
                    if (k != null && !v.isNullOrEmpty()) headers[k.lowercase()] = v.first()
                }
                HttpResult.Response(code, body, headers)
            } catch (e: SocketTimeoutException) {
                HttpResult.Uncertain(UncertainKind.TIMEOUT)
            } catch (e: IOException) {
                HttpResult.Uncertain(UncertainKind.CONNECTION_LOST)
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun readLimited(stream: java.io.InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (out.size() < MAX_BODY) {
            val n = stream.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toString("UTF-8")
    }

    private companion object {
        const val USER_AGENT = "365PushAlert/1.0 (personal Android app)"
        const val MAX_BODY = 64 * 1024
    }
}
