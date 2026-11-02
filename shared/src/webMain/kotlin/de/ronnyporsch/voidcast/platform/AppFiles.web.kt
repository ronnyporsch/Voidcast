package de.ronnyporsch.voidcast.platform

import js.buffer.toByteArray
import js.iterable.asFlow
import js.objects.unsafeJso
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import web.blob.text
import web.fs.FileSystemDirectoryHandle
import web.fs.FileSystemGetDirectoryOptions
import web.fs.FileSystemGetFileOptions
import web.fs.createWritable
import web.fs.getDirectoryHandle
import web.fs.getFile
import web.fs.getFileHandle
import web.fs.removeEntry
import web.fs.write
import web.globals.isSecureContext
import web.http.Response
import web.http.arrayBuffer
import web.http.fetch
import web.navigator.navigator
import web.storage.getDirectory
import web.storage.persist
import web.streams.close
import web.streams.pipeTo
import web.url.URL
import kotlin.io.encoding.Base64

// The origin private file system only exists in secure contexts (HTTPS or localhost)
actual fun createSongFileStore(): SongFileStore? = if (isSecureContext) songFileStore else null

private val songFileStore by lazy { OpfsSongFileStore() }

/**
 * Keeps the songs and their cover art in the browser's origin private file system. The songs play from blob URLs
 * of their files; the covers are kept as data URLs, which the image loader can load (unlike blob URLs).
 */
private class OpfsSongFileStore : SongFileStore {
    private class Dirs(val songs: FileSystemDirectoryHandle, val covers: FileSystemDirectoryHandle)

    // The browser runs everything on one thread, so the maps need no locking
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Blob URLs of the downloaded songs, by file name. */
    private val songUrls = mutableMapOf<String, String>()

    /** Data URLs of the downloaded covers, by file name. */
    private val coverUrls = mutableMapOf<String, String>()

    private val dirs = scope.async {
        val root = navigator.storage.getDirectory()
        val create = unsafeJso<FileSystemGetDirectoryOptions> { create = true }
        Dirs(root.getDirectoryHandle("songs", create), root.getDirectoryHandle("covers", create))
    }

    private val loading = scope.async {
        val dirs = dirs.await()
        for (name in fileNames(dirs.songs)) {
            runCatching {
                val file = dirs.songs.getFileHandle(name).getFile()
                // Left by a download that broke off: a file only gets its content once it is written completely
                if (file.size > 0) songUrls[name] = URL.createObjectURL(file) else dirs.songs.removeEntry(name)
            }
        }
        for (name in fileNames(dirs.covers)) {
            runCatching {
                val dataUrl = dirs.covers.getFileHandle(name).getFile().text()
                if (dataUrl.isNotEmpty()) coverUrls[name] = dataUrl else dirs.covers.removeEntry(name)
            }
        }
    }

    private var persistRequested = false

    private suspend fun fileNames(dir: FileSystemDirectoryHandle): List<String> =
        dir.keys().asFlow().toList().map { it.toString() }

    // Without an origin private file system (e.g. in some private windows) nothing is downloaded
    override suspend fun awaitLoaded() {
        runCatching { loading.await() }
    }

    override fun uriOf(songId: String): String? = songUrls[songFileName(songId)]

    override suspend fun download(songId: String, url: String) {
        val name = songFileName(songId)
        val dir = dirs.await()
        val response = fetchOk(url)
        val body = response.body ?: error("Empty response")
        write(dir.songs, name) { handle ->
            // Only once the stream is closed (which pipeTo does when it is through) does the file get the content
            body.pipeTo(handle.createWritable())
        }
        val file = dir.songs.getFileHandle(name).getFile()
        songUrls.put(name, URL.createObjectURL(file))?.let(URL::revokeObjectURL)
        requestPersistence()
    }

    override suspend fun delete(songId: String) {
        val name = songFileName(songId)
        songUrls.remove(name)?.let(URL::revokeObjectURL)
        runCatching { dirs.await().songs.removeEntry(name) }
    }

    override fun coverUriOf(coverArtId: String): String? = coverUrls[coverFileName(coverArtId)]

    override suspend fun downloadCover(coverArtId: String, url: String) {
        val name = coverFileName(coverArtId)
        val dir = dirs.await()
        val response = fetchOk(url)
        val type = response.headers.get("Content-Type")?.substringBefore(';')?.trim()?.ifEmpty { null } ?: "image/jpeg"
        val dataUrl = "data:$type;base64," + Base64.encode(response.arrayBuffer().toByteArray())
        write(dir.covers, name) { handle ->
            val stream = handle.createWritable()
            stream.write(dataUrl.toJsString())
            stream.close()
        }
        coverUrls[name] = dataUrl
    }

    override suspend fun deleteCover(coverArtId: String) {
        val name = coverFileName(coverArtId)
        coverUrls.remove(name)
        runCatching { dirs.await().covers.removeEntry(name) }
    }

    private suspend fun fetchOk(url: String): Response {
        val response = fetch(url)
        if (!response.ok) error("HTTP ${response.status}")
        return response
    }

    /** Writes the file [name] in [dir] with [block]; removes it again if that fails. */
    private suspend fun write(
        dir: FileSystemDirectoryHandle,
        name: String,
        block: suspend (web.fs.FileSystemFileHandle) -> Unit,
    ) {
        try {
            block(dir.getFileHandle(name, unsafeJso<FileSystemGetFileOptions> { create = true }))
        } catch (e: Throwable) {
            withContext(NonCancellable) { runCatching { dir.removeEntry(name) } }
            throw e
        }
    }

    /** Asks the browser not to delete the downloads when it runs low on storage. */
    private suspend fun requestPersistence() {
        if (persistRequested) return
        persistRequested = true
        runCatching { navigator.storage.persist() }
    }
}
