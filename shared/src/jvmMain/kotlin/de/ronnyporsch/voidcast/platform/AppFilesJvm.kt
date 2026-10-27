package de.ronnyporsch.voidcast.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

private val dataDir: File by lazy {
    val base = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
        ?: (System.getProperty("user.home") + "/.local/share")
    File(base, "voidcast").apply { mkdirs() }
}

actual suspend fun readAppFile(name: String): String? = withContext(Dispatchers.IO) {
    File(dataDir, name).takeIf { it.isFile }?.readText()
}

// Writes of the same file would share its temporary file, so one could rename the other's away and fail
private val writeMutex = Mutex()

actual suspend fun writeAppFile(name: String, content: String?) = writeMutex.withLock {
    withContext(Dispatchers.IO) {
        val file = File(dataDir, name)
        if (content == null) {
            file.delete()
        } else {
            val temp = File(dataDir, "$name.tmp")
            temp.writeText(content)
            if (!temp.renameTo(file)) throw IOException("Can't write $file")
        }
        Unit
    }
}

actual fun createSongFileStore(): SongFileStore? = null
