using System;
using System.Collections.Generic;
using System.Linq;
using Jellyfin.Plugin.Voidcast.Api;

namespace Jellyfin.Plugin.Voidcast.Services;

internal static class TrackTagsBuilder
{
    private static readonly HashSet<string> ReleaseTypes = ["album", "single", "ep"];

    /// <summary>Builds the tags of <paramref name="song"/> from its search result and, if found, its album.</summary>
    public static TrackTags Build(YouTubeSongDto song, YouTubeAlbum? album)
    {
        var artists = song.Artists.Select(artist => artist.Name).ToList();
        if (artists.Count == 0 && !string.IsNullOrWhiteSpace(song.Artist))
        {
            artists.Add(song.Artist);
        }

        var tags = new TrackTags
        {
            Title = song.Title,
            Artist = song.Artist,
            Artists = artists,
            Genre = string.IsNullOrWhiteSpace(song.Genre) ? null : song.Genre.Trim(),
            Comment = YouTubeMusicClient.WatchUrl(song.VideoId),
        };
        if (album == null)
        {
            // Without album details the track artists are the best guess for the album artists
            if (!string.IsNullOrWhiteSpace(song.Album))
            {
                tags.Album = song.Album;
                tags.AlbumArtist = string.IsNullOrWhiteSpace(song.Artist) ? null : song.Artist;
                tags.AlbumArtists = artists;
            }

            return tags;
        }

        var track = album.Tracks.FirstOrDefault(t => t.VideoId == song.VideoId)
            // The album may list another video (e.g. the official audio) of the same track
            ?? SingleOrNull(album.Tracks.Where(t => string.Equals(t.Title, song.Title, StringComparison.OrdinalIgnoreCase)));
        var albumArtists = album.Artists.Select(artist => artist.Name).ToList();
        if (albumArtists.Count == 0)
        {
            albumArtists = artists;
        }

        // The client may have renamed the album
        tags.Album = string.IsNullOrWhiteSpace(song.Album) ? album.Title : song.Album;
        tags.AlbumArtist = string.IsNullOrWhiteSpace(album.Artist) ? string.Join(", ", albumArtists) : album.Artist;
        tags.AlbumArtists = albumArtists;
        tags.TrackNumber = track?.TrackNumber;
        tags.TotalTracks = album.TrackCount;
        tags.Year = album.Year;
        var releaseType = album.ReleaseType?.ToLowerInvariant();
        tags.ReleaseType = releaseType != null && ReleaseTypes.Contains(releaseType) ? releaseType : null;
        tags.Media = "Digital Media";
        return tags;
    }

    private static T? SingleOrNull<T>(IEnumerable<T> items)
        where T : class
    {
        var list = items.Take(2).ToList();
        return list.Count == 1 ? list[0] : null;
    }
}
