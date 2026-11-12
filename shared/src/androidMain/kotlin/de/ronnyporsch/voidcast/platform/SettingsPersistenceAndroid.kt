package de.ronnyporsch.voidcast.platform

import android.content.Context
import android.content.SharedPreferences
import de.ronnyporsch.voidcast.domain.model.AppSettings
import de.ronnyporsch.voidcast.domain.model.PlaybackSettings
import de.ronnyporsch.voidcast.domain.model.RepeatMode
import de.ronnyporsch.voidcast.domain.model.ServerType

private val prefs by lazy {
    AppContextHolder.context.getSharedPreferences("voidcast_settings", Context.MODE_PRIVATE)
}

/**
 * Stores [secret] under [key] encrypted with the device's keystore, see [SecretCipher]. Where the keystore doesn't
 * work it isn't stored at all.
 */
private fun SharedPreferences.Editor.putSecret(key: String, secret: String): SharedPreferences.Editor {
    val encrypted = if (secret.isEmpty()) null else SecretCipher.encrypt(secret)
    return if (encrypted != null) putString(key, encrypted) else remove(key)
}

private fun SharedPreferences.getSecret(key: String): String = getString(key, null)?.let { SecretCipher.decrypt(it) } ?: ""

actual fun saveSettings(settings: AppSettings) {
    prefs.edit()
        .putString("serverType", settings.serverType.name)
        .putString("serverUrl", settings.serverUrl)
        .putString("username", settings.username)
        .putSecret("accessToken", settings.accessToken)
        .putString("jellyfinUserId", settings.jellyfinUserId)
        .putString("subsonicSalt", settings.subsonicSalt)
        .putBoolean("allowUntrustedCertificate", settings.allowUntrustedCertificate)
        .putString("certificateFingerprint", settings.certificateFingerprint)
        .putBoolean("gaplessEnabled", settings.playback.gaplessEnabled)
        .putBoolean("crossfadeEnabled", settings.playback.crossfadeEnabled)
        .putInt("crossfadeDurationSeconds", settings.playback.crossfadeDurationSeconds)
        .putBoolean("normalizationEnabled", settings.playback.normalizationEnabled)
        .putInt("volumeBoostDb", settings.playback.volumeBoostDb)
        .apply { settings.playback.typicalGainDb?.let { putFloat("typicalGainDb", it) } ?: remove("typicalGainDb") }
        .putBoolean("shuffle", settings.playback.shuffle)
        .putString("repeatMode", settings.playback.repeatMode.name)
        .putString("jellyfinDeviceId", settings.jellyfinDeviceId)
        .putString("libraryView", settings.libraryView.encode())
        .putBoolean("offlineMode", settings.offlineMode)
        .putBoolean("downloadOnlyOnWifi", settings.downloadOnlyOnWifi)
        .apply()
}

actual fun loadSettings(): AppSettings? {
    val p = prefs
    val serverUrl = p.getString("serverUrl", "") ?: ""
    if (serverUrl.isBlank()) return null
    return AppSettings(
        serverType = ServerType.entries.firstOrNull { it.name == p.getString("serverType", "") } ?: ServerType.Subsonic,
        serverUrl = serverUrl,
        username = p.getString("username", "") ?: "",
        accessToken = p.getSecret("accessToken"),
        jellyfinUserId = p.getString("jellyfinUserId", "") ?: "",
        subsonicSalt = p.getString("subsonicSalt", "") ?: "",
        allowUntrustedCertificate = p.getBoolean("allowUntrustedCertificate", false),
        certificateFingerprint = p.getString("certificateFingerprint", "") ?: "",
        playback = PlaybackSettings(
            gaplessEnabled = p.getBoolean("gaplessEnabled", false),
            crossfadeEnabled = p.getBoolean("crossfadeEnabled", false),
            crossfadeDurationSeconds = p.getInt("crossfadeDurationSeconds", 5),
            normalizationEnabled = p.getBoolean("normalizationEnabled", true),
            volumeBoostDb = p.getInt("volumeBoostDb", 0),
            typicalGainDb = if (p.contains("typicalGainDb")) p.getFloat("typicalGainDb", 0f) else null,
            shuffle = p.getBoolean("shuffle", false),
            repeatMode = RepeatMode.entries.firstOrNull { it.name == p.getString("repeatMode", "") } ?: RepeatMode.Off,
        ),
        jellyfinDeviceId = p.getString("jellyfinDeviceId", "") ?: "",
        libraryView = decodeLibraryView(p.getString("libraryView", null)),
        offlineMode = p.getBoolean("offlineMode", false),
        downloadOnlyOnWifi = p.getBoolean("downloadOnlyOnWifi", false),
    )
}
