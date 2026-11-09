package de.ronnyporsch.voidcast

import de.ronnyporsch.voidcast.platform.audioFileTypeOf
import de.ronnyporsch.voidcast.platform.sharedFileName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SongFileSharerTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun recognizesFormatsByTheirFirstBytes() {
        assertEquals("flac", audioFileTypeOf("fLaC____".encodeToByteArray()).extension)
        assertEquals("ogg", audioFileTypeOf("OggS".padEnd(36, '_').encodeToByteArray()).extension)
        assertEquals("opus", audioFileTypeOf(("OggS".padEnd(28, '_') + "OpusHead").encodeToByteArray()).extension)
        assertEquals("m4a", audioFileTypeOf("____ftypM4A ".encodeToByteArray()).extension)
        assertEquals("wav", audioFileTypeOf("RIFF____WAVE".encodeToByteArray()).extension)
        assertEquals("webm", audioFileTypeOf(bytes(0x1A, 0x45, 0xDF, 0xA3, 0)).extension)
        assertEquals("mp3", audioFileTypeOf("ID3_".encodeToByteArray()).extension)
        assertEquals("mp3", audioFileTypeOf(bytes(0xFF, 0xFB, 0x90)).extension)
        assertEquals("aac", audioFileTypeOf(bytes(0xFF, 0xF1, 0x50)).extension)
    }

    @Test
    fun unknownFormatsHaveNoExtension() {
        val type = audioFileTypeOf("something else".encodeToByteArray())
        assertNull(type.extension)
        assertEquals("audio/*", type.mimeType)
        assertNull(audioFileTypeOf(ByteArray(0)).extension)
    }

    @Test
    fun fileNamesAreSafe() {
        assertEquals("AC_DC - Back in Black.flac", sharedFileName("AC/DC - Back in Black", "flac"))
        assertEquals("What_.mp3", sharedFileName("What?", "mp3"))
        assertEquals("Song.mp3", sharedFileName("...", "mp3"))
        assertEquals("Title", sharedFileName("Title", null))
        assertEquals("x".repeat(80) + ".mp3", sharedFileName("x".repeat(300), "mp3"))
    }
}
