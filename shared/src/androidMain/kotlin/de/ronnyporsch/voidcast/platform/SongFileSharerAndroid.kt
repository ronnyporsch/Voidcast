package de.ronnyporsch.voidcast.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.system.ErrnoException
import android.system.Os
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

actual fun createSongFileSharer(): SongFileSharer? = SongFileSharer(::shareSongFiles)

/** Directory in the cache the app's FileProvider exposes, see `res/xml/shared_song_paths.xml`. */
private const val SHARED_DIR = "shared_songs"

/** Longest name of a shared file before its extension; file systems allow 255 bytes, which is 85 of most characters. */
private const val MAX_NAME_LENGTH = 80

private const val HEADER_SIZE = 36

private suspend fun shareSongFiles(files: List<SharedSongFile>) {
    val context = AppContextHolder.context
    val shared = withContext(Dispatchers.IO) {
        // Those of the previous share aren't needed any more
        val dir = File(context.cacheDir, SHARED_DIR).apply { deleteRecursively() }
        // Directories of their own, as songs can have the same name
        files.mapIndexed { index, file -> expose(context, file, File(dir, index.toString()).apply { mkdirs() }) }
    }
    val uris = shared.map { it.first }
    val send = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
        type = shared.map { it.second }.distinct().singleOrNull() ?: "audio/*"
        if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.single()) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/**
 * The stored files are named after the song's id and have no extension, so other apps get them under a proper name in
 * [dir]. Returns their URI and MIME type.
 */
private fun expose(context: Context, file: SharedSongFile, dir: File): Pair<Uri, String> {
    val source = File(requireNotNull(Uri.parse(file.uri).path) { "Not a file: ${file.uri}" })
    val header = ByteArray(HEADER_SIZE)
    val headerSize = source.inputStream().use { it.read(header) }.coerceAtLeast(0)
    val type = audioFileTypeOf(header.copyOf(headerSize))
    val target = File(dir, sharedFileName(file.name, type.extension))
    try {
        // Takes no extra space and no time, songs can be big
        Os.link(source.path, target.path)
    } catch (_: ErrnoException) {
        source.copyTo(target)
    }
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target) to type.mimeType
}

/** [extension] is null if the type of the file isn't known. */
internal data class AudioFileType(val extension: String?, val mimeType: String)

/** Songs are downloaded in their original format, which only their first bytes tell reliably. */
internal fun audioFileTypeOf(header: ByteArray): AudioFileType = when {
    header.hasAt(0, "fLaC") -> AudioFileType("flac", "audio/flac")
    header.hasAt(0, "OggS") -> if (header.hasAt(28, "OpusHead")) AudioFileType("opus", "audio/ogg") else AudioFileType("ogg", "audio/ogg")
    header.hasAt(4, "ftyp") -> AudioFileType("m4a", "audio/mp4")
    header.hasAt(0, "RIFF") && header.hasAt(8, "WAVE") -> AudioFileType("wav", "audio/wav")
    header.size >= 4 && header[0] == 0x1A.toByte() && header[1] == 0x45.toByte() && header[2] == 0xDF.toByte() && header[3] == 0xA3.toByte() ->
        AudioFileType("webm", "audio/webm")
    header.hasAt(0, "ID3") -> AudioFileType("mp3", "audio/mpeg")
    // Frame sync: AAC's (ADTS) has layer 0, MP3's doesn't
    header.size >= 2 && header[0] == 0xFF.toByte() && (header[1].toInt() and 0xF6) == 0xF0 -> AudioFileType("aac", "audio/aac")
    header.size >= 2 && header[0] == 0xFF.toByte() && (header[1].toInt() and 0xE0) == 0xE0 -> AudioFileType("mp3", "audio/mpeg")
    else -> AudioFileType(null, "audio/*")
}

private fun ByteArray.hasAt(offset: Int, text: String): Boolean =
    size >= offset + text.length && text.indices.all { this[offset + it] == text[it].code.toByte() }

internal fun sharedFileName(name: String, extension: String?): String {
    val base = name.replace(Regex("[/\\\\:*?\"<>|\\p{Cntrl}]"), "_").take(MAX_NAME_LENGTH).trim().trim('.').ifEmpty { "Song" }
    return if (extension != null) "$base.$extension" else base
}
