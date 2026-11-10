package de.ronnyporsch.voidcast.platform

import com.sun.jna.NativeLibrary
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import kotlin.concurrent.thread

/**
 * JACK (of PipeWire), which VLC plays through on Linux. VLC links its output to the default device once, when a song
 * starts; this moves it along when the default device changes during a song, e.g. when headphones connect or
 * disconnect.
 */
internal object JackOutput {
    /** What VLC's outputs are called (`--jack-name`). */
    const val NAME = "Voidcast"

    private const val AUDIO = "32 bit float mono audio"
    private const val IS_INPUT = 1L
    private const val IS_OUTPUT = 2L

    private var jack: NativeLibrary? = null
    private var client: Pointer? = null
    private var follower: Thread? = null
    /** The ports of the default device VLC's outputs were last seen with. */
    private var defaultDevice: List<String>? = null

    /**
     * What's wrong if JACK can't be used, null if it can. VLC itself would only play silently then. That's without
     * PipeWire's JACK, or with a real JACK whose server isn't running.
     */
    @Synchronized
    fun error(): String? {
        if (client == null) open()
        if (client == null) return "Playback needs pipewire-jack and a running PipeWire"
        if (follower == null) {
            follower = thread(name = "JACK default device", isDaemon = true) {
                while (true) {
                    Thread.sleep(500)
                    try {
                        follow()
                    } catch (e: Throwable) {
                        System.err.println("Following the default audio device failed: $e")
                    }
                }
            }
        }
        return null
    }

    private fun open() {
        try {
            // The library VLC's JACK plugin uses. 1: don't start a server
            val jack = NativeLibrary.getInstance("libjack.so.0")
            client = jack.getFunction("jack_client_open").invokePointer(arrayOf("$NAME control", 1, IntByReference()))
            this.jack = jack
        } catch (e: Throwable) {
            // No JACK library installed
        }
    }

    @Synchronized
    private fun follow() {
        val client = client ?: return open()
        // PipeWire's JACK calls the ports of the default device "system", like VLC looks them up
        val device = ports(client, "system", IS_INPUT)
        if (device.isEmpty()) {
            // No device at all, or PipeWire was restarted: then this client is dead
            if (ports(client, "", IS_OUTPUT).isEmpty()) {
                call("jack_client_close", client)
                this.client = null
            }
            return
        }
        val changed = defaultDevice != null && defaultDevice != device
        defaultDevice = device
        // VLC has one output per channel, in the order of the channels; a second player is called e.g. "Voidcast-01"
        val outputs = ports(client, "^$NAME(-[0-9]+)?:", IS_OUTPUT).groupBy { it.substringBefore(':') }.values
        for (output in outputs.flatMap { it.withIndex() }) {
            val wanted = device[output.index % device.size]
            val port = jack!!.getFunction("jack_port_by_name").invokePointer(arrayOf(client, output.value)) ?: continue
            val linked = strings(jack!!.getFunction("jack_port_get_all_connections").invokePointer(arrayOf(client, port)))
            // Links made by hand (e.g. in a patchbay) stay until the default device changes
            if (linked.isNotEmpty() && (!changed || linked == listOf(wanted))) continue
            linked.forEach { call("jack_disconnect", client, output.value, it) }
            call("jack_connect", client, output.value, wanted)
        }
    }

    private fun ports(client: Pointer, namePattern: String, flags: Long): List<String> =
        strings(jack!!.getFunction("jack_get_ports").invokePointer(arrayOf(client, namePattern, AUDIO, NativeLong(flags))))

    /** Reads and frees a list of strings JACK returned. */
    private fun strings(list: Pointer?): List<String> {
        list ?: return emptyList()
        return list.getStringArray(0).toList().also { jack!!.getFunction("jack_free").invoke(arrayOf(list)) }
    }

    private fun call(function: String, vararg args: Any) = jack!!.getFunction(function).invokeInt(args)
}
