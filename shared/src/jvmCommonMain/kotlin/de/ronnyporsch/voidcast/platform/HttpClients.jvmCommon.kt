package de.ronnyporsch.voidcast.platform

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.Socket
import java.security.KeyStore
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
        .sslSocketFactory(sslContext.socketFactory, UntrustedHostTrustManager)
        .hostnameVerifier { hostname, session ->
            hostname.isUntrusted() || defaultHostnameVerifier.verify(hostname, session)
        }
        .build()
}

private fun String?.isUntrusted() = this != null && equals(untrustedCertificateHost, ignoreCase = true)

/**
 * Verifies certificates like the platform does, except for [untrustedCertificateHost]. JSSE (and Android's
 * Conscrypt) pass the socket to an [X509ExtendedTrustManager], which tells the host being connected to.
 */
private object UntrustedHostTrustManager : X509ExtendedTrustManager() {
    private val system: X509ExtendedTrustManager =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers.filterIsInstance<X509ExtendedTrustManager>().first()

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) {
        val host = (socket as? SSLSocket)?.handshakeSession?.peerHost
        if (!host.isUntrusted()) system.checkServerTrusted(chain, authType, socket)
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) {
        if (!engine?.peerHost.isUntrusted()) system.checkServerTrusted(chain, authType, engine)
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
