package de.ronnyporsch.voidcast.platform

import de.ronnyporsch.voidcast.util.md5

/** Reads a text file the app stored with [writeAppFile], or null if there is none. */
expect suspend fun readAppFile(name: String): String?

/** Stores [content] in the app's private storage under [name]; null deletes it. */
expect suspend fun writeAppFile(name: String, content: String?)

/** Song files kept on the device for offline playback. */
interface SongFileStore {
    /** Suspends until the store knows which files it has; until then [uriOf] and [coverUriOf] may return null. */
    suspend fun awaitLoaded() {}

    /** URI the audio player can play the downloaded song from, or null if it isn't downloaded. */
    fun uriOf(songId: String): String?

    /** Downloads [url] as the file of [songId]. Throws if that fails. */
    suspend fun download(songId: String, url: String)

    suspend fun delete(songId: String)

    /** URI of the downloaded cover art with [coverArtId], or null if it isn't downloaded. */
    fun coverUriOf(coverArtId: String): String?

    /** Downloads [url] as the cover art with [coverArtId]. Throws if that fails. */
    suspend fun downloadCover(coverArtId: String, url: String)

    suspend fun deleteCover(coverArtId: String)
}

/** Null where songs can't be downloaded. */
expect fun createSongFileStore(): SongFileStore?

/** A downloaded song to give to another app: [uri] of its file, see [SongFileStore.uriOf], and its name without extension. */
class SharedSongFile(val uri: String, val name: String)

fun interface SongFileSharer {
    /** Lets the user pick an app to send [files] to. Throws if that fails. */
    suspend fun share(files: List<SharedSongFile>)
}

/** Null where songs can't be shared with other apps. */
expect fun createSongFileSharer(): SongFileSharer?

/** Name of the file of the downloaded song [songId]. */
internal fun songFileName(songId: String) = songId.replace(Regex("[^A-Za-z0-9_-]"), "_")

/** Name of the file of the downloaded cover art [coverArtId], which may be a long URL (YouTube Music thumbnails). */
internal fun coverFileName(coverArtId: String) = md5(coverArtId)
