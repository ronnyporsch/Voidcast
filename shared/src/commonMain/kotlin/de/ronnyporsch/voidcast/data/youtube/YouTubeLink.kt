package de.ronnyporsch.voidcast.data.youtube

/** A link to YouTube or YouTube Music, e.g. pasted into the search. */
sealed class YouTubeLink {
    data class Video(val videoId: String) : YouTubeLink()
    data class Channel(val channelId: String) : YouTubeLink()

    companion object {
        private val linkRegex = Regex("""^(?:https?://)?(?:(?:www|m|music)\.)?(youtube\.com|youtube-nocookie\.com|youtu\.be)(/\S*)?$""", RegexOption.IGNORE_CASE)
        private val videoIdRegex = Regex("""^[\w-]{11}$""")
        private val channelIdRegex = Regex("""^UC[\w-]{22}$""")
        /** Paths of single videos besides /watch?v=<id>, e.g. /shorts/<id>. */
        private val videoPaths = setOf("shorts", "embed", "live", "v")

        /**
         * Recognizes links to videos (watch pages of youtube.com and music.youtube.com, youtu.be, shorts, embeds) and
         * to channels by id; null for anything else.
         */
        fun parse(text: String): YouTubeLink? {
            val match = linkRegex.matchEntire(text.trim()) ?: return null
            val host = match.groupValues[1].lowercase()
            val url = match.groupValues[2].substringBefore('#')
            val segments = url.substringBefore('?').split('/').filter { it.isNotEmpty() }
            val parameters = url.substringAfter('?', "").split('&')
                .associate { it.substringBefore('=') to it.substringAfter('=', "") }
            val videoId = when {
                host == "youtu.be" -> segments.firstOrNull()
                segments.firstOrNull() == "watch" -> parameters["v"]
                segments.size >= 2 && segments[0] in videoPaths -> segments[1]
                else -> null
            }
            return when {
                videoId != null -> videoId.takeIf(videoIdRegex::matches)?.let(::Video)
                segments.size >= 2 && segments[0] == "channel" && channelIdRegex.matches(segments[1]) -> Channel(segments[1])
                else -> null
            }
        }
    }
}
