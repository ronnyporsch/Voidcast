package de.ronnyporsch.voidcast.data.api

import de.ronnyporsch.voidcast.data.dto.*
import de.ronnyporsch.voidcast.domain.model.Album
import de.ronnyporsch.voidcast.domain.model.Artist
import de.ronnyporsch.voidcast.domain.model.Playlist
import de.ronnyporsch.voidcast.domain.model.Song
import de.ronnyporsch.voidcast.domain.model.codecDisplayName

fun ArtistDto.toDomain() = Artist(
    id = id,
    name = name,
    albumCount = albumCount,
    coverArtId = coverArt,
)

fun AlbumDto.toDomain() = Album(
    id = id,
    name = name,
    artist = artist,
    artistId = artistId,
    artists = creditedArtists(artists, artistId, artist),
    coverArtId = coverArt,
    songCount = songCount,
    year = year,
    duration = duration,
    dateAdded = created,
)

fun AlbumWithSongsDto.toDomain() = Album(
    id = id,
    name = name,
    artist = artist,
    artistId = artistId,
    artists = creditedArtists(artists, artistId, artist),
    coverArtId = coverArt,
    songCount = songCount,
    year = year,
    duration = duration,
    songs = song.map { it.toDomain() },
    dateAdded = created,
)

fun SongDto.toDomain() = Song(
    id = id,
    title = title,
    artist = artist,
    artistId = artistId,
    artists = creditedArtists(artists, artistId, artist),
    album = album,
    albumId = albumId,
    coverArtId = coverArt,
    duration = duration,
    track = track,
    discNumber = discNumber,
    year = year,
    genre = genre,
    bitRate = bitRate,
    codec = suffix?.takeIf { it.isNotBlank() }?.let(::codecDisplayName),
    dateAdded = created,
)

fun PlaylistDto.toDomain() = Playlist(
    id = id,
    name = name,
    songCount = songCount,
    duration = duration,
    coverArtId = coverArt,
    dateAdded = created,
)

fun PlaylistWithSongsDto.toDomain() = Playlist(
    id = id,
    name = name,
    songCount = songCount,
    duration = duration,
    coverArtId = coverArt,
    songs = entry.map { it.toDomain() },
    dateAdded = created,
)

/** Falls back to the single display artist for servers without OpenSubsonic multi-artist support. */
private fun creditedArtists(artists: List<ArtistDto>, artistId: String, artist: String): List<Artist> = when {
    artists.isNotEmpty() -> artists.map { it.toDomain() }
    artist.isNotBlank() -> listOf(Artist(id = artistId, name = artist))
    else -> emptyList()
}
