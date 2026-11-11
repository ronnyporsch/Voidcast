package de.ronnyporsch.voidcast.platform

import io.ktor.client.*
import kotlin.concurrent.Volatile

/**
 * The one TLS certificate [host] is trusted with even though nobody vouches for it (e.g. it is self-signed) or it
 * doesn't match the host name. [fingerprint] is its SHA-256 (lowercase hex); while it is null, the certificate the
 * host shows next is trusted and becomes it (trust on first use). Any other certificate the host shows later is only
 * accepted if it is valid like for every other host, so nobody in between can pass off their own.
 */
class UntrustedCertificate(val host: String, fingerprint: String?) {
    @Volatile
    var fingerprint: String? = fingerprint
        internal set
}

/**
 * The certificate the user accepted for the music server, or null to verify all certificates the usual way. Only the
 * music server gets this, everything else (e.g. YouTube thumbnails) is still verified. Browsers verify certificates
 * themselves, so this has no effect on the web.
 */
@Volatile
var untrustedCertificate: UntrustedCertificate? = null
    set(value) {
        if (field === value) return
        field = value
        closeIdleConnections()
    }

/**
 * Makes every request fail as if the device had no network, see [de.ronnyporsch.voidcast.domain.model.AppSettings.offlineMode].
 * Turning it on also cancels the running requests, e.g. downloads and the stream being played. No effect on the web.
 */
@Volatile
var offlineMode: Boolean = false
    set(value) {
        if (field == value) return
        field = value
        if (value) cancelRequests()
    }

/**
 * How requests to the music server at [serverUrl] sign in: with the [header] (an `Authorization` header) instead of
 * the [token] that stream URLs carry as [queryParameter].
 */
class HeaderAuthorization(val serverUrl: String, val queryParameter: String, val token: String, val header: String)

/**
 * Stream URLs carry the access token, as players can't set headers, and URLs end up in the logs of servers and
 * proxies. Where the app loads them itself (playback on Android, downloads, not VLC on the desktop or browsers), it
 * takes the token out of the address and sends this header instead. Null while not connected to a Jellyfin server.
 */
@Volatile
var headerAuthorization: HeaderAuthorization? = null

/** Cancels the requests in flight, see [offlineMode]. */
internal expect fun cancelRequests()

/** Drops pooled connections, which were established under the previous [untrustedCertificate]. */
internal expect fun closeIdleConnections()

/** Whether the platform honors [untrustedCertificate]. */
expect val canAcceptUntrustedCertificates: Boolean

/** Creates an HTTP client that honors [untrustedCertificate]. */
expect fun createHttpClient(block: HttpClientConfig<*>.() -> Unit = {}): HttpClient
