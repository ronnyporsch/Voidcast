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
 * Stores [secret] under [key] encrypted with the device's keystore, see [SecretCipher]. Only where the keystore
 * doesn't work it stays readable, as in the versions before.
 */
private fun SharedPreferences.Editor.putSecret(key: String, secret: String): SharedPreferences.Editor {
    if (secret.isEmpty()) return remove("${key}Encrypted").remove(key)
    val encrypted = SecretCipher.encrypt(secret)
    return if (encrypted != null) putString("${key}Encrypted", encrypted).remove(key)
    else remove("${key}Encrypted").putString(key, secret)
}

private fun SharedPreferences.getSecret(key: String): String =
    getString("${key}Encrypted", null)?.let { SecretCipher.decrypt(it) ?: "" } ?: getString(key, "") ?: ""

actual fun saveSettings(settings: AppSettings) {
    prefs.edit()
        .putString("serverType", settings.serverType.name)
        .putString("serverUrl", settings.serverUrl)
        .putString("username", settings.username)
        .putSecret("password", settings.password)
        .putSecret("accessToken", settings.accessToken)
        .putString("jellyfinUserId", settings.jellyfinUserId)
        .putString("subsonicSalt", settings.subsonicSalt)
        .putBoolean("allowUntrustedCertificate", settings.allowUntrustedCertificate)
        .putString("certificateFingerprint", settings.certificateFingerprint)
        .putBoolean("gaplessEnabled", settings.playback.gaplessEnabled)
        .putBoolean("crossfadeEnabled", settings.playback.crossfadeEnabled)
        .putInt("crossfadeDurationSeconds", settings.playback.crossfadeDurationSeconds)
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
    // Versions before kept these readable
    if (p.contains("password") || p.contains("accessToken")) {
        p.edit().putSecret("password", p.getString("password", "") ?: "").putSecret("accessToken", p.getString("accessToken", "") ?: "").apply()
    }
    return AppSettings(
        serverType = ServerType.entries.firstOrNull { it.name == p.getString("serverType", "") } ?: ServerType.Subsonic,
        serverUrl = serverUrl,
        username = p.getString("username", "") ?: "",
        password = p.getSecret("password"),
        accessToken = p.getSecret("accessToken"),
        jellyfinUserId = p.getString("jellyfinUserId", "") ?: "",
        subsonicSalt = p.getString("subsonicSalt", "") ?: "",
        allowUntrustedCertificate = p.getBoolean("allowUntrustedCertificate", false),
        certificateFingerprint = p.getString("certificateFingerprint", "") ?: "",
        playback = PlaybackSettings(
            gaplessEnabled = p.getBoolean("gaplessEnabled", false),
            crossfadeEnabled = p.getBoolean("crossfadeEnabled", false),
            crossfadeDurationSeconds = p.getInt("crossfadeDurationSeconds", 5),
            shuffle = p.getBoolean("shuffle", false),
            repeatMode = RepeatMode.entries.firstOrNull { it.name == p.getString("repeatMode", "") } ?: RepeatMode.Off,
        ),
        jellyfinDeviceId = p.getString("jellyfinDeviceId", "") ?: "",
        libraryView = decodeLibraryView(p.getString("libraryView", null)),
        offlineMode = p.getBoolean("offlineMode", false),
        downloadOnlyOnWifi = p.getBoolean("downloadOnlyOnWifi", false),
    )
}
