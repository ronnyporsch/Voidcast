package de.ronnyporsch.voidcast

class JVMPlatform : Platform {
    override val name: String = "Java ${System.getProperty("java.version")}"
    override val deviceName: String by lazy {
        // Without a network lookup, which InetAddress.getLocalHost() may do
        val host = System.getenv("HOSTNAME") ?: System.getenv("COMPUTERNAME")
            ?: runCatching { java.io.File("/etc/hostname").readText().trim() }.getOrNull()
        host?.takeIf { it.isNotBlank() } ?: System.getProperty("os.name") ?: "Desktop"
    }
    override val isMobile: Boolean = false
    // libVLC decodes them all
    override val audioContainers: List<String> = BROWSER_AUDIO_CONTAINERS + listOf("aiff", "alac", "wma", "ape", "wv", "mka")
}

actual fun getPlatform(): Platform = JVMPlatform()