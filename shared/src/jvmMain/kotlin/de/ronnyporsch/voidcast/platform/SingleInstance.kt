package de.ronnyporsch.voidcast.platform

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.StandardOpenOption
import kotlin.concurrent.thread

/**
 * Keeps the desktop app to one instance: the first one holds a lock file and listens on a local port, which later
 * starts use to bring it to the front instead of opening another window.
 */
object SingleInstance {
    private val lockFile = File(dataDir, "instance.lock")
    private val portFile = File(dataDir, "instance.port")
    private var lock: FileLock? = null

    private val _activationRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Another start of the app asked this instance to show its window. */
    val activationRequests: SharedFlow<Unit> = _activationRequests

    /** Whether this is the only instance; otherwise the running one got asked to show itself. */
    fun acquire(): Boolean {
        val channel = FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        lock = try {
            channel.tryLock()
        } catch (_: IOException) {
            null
        }
        if (lock == null) {
            channel.close()
            // Without an answer the other instance is gone or stuck; then start anyway rather than not at all
            return !activateRunningInstance()
        }
        listen()
        return true
    }

    private fun activateRunningInstance(): Boolean = try {
        val port = portFile.readText().trim().toInt()
        Socket(InetAddress.getLoopbackAddress(), port).use { it.getOutputStream().write(ACTIVATE) }
        true
    } catch (_: Exception) {
        false
    }

    private fun listen() {
        val server = try {
            ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        } catch (_: IOException) {
            return
        }
        portFile.writeText(server.localPort.toString())
        thread(isDaemon = true, name = "single-instance") {
            while (true) {
                val request = try {
                    server.accept().use {
                        it.soTimeout = 1000
                        it.getInputStream().read()
                    }
                } catch (_: IOException) {
                    continue
                }
                if (request == ACTIVATE) _activationRequests.tryEmit(Unit)
            }
        }
    }

    private const val ACTIVATE = 1
}
