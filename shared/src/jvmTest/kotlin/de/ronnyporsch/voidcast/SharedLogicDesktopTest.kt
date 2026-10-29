package de.ronnyporsch.voidcast

import de.ronnyporsch.voidcast.platform.VlcPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.sound.sampled.*
import kotlin.test.Test
import kotlin.test.assertTrue

class SharedLogicDesktopTest {

    private fun createWavBytes(durationSeconds: Int = 1): ByteArray {
        val sampleRate = 44100f
        val channels = 2
        val sampleSizeInBits = 16
        val frameSize = channels * (sampleSizeInBits / 8)
        val format = AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            sampleRate,
            sampleSizeInBits,
            channels,
            frameSize,
            sampleRate,
            false
        )
        val numFrames = (sampleRate * durationSeconds).toInt()
        val numBytes = numFrames * frameSize
        val pcmData = ByteArray(numBytes)
        for (i in 0 until numFrames) {
            val freq = 440.0
            val angle = 2.0 * Math.PI * i * freq / sampleRate
            val sample = (Math.sin(angle) * 32767).toInt().toShort()
            val offset = i * frameSize
            // left channel
            pcmData[offset] = (sample.toInt() and 0xFF).toByte()
            pcmData[offset + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
            // right channel
            pcmData[offset + 2] = (sample.toInt() and 0xFF).toByte()
            pcmData[offset + 3] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }
        val bais = ByteArrayInputStream(pcmData)
        val ais = AudioInputStream(bais, format, numFrames.toLong())
        val baos = ByteArrayOutputStream()
        AudioSystem.write(ais, AudioFileFormat.Type.WAVE, baos)
        return baos.toByteArray()
    }

    @Test
    fun testVlcPlayerPlayback() = runBlocking {
        val wavBytes = createWavBytes(1)
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/test.wav") { exchange ->
            exchange.sendResponseHeaders(200, wavBytes.size.toLong())
            exchange.responseBody.use { it.write(wavBytes) }
        }
        server.start()
        val port = server.address.port
        val url = "http://127.0.0.1:$port/test.wav"

        val player = VlcPlayer()
        // VLC isn't installed
        if (!player.supportsVideo) return@runBlocking server.stop(0)
        player.play(url)

        var finished = false
        for (i in 0 until 50) {
            delay(100)
            val state = player.state.value
            if (!state.isPlaying && !state.isBuffering && state.positionMs > 0) {
                finished = true
                break
            }
        }
        player.release()
        server.stop(0)
        assertTrue(finished)
    }

    @Test
    fun testVlcPlayerPauseResumeAndSeek() = runBlocking {
        val wavBytes = createWavBytes(3)
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/test_long.wav") { exchange ->
            exchange.sendResponseHeaders(200, wavBytes.size.toLong())
            exchange.responseBody.use { it.write(wavBytes) }
        }
        server.start()
        val port = server.address.port
        val url = "http://127.0.0.1:$port/test_long.wav"

        val player = VlcPlayer()
        // VLC isn't installed
        if (!player.supportsVideo) return@runBlocking server.stop(0)
        player.play(url)

        // Wait for playback to start
        var started = false
        for (i in 0 until 20) {
            delay(50)
            if (player.state.value.isPlaying) {
                started = true
                break
            }
        }
        assertTrue(started)

        player.pause()
        assertTrue(!player.state.value.isPlaying)

        player.resume()
        assertTrue(player.state.value.isPlaying)

        player.seekTo(1500)
        assertTrue(player.state.value.positionMs >= 1500)

        player.release()
        server.stop(0)
    }
}