package de.ronnyporsch.voidcast.platform

import de.ronnyporsch.voidcast.domain.model.AppSettings
import de.ronnyporsch.voidcast.domain.model.PlaybackSettings
import de.ronnyporsch.voidcast.domain.model.RepeatMode
import de.ronnyporsch.voidcast.domain.model.ServerType
import java.util.prefs.Preferences

private val prefs = Preferences.userNodeForPackage(AppSettings::class.java)

/**
 * The access token is kept by the operating system, see [SecretStore], the settings only note that it is there.
 * Only where that doesn't work (no keyring) it stays in the settings, as in the versions before.
 */
private object AccessToken {
    private const val KEY = "accessToken"
    private const val IN_STORE = "accessTokenInSecretStore"
    private val store by lazy { secretStore(KEY, prefs) }

    /** What the store holds, as far as known, so saving unchanged settings doesn't ask it again. */
    private var stored: String? = null

    /** What the store didn't take, so it isn't asked (and the user to unlock the keyring) with every change. */
    private var refused: String? = null

    @Synchronized
    fun load(): String {
        val readable = prefs.get(KEY, "") ?: ""
        if (readable.isNotEmpty()) return readable.also {
            save(it)
            runCatching { prefs.flush() }
        }
        if (!prefs.getBoolean(IN_STORE, false)) return ""
        return (store.load() ?: "").also { stored = it }
    }

    @Synchronized
    fun save(token: String) {
        val inStore = when {
            token == stored -> true
            // Nothing to remove from a store that never got anything
            token.isEmpty() && !prefs.getBoolean(IN_STORE, false) -> true
            token == refused -> false
            else -> store.store(token)
        }
        if (inStore) stored = token else refused = token
        prefs.put(KEY, if (inStore) "" else token)
        prefs.putBoolean(IN_STORE, inStore && token.isNotEmpty())
    }
}

actual fun saveSettings(settings: AppSettings) {
    prefs.put("serverType", settings.serverType.name)
    prefs.put("serverUrl", settings.serverUrl)
    prefs.put("username", settings.username)
    prefs.put("password", settings.password)
    AccessToken.save(settings.accessToken)
    prefs.put("jellyfinUserId", settings.jellyfinUserId)
    prefs.put("subsonicSalt", settings.subsonicSalt)
    prefs.putBoolean("allowUntrustedCertificate", settings.allowUntrustedCertificate)
    prefs.put("certificateFingerprint", settings.certificateFingerprint)
    prefs.putBoolean("gaplessEnabled", settings.playback.gaplessEnabled)
    prefs.putBoolean("crossfadeEnabled", settings.playback.crossfadeEnabled)
    prefs.putInt("crossfadeDurationSeconds", settings.playback.crossfadeDurationSeconds)
    prefs.putBoolean("shuffle", settings.playback.shuffle)
    prefs.put("repeatMode", settings.playback.repeatMode.name)
    prefs.put("jellyfinDeviceId", settings.jellyfinDeviceId)
    prefs.put("libraryView", settings.libraryView.encode())
    prefs.flush()
}

actual fun loadSettings(): AppSettings? {
    val serverUrl = prefs.get("serverUrl", "") ?: ""
    if (serverUrl.isBlank()) return null
    return AppSettings(
        serverType = ServerType.entries.firstOrNull { it.name == prefs.get("serverType", "") } ?: ServerType.Subsonic,
        serverUrl = serverUrl,
        username = prefs.get("username", "") ?: "",
        password = prefs.get("password", "") ?: "",
        accessToken = AccessToken.load(),
        jellyfinUserId = prefs.get("jellyfinUserId", "") ?: "",
        subsonicSalt = prefs.get("subsonicSalt", "") ?: "",
        allowUntrustedCertificate = prefs.getBoolean("allowUntrustedCertificate", false),
        certificateFingerprint = prefs.get("certificateFingerprint", "") ?: "",
        playback = PlaybackSettings(
            gaplessEnabled = prefs.getBoolean("gaplessEnabled", false),
            crossfadeEnabled = prefs.getBoolean("crossfadeEnabled", false),
            crossfadeDurationSeconds = prefs.getInt("crossfadeDurationSeconds", 5),
            shuffle = prefs.getBoolean("shuffle", false),
            repeatMode = RepeatMode.entries.firstOrNull { it.name == prefs.get("repeatMode", "") } ?: RepeatMode.Off,
        ),
        jellyfinDeviceId = prefs.get("jellyfinDeviceId", "") ?: "",
        libraryView = decodeLibraryView(prefs.get("libraryView", null)),
    )
}
