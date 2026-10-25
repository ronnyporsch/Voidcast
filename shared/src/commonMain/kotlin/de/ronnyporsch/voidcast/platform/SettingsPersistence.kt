package de.ronnyporsch.voidcast.platform

import de.ronnyporsch.voidcast.domain.model.AppSettings
import de.ronnyporsch.voidcast.domain.model.LibraryViewSettings
import kotlinx.serialization.json.Json

expect fun saveSettings(settings: AppSettings)

expect fun loadSettings(): AppSettings?

private val libraryViewJson = Json { ignoreUnknownKeys = true }

/** [LibraryViewSettings] as one stored value. */
internal fun LibraryViewSettings.encode(): String = libraryViewJson.encodeToString(LibraryViewSettings.serializer(), this)

internal fun decodeLibraryView(value: String?): LibraryViewSettings =
    value?.let { runCatching { libraryViewJson.decodeFromString(LibraryViewSettings.serializer(), it) }.getOrNull() }
        ?: LibraryViewSettings()
