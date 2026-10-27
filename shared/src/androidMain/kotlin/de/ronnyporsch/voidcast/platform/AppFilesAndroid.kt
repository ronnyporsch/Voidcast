package de.ronnyporsch.voidcast.platform

import android.net.Uri
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

/** Keeps the songs in the app's internal storage, which no other app can access. */
private object AndroidSongFileStore : SongFileStore {
    private val dir by lazy { File(AppContextHolder.context.filesDir, "songs").apply { mkdirs() } }

    // Asked for every song that is played, so don't hit the file system each time
    private val downloaded: MutableSet<String> by lazy {
        ConcurrentHashMap.newKeySet<String>().apply {
            dir.listFiles()?.filter { !it.name.endsWith(".part") }?.forEach { add(it.name) }
        }
    }

    private fun fileName(songId: String) = songId.replace(Regex("[^A-Za-z0-9_-]"), "_")

    override fun uriOf(songId: String): String? {
        val name = fileName(songId)
        return if (name in downloaded) Uri.fromFile(File(dir, name)).toString() else null
    }

    override suspend fun download(songId: String, url: String) = withContext(Dispatchers.IO) {
        val name = fileName(songId)
        val file = File(dir, name)
        val part = File(dir, "$name.part")
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
            downloaded += name
        } finally {
            part.delete()
        }
    }

    override suspend fun delete(songId: String) = withContext(Dispatchers.IO) {
        val name = fileName(songId)
        downloaded -= name
        File(dir, name).delete()
        Unit
    }
}
