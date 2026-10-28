package de.ronnyporsch.voidcast.platform

import android.net.Uri
import de.ronnyporsch.voidcast.util.md5
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

actual suspend fun readAppFile(name: String): String? = withContext(Dispatchers.IO) {
    File(AppContextHolder.context.filesDir, name).takeIf { it.isFile }?.readText()
}

// Writes of the same file would share its temporary file, so one could rename the other's away and fail
private val writeMutex = Mutex()

actual suspend fun writeAppFile(name: String, content: String?) = writeMutex.withLock {
    withContext(Dispatchers.IO) {
        val file = File(AppContextHolder.context.filesDir, name)
        if (content == null) {
            file.delete()
        } else {
            // Write a temporary file first, so a crash mid-write doesn't leave a truncated file behind
            val temp = File(file.parentFile, "$name.tmp")
            temp.writeText(content)
            if (!temp.renameTo(file)) throw IOException("Can't write $file")
        }
        Unit
    }
}

actual fun createSongFileStore(): SongFileStore? = AndroidSongFileStore

/** Keeps the songs and their cover art in the app's internal storage, which no other app can access. */
private object AndroidSongFileStore : SongFileStore {
    private val dir by lazy { File(AppContextHolder.context.filesDir, "songs").apply { mkdirs() } }
    private val coverDir by lazy { File(AppContextHolder.context.filesDir, "covers").apply { mkdirs() } }

    // Asked for every song that is played, so don't hit the file system each time
    private val downloaded: MutableSet<String> by lazy { filesIn(dir) }
    // Asked for every cover that is shown
    private val downloadedCovers: MutableSet<String> by lazy { filesIn(coverDir) }

    private fun filesIn(dir: File): MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply {
        dir.listFiles()?.filter { !it.name.endsWith(".part") }?.forEach { add(it.name) }
    }

    private fun fileName(songId: String) = songId.replace(Regex("[^A-Za-z0-9_-]"), "_")

    // Cover art ids may be long URLs (YouTube Music thumbnails)
    private fun coverFileName(coverArtId: String) = md5(coverArtId)

    override fun uriOf(songId: String): String? {
        val name = fileName(songId)
        return if (name in downloaded) Uri.fromFile(File(dir, name)).toString() else null
    }

    override suspend fun download(songId: String, url: String) {
        val name = fileName(songId)
        fetch(url, File(dir, name))
        downloaded += name
    }

    override suspend fun delete(songId: String) = withContext(Dispatchers.IO) {
        val name = fileName(songId)
        downloaded -= name
        File(dir, name).delete()
        Unit
    }

    override fun coverUriOf(coverArtId: String): String? {
        val name = coverFileName(coverArtId)
        return if (name in downloadedCovers) Uri.fromFile(File(coverDir, name)).toString() else null
    }

    override suspend fun downloadCover(coverArtId: String, url: String) {
        val name = coverFileName(coverArtId)
        fetch(url, File(coverDir, name))
        downloadedCovers += name
    }

    override suspend fun deleteCover(coverArtId: String) = withContext(Dispatchers.IO) {
        val name = coverFileName(coverArtId)
        downloadedCovers -= name
        File(coverDir, name).delete()
        Unit
    }

    private suspend fun fetch(url: String, file: File) = withContext(Dispatchers.IO) {
        val part = File(file.parentFile, "${file.name}.part")
        try {
            sharedOkHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("Empty response")
                part.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                        }
                    }
                }
            }
            if (!part.renameTo(file)) throw IOException("Can't write $file")
        } finally {
            part.delete()
        }
    }
}
