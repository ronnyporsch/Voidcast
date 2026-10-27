package de.ronnyporsch.voidcast

import de.ronnyporsch.voidcast.data.api.toDomain
import de.ronnyporsch.voidcast.data.dto.AlbumDto
import de.ronnyporsch.voidcast.data.dto.ArtistDto
import de.ronnyporsch.voidcast.data.dto.SongDto
import de.ronnyporsch.voidcast.data.repository.collectAppearsOnAlbumIds
import de.ronnyporsch.voidcast.domain.model.Album
import de.ronnyporsch.voidcast.domain.model.Artist
import de.ronnyporsch.voidcast.domain.model.LibraryCategory
import de.ronnyporsch.voidcast.domain.model.LibraryOrder
import de.ronnyporsch.voidcast.domain.model.LibrarySort
import de.ronnyporsch.voidcast.domain.model.LibraryViewSettings
import de.ronnyporsch.voidcast.platform.decodeLibraryView
import de.ronnyporsch.voidcast.platform.encode
import de.ronnyporsch.voidcast.util.sortedBy
import de.ronnyporsch.voidcast.domain.model.Song
import de.ronnyporsch.voidcast.domain.model.formatDuration
import de.ronnyporsch.voidcast.domain.model.formatDurationMs
import de.ronnyporsch.voidcast.ui.components.TouchSelection
import de.ronnyporsch.voidcast.ui.components.filtered
import de.ronnyporsch.voidcast.ui.components.selectedThrough
import de.ronnyporsch.voidcast.ui.navigation.Navigator
import de.ronnyporsch.voidcast.ui.navigation.Screen
import de.ronnyporsch.voidcast.util.md5
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SharedCommonTest {

    @Test
    fun md5_produces_correct_hash() {
        assertEquals("5d41402abc4b2a76b9719d911017c592", md5("hello"))
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", md5(""))
        assertEquals("098f6bcd4621d373cade4e832627b4f6", md5("test"))
    }

    @Test
    fun md5_subsonic_auth_token() {
        val password = "sesame"
        val salt = "c19b2d"
        val token = md5(password + salt)
        assertEquals("26719a1196d2a940705a59634eb18eab", token)
    }

    @Test
    fun formatDuration_formats_correctly() {
        assertEquals("0:00", 0.formatDuration())
        assertEquals("1:00", 60.formatDuration())
        assertEquals("3:45", 225.formatDuration())
        assertEquals("10:05", 605.formatDuration())
    }

    @Test
    fun formatDurationMs_formats_correctly() {
        assertEquals("0:00", 0L.formatDurationMs())
        assertEquals("1:00", 60000L.formatDurationMs())
        assertEquals("3:45", 225000L.formatDurationMs())
    }

    @Test
    fun artistDto_maps_to_domain() {
        val dto = ArtistDto(id = "ar1", name = "Test Artist", albumCount = 5, coverArt = "cover1")
        val domain = dto.toDomain()
        assertEquals("ar1", domain.id)
        assertEquals("Test Artist", domain.name)
        assertEquals(5, domain.albumCount)
        assertEquals("cover1", domain.coverArtId)
    }

    @Test
    fun albumDto_maps_to_domain() {
        val dto = AlbumDto(
            id = "al1", name = "Test Album", artist = "Artist",
            artistId = "ar1", coverArt = "c1", songCount = 10, year = 2024, duration = 3600
        )
        val domain = dto.toDomain()
        assertEquals("al1", domain.id)
        assertEquals("Test Album", domain.name)
        assertEquals("Artist", domain.artist)
        assertEquals(10, domain.songCount)
        assertEquals(2024, domain.year)
    }

    @Test
    fun songDto_maps_to_domain() {
        val dto = SongDto(
            id = "s1", title = "My Song", artist = "Artist", artistId = "ar1",
            album = "Album", albumId = "al1", duration = 180, track = 3
        )
        val domain = dto.toDomain()
        assertEquals("s1", domain.id)
        assertEquals("My Song", domain.title)
        assertEquals(180, domain.duration)
        assertEquals(3, domain.track)
    }

    @Test
    fun albums_with_different_artists_are_deduplicated_by_id() {
        val albumFromArtist1 = AlbumDto(
            id = "album_collab",
            name = "Collaborative Album",
            artist = "Artist A",
            artistId = "ar1",
            coverArt = "c1",
            songCount = 10,
            year = 2024,
            duration = 3600
        ).toDomain()

        val albumFromArtist2 = AlbumDto(
            id = "album_collab",
            name = "Collaborative Album",
            artist = "Artist B",
            artistId = "ar2",
            coverArt = "c1",
            songCount = 10,
            year = 2024,
            duration = 3600
        ).toDomain()

        val rawAlbums = listOf(albumFromArtist1, albumFromArtist2)
        val deduplicated = rawAlbums.distinctBy { it.id.ifEmpty { "${it.name}_${it.artist}" } }

        assertEquals(1, deduplicated.size)
        assertEquals("album_collab", deduplicated[0].id)
        assertEquals("Collaborative Album", deduplicated[0].name)
    }

    @Test
    fun song_artists_come_from_server_tags() {
        val song = SongDto(
            id = "s1",
            title = "Otherside (feat. Foo)",
            artist = "Main Artist feat. Foo",
            artistId = "a1",
            artists = listOf(ArtistDto(id = "a1", name = "Main Artist"), ArtistDto(id = "a2", name = "Foo")),
        ).toDomain()

        assertEquals(listOf("a1", "a2"), song.artists.map { it.id })
        assertEquals(listOf("Main Artist", "Foo"), song.artists.map { it.name })
    }

    @Test
    fun combined_artist_tag_is_not_split() {
        val song = SongDto(id = "s1", title = "Song", artist = "Simon & Garfunkel", artistId = "a1").toDomain()

        assertEquals(listOf("Simon & Garfunkel"), song.artists.map { it.name })
        assertEquals("a1", song.artists.single().id)
    }

    @Test
    fun song_without_artist_has_no_credits() {
        assertTrue(SongDto(id = "s1", title = "Song").toDomain().artists.isEmpty())
    }

    @Test
    fun appears_on_album_ids_exclude_primary_albums_and_duplicates() {
        val songs = listOf(
            Song(
                id = "s1",
                title = "Track 1",
                artist = "Main Artist",
                album = "Collaborative Album",
                albumId = "feat-1",
                artists = listOf(Artist(id = "a1", name = "Featured Artist"), Artist(id = "a2", name = "Main Artist")),
            ),
            Song(
                id = "s2",
                title = "Track 2",
                artist = "Other Artist",
                album = "Another Album",
                albumId = "feat-2",
                artists = listOf(Artist(id = "a1", name = "Featured Artist")),
            ),
            Song(
                id = "s3",
                title = "Track 3",
                artist = "Main Artist",
                album = "Own Album",
                albumId = "own-1",
                artists = listOf(Artist(id = "a2", name = "Main Artist")),
            ),
            Song(
                id = "s4",
                title = "Track 4",
                artist = "Other Artist",
                album = "Collaborative Album",
                albumId = "feat-1",
                artists = listOf(Artist(id = "a1", name = "Featured Artist"), Artist(id = "a2", name = "Main Artist")),
            ),
        )

        val albumIds = collectAppearsOnAlbumIds("Featured Artist", listOf("own-1"), songs)

        assertEquals(listOf("feat-1", "feat-2"), albumIds)
    }

    @Test
    fun selectedThrough_adds_the_range_to_the_nearest_selected_song() {
        val songs = (0..9).map { Song(id = "s$it", title = "Song $it") }
        assertEquals(setOf("s2", "s3", "s4", "s8"), songs.selectedThrough(4, setOf("s2", "s8")))
        assertEquals(setOf("s2", "s7", "s8"), songs.selectedThrough(7, setOf("s2", "s8")))
        assertEquals(setOf("s1", "s2", "s3"), songs.selectedThrough(1, setOf("s3")))
        assertEquals(setOf("s4"), songs.selectedThrough(4, emptySet()))
    }

    @Test
    fun filtered_keeps_the_matching_songs_with_their_positions() {
        val songs = listOf(
            Song(id = "a", title = "Bohemian Rhapsody", artist = "Queen", album = "A Night at the Opera"),
            Song(id = "b", title = "Under Pressure", artist = "Queen & David Bowie"),
            Song(id = "c", title = "Heroes", artist = "David Bowie", album = "Heroes"),
        )
        assertEquals(listOf(0, 1, 2), songs.filtered("  ").map { it.index })
        assertEquals(listOf(1, 2), songs.filtered("bowie").map { it.index })
        assertEquals(listOf(0), songs.filtered("opera queen").map { it.index })
        assertEquals(emptyList(), songs.filtered("abba").map { it.index })
    }

    @Test
    fun navigator_keeps_entries_and_their_scroll_positions_across_back_and_forward() {
        val navigator = Navigator(Screen.Library())
        val library = navigator.currentEntry.value
        navigator.push(Screen.Search)
        val search = navigator.currentEntry.value
        navigator.push(Screen.AlbumSongs("a"))
        navigator.pop()
        assertTrue(navigator.currentEntry.value === search)
        navigator.pop()
        assertTrue(navigator.currentEntry.value === library)
        navigator.forward()
        assertTrue(navigator.currentEntry.value === search)
        // Replacing keeps the visit, e.g. the library recording what its pane shows
        navigator.replace(Screen.Settings)
        assertEquals(search.id, navigator.currentEntry.value.id)
        assertTrue(navigator.currentEntry.value.scrollPositions === search.scrollPositions)
        assertEquals(Screen.Settings, navigator.currentScreen.value)
        // A new visit of an equal screen starts at the top
        navigator.push(Screen.Search)
        assertTrue(navigator.currentEntry.value.id != search.id)
    }

    @Test
    fun albums_sort_alphabetically_by_release_date_and_by_date_added() {
        val albums = listOf(
            Album(id = "1", name = "beta", year = 2001, dateAdded = "2024-01-02T00:00:00Z"),
            Album(id = "2", name = "Alpha", year = null, dateAdded = null),
            Album(id = "3", name = "gamma", year = 2020, dateAdded = "2023-05-01T00:00:00Z"),
        )
        assertEquals(listOf("2", "1", "3"), albums.sortedBy(LibraryOrder(LibrarySort.Alphabetical)).map { it.id })
        // Newest first, unknown dates last
        assertEquals(listOf("3", "1", "2"), albums.sortedBy(LibraryOrder(LibrarySort.ReleaseDate)).map { it.id })
        assertEquals(listOf("1", "3", "2"), albums.sortedBy(LibraryOrder(LibrarySort.DateAdded)).map { it.id })
        // Reversed: Z to A, oldest first; unknown dates still last
        assertEquals(listOf("3", "1", "2"), albums.sortedBy(LibraryOrder(LibrarySort.Alphabetical, reversed = true)).map { it.id })
        assertEquals(listOf("1", "3", "2"), albums.sortedBy(LibraryOrder(LibrarySort.ReleaseDate, reversed = true)).map { it.id })
        assertEquals(listOf("3", "1", "2"), albums.sortedBy(LibraryOrder(LibrarySort.DateAdded, reversed = true)).map { it.id })
    }

    @Test
    fun songs_sorted_by_release_date_keep_their_track_order() {
        val songs = listOf(
            Song(id = "b2", title = "Z", album = "B", track = 2, year = 2010),
            Song(id = "a1", title = "Y", album = "A", track = 1, year = 2000),
            Song(id = "b1", title = "X", album = "B", track = 1, year = 2010),
        )
        assertEquals(listOf("b1", "b2", "a1"), songs.sortedBy(LibraryOrder(LibrarySort.ReleaseDate)).map { it.id })
        assertEquals(listOf("a1", "b1", "b2"), songs.sortedBy(LibraryOrder(LibrarySort.ReleaseDate, reversed = true)).map { it.id })
        assertEquals(listOf("b1", "a1", "b2"), songs.sortedBy(LibraryOrder(LibrarySort.Alphabetical)).map { it.id })
    }

    @Test
    fun library_view_settings_survive_storing_them() {
        val view = LibraryViewSettings(LibraryCategory.Albums, mapOf(LibraryCategory.Albums to LibraryOrder(LibrarySort.DateAdded, reversed = true)))
        assertEquals(view, decodeLibraryView(view.encode()))
        assertEquals(LibraryViewSettings(), decodeLibraryView(null))
        assertEquals(LibraryViewSettings(), decodeLibraryView("not json"))
        assertEquals(LibraryOrder(), view.orderOf(LibraryCategory.Songs))
    }

    @Test
    fun touch_selection_drags_select_and_unselect_ranges() {
        val selection = TouchSelection<Song>()
        val songs = (0..5).map { Song(id = "s$it", title = "Song $it") }
        selection.items = songs
        selection.id = { it.id }
        selection.onChange = { selection.selectedIds = it }
        selection.selectedIds = setOf("s0")
        selection.startDrag(2)
        selection.dragTo(4)
        assertEquals(setOf("s0", "s2", "s3", "s4"), selection.selectedIds)
        // Moving back shrinks the range again
        selection.dragTo(3)
        assertEquals(setOf("s0", "s2", "s3"), selection.selectedIds)
        // Starting on a selected song unselects
        selection.startDrag(3)
        selection.dragTo(2)
        assertEquals(setOf("s0"), selection.selectedIds)
    }
}
