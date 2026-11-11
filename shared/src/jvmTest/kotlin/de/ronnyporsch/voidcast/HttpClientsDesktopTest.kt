package de.ronnyporsch.voidcast

import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import de.ronnyporsch.voidcast.platform.HeaderAuthorization
import de.ronnyporsch.voidcast.platform.UntrustedCertificate
import de.ronnyporsch.voidcast.platform.headerAuthorization
import de.ronnyporsch.voidcast.platform.sharedOkHttpClient
import de.ronnyporsch.voidcast.platform.untrustedCertificate
import okhttp3.Request
import java.io.File
import java.net.InetSocketAddress
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HttpClientsDesktopTest {
    @AfterTest
    fun reset() {
        headerAuthorization = null
        untrustedCertificate = null
    }

    /** An HTTPS server on localhost with a new self-signed certificate. */
    private fun selfSignedServer(): HttpsServer {
        val keyStoreFile = File.createTempFile("voidcast-test", ".p12").apply { delete(); deleteOnExit() }
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val exit = ProcessBuilder(
            keytool, "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
            "-dname", "CN=localhost", "-storetype", "PKCS12", "-keystore", keyStoreFile.path, "-storepass", "changeit",
        ).redirectErrorStream(true).start().apply { inputStream.readAllBytes() }.waitFor()
        assertEquals(0, exit, "keytool")
        val keyStore = KeyStore.getInstance("PKCS12").apply { keyStoreFile.inputStream().use { load(it, "changeit".toCharArray()) } }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, "changeit".toCharArray()) }
        val context = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
        return HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            httpsConfigurator = HttpsConfigurator(context)
            createContext("/") { exchange ->
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
            }
            start()
        }
    }

    private fun get(server: HttpsServer): Int =
        sharedOkHttpClient.newCall(Request.Builder().url("https://localhost:${server.address.port}/").build()).execute().use { it.code }

    @Test
    fun a_self_signed_certificate_is_only_trusted_once_accepted_and_only_that_one() {
        val server = selfSignedServer()
        val impostor = selfSignedServer()
        try {
            assertFailsWith<SSLException> { get(server) }

            val accepted = UntrustedCertificate("localhost", null)
            untrustedCertificate = accepted
            assertEquals(204, get(server))
            val fingerprint = accepted.fingerprint
            assertEquals(64, fingerprint?.length)
            // Again, and as after a restart, with the remembered fingerprint
            assertEquals(204, get(server))
            untrustedCertificate = UntrustedCertificate("localhost", fingerprint)
            assertEquals(204, get(server))

            // Someone else answering for the host with a certificate of their own
            assertFailsWith<SSLException> { get(impostor) }
            assertEquals(fingerprint, untrustedCertificate?.fingerprint)

            // Another host isn't trusted with it
            untrustedCertificate = UntrustedCertificate("example.com", null)
            assertFailsWith<SSLException> { get(server) }
        } finally {
            server.stop(0)
            impostor.stop(0)
        }
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
