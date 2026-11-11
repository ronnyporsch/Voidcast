package de.ronnyporsch.voidcast

import com.sun.net.httpserver.HttpServer
import de.ronnyporsch.voidcast.platform.HeaderAuthorization
import de.ronnyporsch.voidcast.platform.headerAuthorization
import de.ronnyporsch.voidcast.platform.sharedOkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class HttpClientsDesktopTest {
    @AfterTest
    fun reset() {
        headerAuthorization = null
    }

    /** What the server got asked: the request's address and its Authorization header. */
    private fun request(path: String, authorization: (serverUrl: String) -> HeaderAuthorization?): Pair<String, String?> {
        var received: Pair<String, String?>? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            received = exchange.requestURI.toString() to exchange.requestHeaders.getFirst("Authorization")
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        server.start()
        try {
            val serverUrl = "http://127.0.0.1:${server.address.port}"
            headerAuthorization = authorization(serverUrl)
            sharedOkHttpClient.newCall(Request.Builder().url(serverUrl + path).build()).execute().close()
        } finally {
            server.stop(0)
        }
        return received!!
    }

    @Test
    fun the_token_of_stream_urls_is_sent_as_header() {
        val (address, header) = request("/Audio/1/universal?Container=opus&ApiKey=secret") {
            HeaderAuthorization(it, "ApiKey", "secret", "MediaBrowser Token=\"secret\"")
        }
        assertEquals("/Audio/1/universal?Container=opus", address)
        assertEquals("MediaBrowser Token=\"secret\"", header)
    }

    @Test
    fun other_requests_stay_as_they_are() {
        // Not the token of the session
        assertEquals("/a?ApiKey=other" to null, request("/a?ApiKey=other") { HeaderAuthorization(it, "ApiKey", "secret", "x") })
        // Another server
        assertEquals("/a?ApiKey=secret" to null, request("/a?ApiKey=secret") { HeaderAuthorization("https://example.com", "ApiKey", "secret", "x") })
        // Not connected to a Jellyfin server
        assertEquals("/a?ApiKey=secret" to null, request("/a?ApiKey=secret") { null })
    }
}
