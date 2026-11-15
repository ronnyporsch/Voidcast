using System.Collections.Generic;
using Jellyfin.Plugin.Voidcast.Api;
using Jellyfin.Plugin.Voidcast.Services;
using Xunit;

namespace Jellyfin.Plugin.Voidcast.Tests;

public class TrackTagsBuilderTests
{
    private static readonly YouTubeAlbum Album = new(
        "Album",
        "Main & Guest",
        [new YouTubeArtistDto { Name = "Main" }, new YouTubeArtistDto { Name = "Guest" }],
        "Album",
        2026,
        1,
        null,
        [new YouTubeAlbumTrack("abcdefghijk", "Song", 1, string.Empty, [], 0, null, false)]);

    private static YouTubeSongDto Song(string album = "Album", List<string>? albumArtists = null) => new()
    {
        VideoId = "abcdefghijk",
        Title = "Song",
        Artist = "Main",
        Artists = [new YouTubeArtistDto { Name = "Main" }],
        Album = album,
        AlbumArtists = albumArtists,
    };

    [Fact]
    public void AlbumArtistsComeFromTheAlbum()
    {
        var tags = TrackTagsBuilder.Build(Song(), Album);
        Assert.Equal("Main & Guest", tags.AlbumArtist);
        Assert.Equal(["Main", "Guest"], tags.AlbumArtists);
    }

    [Fact]
    public void RequestedAlbumTitleAndArtistsReplaceTheAlbums()
    {
        var tags = TrackTagsBuilder.Build(Song("Renamed", [" Various Artists ", "", "Other", "Other"]), Album);
        Assert.Equal("Renamed", tags.Album);
        Assert.Equal("Various Artists, Other", tags.AlbumArtist);
        Assert.Equal(["Various Artists", "Other"], tags.AlbumArtists);
        Assert.Equal(1, tags.TrackNumber);
    }

    [Fact]
    public void RequestedAlbumArtistsReplaceTheTrackArtistsWithoutAlbumDetails()
    {
        var tags = TrackTagsBuilder.Build(Song(albumArtists: ["Various Artists"]), null);
        Assert.Equal("Various Artists", tags.AlbumArtist);
        Assert.Equal(["Various Artists"], tags.AlbumArtists);
    }

    [Fact]
    public void TracksWithoutAlbumGetNoAlbumArtists()
    {
        var tags = TrackTagsBuilder.Build(Song(string.Empty, ["Various Artists"]), null);
        Assert.Null(tags.Album);
        Assert.Null(tags.AlbumArtist);
        Assert.Empty(tags.AlbumArtists);
    }
}
