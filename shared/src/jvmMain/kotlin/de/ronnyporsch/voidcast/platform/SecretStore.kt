package de.ronnyporsch.voidcast.platform

import com.sun.jna.platform.win32.Crypt32Util
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences
import kotlin.concurrent.thread

/**
 * Keeps one secret of the app where the operating system protects it, instead of readable in the settings: in the
 * keyring on Linux (GNOME Keyring, KWallet, ...), in the keychain on macOS, encrypted for the user account on
 * Windows.
 */
internal interface SecretStore {
    /** The stored secret, or null if there is none or it can't be read. */
    fun load(): String?

    /** Stores [secret], an empty one removes it. False if that didn't work, e.g. because there is no keyring. */
    fun store(secret: String): Boolean
}

internal fun secretStore(name: String, prefs: Preferences): SecretStore {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        "win" in os -> WindowsSecretStore(name, prefs)
        "mac" in os -> MacSecretStore(name)
        else -> LinuxSecretStore(name)
    }
}

private const val APPLICATION = "de.ronnyporsch.voidcast"

/** The Secret Service, through libsecret's `secret-tool`. */
internal class LinuxSecretStore(private val name: String, private val application: String = APPLICATION) : SecretStore {
    private val attributes get() = arrayOf("application", application, "key", name)

    override fun load(): String? = run("secret-tool", "lookup", *attributes)?.takeIf { it.isNotEmpty() }

    override fun store(secret: String): Boolean {
        if (secret.isEmpty()) {
            // Fails as well when there was nothing to remove
            run("secret-tool", "clear", *attributes)
            return load() == null
        }
        return run("secret-tool", "store", "--label=Voidcast", *attributes, input = secret) != null
    }
}

/** The login keychain, through `security`. */
private class MacSecretStore(private val name: String) : SecretStore {
    override fun load(): String? =
        run("security", "find-generic-password", "-a", name, "-s", APPLICATION, "-w")?.trim()?.takeIf { it.isNotEmpty() }

    override fun store(secret: String): Boolean {
        if (secret.isEmpty()) {
            run("security", "delete-generic-password", "-a", name, "-s", APPLICATION)
            return load() == null
        }
        // Passed as a command on the input, as arguments are visible to every process. That one is split at
        // whitespace and quotes.
        if (!secret.all { it.isLetterOrDigit() || it in "._~+/=-" }) return false
        return run("security", "-i", input = "add-generic-password -U -a $name -s $APPLICATION -w $secret\n") != null && load() == secret
    }
}

/** No keyring that works without the user confirming: the secret stays in the settings, encrypted by Windows (DPAPI). */
private class WindowsSecretStore(name: String, private val prefs: Preferences) : SecretStore {
    private val key = "${name}Protected"

    override fun load(): String? = runCatching {
        prefs.get(key, null)?.let { Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(it)).decodeToString() }
    }.getOrNull()

    override fun store(secret: String): Boolean = runCatching {
        if (secret.isEmpty()) prefs.remove(key)
        else prefs.put(key, Base64.getEncoder().encodeToString(Crypt32Util.cryptProtectData(secret.encodeToByteArray())))
    }.isSuccess
}

/**
 * What [command] printed, or null if it failed, isn't installed or didn't finish. The time is for the user to
 * unlock the keyring, which the command waits for.
 */
private fun run(vararg command: String, input: String = ""): String? = runCatching {
    val process = ProcessBuilder(*command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    var output = ""
    val reader = thread { output = runCatching { process.inputStream.bufferedReader().readText() }.getOrDefault("") }
    process.outputStream.use { it.write(input.encodeToByteArray()) }
    if (!process.waitFor(60, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return null
    }
    reader.join()
    output.takeIf { process.exitValue() == 0 }
}.getOrNull()
