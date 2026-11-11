package de.ronnyporsch.voidcast.util

import io.ktor.http.parseUrl

private val localSuffixes = listOf(".localhost", ".local", ".lan", ".home", ".home.arpa", ".internal", ".fritz.box")
private val ipv4Regex = Regex("""^(\d{1,3})\.(\d{1,3})\.\d{1,3}\.\d{1,3}$""")

/**
 * Whether [serverUrl] sends the login and everything else unencrypted over networks others can listen on: plain HTTP
 * to anything but this device and the local network.
 */
fun isUnencryptedRemoteUrl(serverUrl: String): Boolean {
    val url = serverUrl.trim()
    if (!url.startsWith("http://", ignoreCase = true)) return false
    val host = parseUrl(url)?.host?.lowercase()?.removePrefix("[")?.removeSuffix("]") ?: return false
    return !isLocalHost(host)
}

private fun isLocalHost(host: String): Boolean {
    ipv4Regex.matchEntire(host)?.let { match ->
        val a = match.groupValues[1].toInt()
        val b = match.groupValues[2].toInt()
        return a == 127 || a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
            // Link-local, and the range of carrier-grade NAT, which VPNs like Tailscale use
            (a == 169 && b == 254) || (a == 100 && b in 64..127)
    }
    // IPv6: loopback, unique local and link-local addresses
    if (':' in host) return host == "::1" || host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80")
    // Names without a dot only resolve in the local network
    return host == "localhost" || '.' !in host || localSuffixes.any(host::endsWith)
}
