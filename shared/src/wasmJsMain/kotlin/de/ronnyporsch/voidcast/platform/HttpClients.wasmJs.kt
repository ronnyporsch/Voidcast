package de.ronnyporsch.voidcast.platform

import io.ktor.client.*

actual val canAcceptUntrustedCertificates: Boolean = false

internal actual fun closeIdleConnections() {}

internal actual fun cancelRequests() {}

actual fun createHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(block)
