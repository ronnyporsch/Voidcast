package de.ronnyporsch.voidcast.platform

import de.ronnyporsch.voidcast.util.md5
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Keeps the songs and their cover art as files in the directory [baseDir] returns; [uriOf] gives the URI of such a file. */
internal class FileSongFileStore(
    baseDir: () -> File,
    private val uriOf: (File) -> String,
) : SongFileStore {
    private val dir by lazy { File(baseDir(), "songs").apply { mkdirs() } }
    private val coverDir by lazy { File(baseDir(), "covers").apply { mkdirs() } }

    // Asked for every song that is played, so don't hit the file system each time
    private val downloaded: MutableSet<String> by lazy { filesIn(dir) }
    // Asked for every cover that is shown
    private val downloadedCovers: MutableSet<String> by lazy { filesIn(coverDir) }

    private fun filesIn(dir: File): MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply {
        dir.listFiles()?.filter { !it.name.endsWith(".part") }?.forEach { add(it.name) }
    }

    override fun uriOf(songId: String): String? {
        val name = songFileName(songId)
        return if (name in downloaded) uriOf(File(dir, name)) else null
    }

    override suspend fun download(songId: String, url: String) {
        val name = songFileName(songId)
        fetch(url, File(dir, name))
        downloaded += name
    }

    override suspend fun delete(songId: String) = withContext(Dispatchers.IO) {
        val name = songFileName(songId)
        downloaded -= name
        File(dir, name).delete()
        Unit
    }

    override fun coverUriOf(coverArtId: String): String? {
        val name = coverFileName(coverArtId)
        return if (name in downloadedCovers) uriOf(File(coverDir, name)) else null
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
        // Downloaded next to the file first, so a download that breaks off doesn't leave a truncated file behind
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
