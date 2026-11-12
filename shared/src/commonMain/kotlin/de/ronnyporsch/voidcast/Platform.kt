package de.ronnyporsch.voidcast

interface Platform {
    val name: String

    /** How other devices list this one, e.g. for remote control. */
    val deviceName: String get() = name

    val isMobile: Boolean

    /** Audio containers the platform's [de.ronnyporsch.voidcast.platform.AudioPlayer] can decode; servers transcode everything else. */
    val audioContainers: List<String>

    /**
     * By how many dB songs can be played louder than they are, see
     * [de.ronnyporsch.voidcast.platform.AudioPlayer.setBoost]; 0 where the platform can't amplify.
     */
    val maxVolumeBoostDb: Int get() = 0
}

/** What browsers (and Android's ExoPlayer) commonly decode. */
internal val BROWSER_AUDIO_CONTAINERS = listOf("mp3", "flac", "aac", "m4a", "mp4", "opus", "ogg", "oga", "webm", "wav")

expect fun getPlatform(): Platform
