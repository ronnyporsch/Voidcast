package de.ronnyporsch.voidcast.platform

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.*

actual val canAcceptUntrustedCertificates: Boolean = true

actual fun createHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(OkHttp) {
    engine { preconfigured = sharedOkHttpClient }
    block()
}

// Ktor's engine and the image loader derive from sharedOkHttpClient, so they share its connection pool
internal actual fun closeIdleConnections() = sharedOkHttpClient.connectionPool.evictAll()

// Clients derived from sharedOkHttpClient share its dispatcher
internal actual fun cancelRequests() = sharedOkHttpClient.dispatcher.cancelAll()

/** For everything that loads from the music server outside of Ktor (e.g. audio playback). */
val sharedOkHttpClient: OkHttpClient by lazy {
    val defaultHostnameVerifier = OkHttpClient().hostnameVerifier
    val sslContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(UntrustedHostTrustManager), null) }
    OkHttpClient.Builder()
        // Everything goes to the one music server; OkHttp's default of 5 per host lets slow requests (e.g. YouTube
        // stream info, which runs yt-dlp on the server) block unrelated ones like loading the next screen
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 32 })
        // Fails like a request without network does
        .addInterceptor { chain ->
            if (offlineMode) throw IOException("Offline mode is on")
            chain.proceed(chain.request())
        }
        .addInterceptor { chain -> chain.proceed(chain.request().withTokenInHeader()) }
        .sslSocketFactory(sslContext.socketFactory, UntrustedHostTrustManager)
        .hostnameVerifier { hostname, session ->
            // The accepted certificate needn't match the host name; any other one has to, like for every other host
            val leaf = runCatching { session.peerCertificates.firstOrNull() as? X509Certificate }.getOrNull()
            (leaf != null && acceptedCertificateOf(hostname)?.accepts(leaf) == true) || defaultHostnameVerifier.verify(hostname, session)
        }
        .build()
}

/** Moves the access token of a request to the music server from its address into a header, see [headerAuthorization]. */
private fun Request.withTokenInHeader(): Request {
    val authorization = headerAuthorization ?: return this
    val server = authorization.serverUrl.toHttpUrlOrNull() ?: return this
    val toServer = url.scheme == server.scheme && url.host == server.host && url.port == server.port
    // The websocket keeps the token in its address, like the Jellyfin clients that can't set headers have it
    if (!toServer || url.queryParameter(authorization.queryParameter) != authorization.token || header("Upgrade") != null) return this
    return newBuilder()
        .url(url.newBuilder().removeAllQueryParameters(authorization.queryParameter).build())
        .header("Authorization", authorization.header)
        .build()
}

private fun acceptedCertificateOf(host: String?): UntrustedCertificate? =
    untrustedCertificate?.takeIf { host != null && it.host.equals(host, ignoreCase = true) }

/** Whether [certificate] is the one the user accepted for the host; the first one the host shows becomes it. */
private fun UntrustedCertificate.accepts(certificate: X509Certificate): Boolean = synchronized(this) {
    val shown = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }
    val accepted = fingerprint ?: shown.also { fingerprint = it }
    accepted == shown
}

/**
 * Verifies certificates like the platform does, except that it also takes the one accepted for the host of
 * [untrustedCertificate]. JSSE (and Android's Conscrypt) pass the socket to an [X509ExtendedTrustManager], which
 * tells the host being connected to.
 */
private object UntrustedHostTrustManager : X509ExtendedTrustManager() {
    private val system: X509ExtendedTrustManager =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers.filterIsInstance<X509ExtendedTrustManager>().first()

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
        check(chain, (socket as? SSLSocket)?.handshakeSession?.peerHost) { system.checkServerTrusted(chain, authType, socket) }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
        check(chain, engine?.peerHost) { system.checkServerTrusted(chain, authType, engine) }

    /** Passes if [chain] starts with the certificate accepted for [host], or else if [verify] (the usual check) does. */
    private fun check(chain: Array<X509Certificate>, host: String?, verify: () -> Unit) {
        val accepted = acceptedCertificateOf(host) ?: return verify()
        if (chain.isNotEmpty() && accepted.accepts(chain[0])) return
        try {
            verify()
        } catch (e: CertificateException) {
            throw CertificateException(
                "${accepted.host} shows another certificate than the one trusted so far. If the server got a new one, turn " +
                    "\"Allow self-signed certificate\" off and on again; if not, someone may be intercepting the connection.",
                e,
            )
        }
    }

    // Without a socket the host is unknown, so verify
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
        system.checkServerTrusted(chain, authType)

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
        system.checkClientTrusted(chain, authType, socket)

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
        system.checkClientTrusted(chain, authType, engine)

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        system.checkClientTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
}
