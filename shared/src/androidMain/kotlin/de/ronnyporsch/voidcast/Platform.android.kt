package de.ronnyporsch.voidcast

import android.os.Build

class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
    override val deviceName: String =
        if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) Build.MODEL else "${Build.MANUFACTURER} ${Build.MODEL}"
    override val isMobile: Boolean = true
    override val audioContainers: List<String> = BROWSER_AUDIO_CONTAINERS
    override val maxVolumeBoostDb: Int = de.ronnyporsch.voidcast.platform.MAX_BOOST_DB
}

actual fun getPlatform(): Platform = AndroidPlatform()