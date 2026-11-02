package de.ronnyporsch.voidcast.platform

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

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

/** Keeps the songs and their cover art in the app's internal storage, which no other app can access. */
actual fun createSongFileStore(): SongFileStore? = songFileStore

private val songFileStore by lazy {
    FileSongFileStore({ AppContextHolder.context.filesDir }) { Uri.fromFile(it).toString() }
}
