using System;
using System.Collections.Generic;
using System.Linq;
using Jellyfin.Plugin.Voidcast.Services;
using Xunit;

namespace Jellyfin.Plugin.Voidcast.Tests;

public class WrappedCalculatorTests
{
    private static readonly DateTime From = new(2026, 1, 1, 0, 0, 0, DateTimeKind.Utc);
    private static readonly DateTime To = new(2027, 1, 1, 0, 0, 0, DateTimeKind.Utc);

    private static WrappedTrack Track(string title, string artist = "A", string? album = "Album", double runtime = 200, params string[] genres) =>
        new(title, title, title, artist, [artist], album, album, artist, genres, runtime);

    private static WrappedPlay Play(WrappedTrack track, DateTime started, int seconds = 200) => new(started, track, seconds);

    private static DateTime Day(int month, int day, int hour = 12) => new(2026, month, day, hour, 0, 0, DateTimeKind.Utc);

    private static Api.WrappedDto Compute(IReadOnlyCollection<WrappedPlay> plays, int offsetHours = 0) =>
        WrappedCalculator.Compute(plays, From, To, TimeSpan.FromHours(offsetHours));

    [Fact]
    public void OnlyPlaysInThePeriodCount()
    {
        var song = Track("S");
        var result = Compute([Play(song, From.AddDays(-1)), Play(song, Day(3, 1)), Play(song, To)]);
        Assert.Equal(1, result.PlayCount);
        Assert.Equal(200, result.TotalSeconds);
    }

    [Fact]
    public void ShortPlaysAddTimeButAreNoPlays()
    {
        var song = Track("S");
        var result = Compute([Play(song, Day(3, 1), 29), Play(song, Day(3, 2), 30)]);
        Assert.Equal(1, result.PlayCount);
        Assert.Equal(59, result.TotalSeconds);
    }

    [Fact]
    public void ShortSongsCountFromHalfOfThem()
    {
        var song = Track("Interlude", runtime: 20);
        Assert.Equal(1, Compute([Play(song, Day(3, 1), 10)]).PlayCount);
    }

    [Fact]
    public void TimeIsAtMostTheSongsLength()
    {
        var result = Compute([Play(Track("S", runtime: 100), Day(3, 1), 5000)]);
        Assert.Equal(100, result.TotalSeconds);
    }

    [Fact]
    public void RankingsGoByPlays()
    {
        var a = Track("A1", "A", "X", 200, "Rock");
        var b = Track("B1", "B", "Y", 200, "Pop");
        var plays = new List<WrappedPlay> { Play(a, Day(3, 1)), Play(b, Day(3, 2)), Play(b, Day(3, 3)) };
        var result = Compute(plays);
        Assert.Equal(["B1", "A1"], result.TopSongs.Select(song => song.Name));
        Assert.Equal(["B", "A"], result.TopArtists.Select(artist => artist.Name));
        Assert.Equal(["Y", "X"], result.TopAlbums.Select(album => album.Name));
        Assert.Equal(["Pop", "Rock"], result.TopGenres.Select(genre => genre.Name));
        Assert.Equal(2, result.TopArtists[0].Plays);
        Assert.Equal(2, result.ArtistCount);
    }

    [Fact]
    public void AllArtistsOfASongAreCredited()
    {
        var duet = new WrappedTrack("D", "D", "D", "A, B", ["A", "B"], null, null, null, [], 200);
        var result = Compute([Play(duet, Day(3, 1))]);
        Assert.Equal(2, result.ArtistCount);
        Assert.All(result.TopArtists, artist => Assert.Equal(1, artist.Plays));
    }

    [Fact]
    public void HoursAndDaysAreThoseOfTheUsersTimeZone()
    {
        // Wednesday 23:00 UTC is Thursday 01:00 two hours ahead
        var result = Compute([Play(Track("S"), new DateTime(2026, 3, 4, 23, 0, 0, DateTimeKind.Utc))], offsetHours: 2);
        Assert.Equal(1, result.PlaysByHour[1]);
        Assert.Equal(1, result.PlaysByWeekday[3]);
        Assert.Equal("2026-03-05", result.BusiestDay!.Date);
    }

    [Fact]
    public void StreakIsTheMostDaysInARow()
    {
        var song = Track("S");
        var days = new[] { Day(3, 1), Day(3, 2), Day(3, 5), Day(3, 6), Day(3, 7), Day(3, 7, 18), Day(3, 9) };
        var streak = Compute(days.Select(day => Play(song, day)).ToList()).LongestStreak!;
        Assert.Equal(3, streak.Days);
        Assert.Equal("2026-03-05", streak.Start);
        Assert.Equal("2026-03-07", streak.End);
    }

    [Fact]
    public void DiscoveriesAreFirstPlayedInThePeriod()
    {
        var old = Track("Old", "Known");
        var newSong = Track("New", "Known");
        var newArtist = Track("Fresh", "Newcomer");
        var plays = new List<WrappedPlay>
        {
            Play(old, From.AddDays(-10)),
            Play(old, Day(3, 1)),
            Play(newSong, Day(3, 1)),
            Play(newArtist, Day(3, 2)),
            Play(newArtist, Day(3, 3)),
            // A play too short doesn't make it known
            Play(Track("Glimpse", "Newcomer"), From.AddDays(-5), 5),
        };
        var result = Compute(plays);
        Assert.Equal(2, result.NewSongCount);
        Assert.Equal(1, result.NewArtistCount);
        Assert.Equal("Newcomer", result.TopNewArtist!.Name);
    }

    [Fact]
    public void OnRepeatIsTheMostPlaysOfASongOnOneDay()
    {
        var a = Track("A");
        var b = Track("B");
        var plays = new List<WrappedPlay>
        {
            Play(a, Day(3, 1, 10)), Play(a, Day(3, 2, 10)), Play(a, Day(3, 3, 10)),
            Play(b, Day(3, 4, 10)), Play(b, Day(3, 4, 11)),
        };
        var repeat = Compute(plays).OnRepeat!;
        Assert.Equal("B", repeat.Song.Name);
        Assert.Equal(2, repeat.Plays);
        Assert.Equal("2026-03-04", repeat.Date);
    }

    [Fact]
    public void NoRepeatWithoutASongPlayedTwiceADay() =>
        Assert.Null(Compute([Play(Track("A"), Day(3, 1)), Play(Track("A"), Day(3, 2))]).OnRepeat);

    [Fact]
    public void SkipsAreSongsStoppedBeforeHalfOfThem()
    {
        var song = Track("S", runtime: 200);
        var result = Compute([Play(song, Day(3, 1), 40), Play(song, Day(3, 2), 99), Play(song, Day(3, 3), 100)]);
        Assert.Equal(2, result.MostSkipped!.Skips);
    }

    [Fact]
    public void NoStatsWithoutPlays()
    {
        var result = Compute([]);
        Assert.Equal(0, result.PlayCount);
        Assert.Null(result.BusiestDay);
        Assert.Null(result.LongestStreak);
        Assert.Null(result.TopNewArtist);
        Assert.Empty(result.TopSongs);
    }

    [Theory]
    [InlineData("Artist - Title (Album)", "Artist", "Title", "Album")]
    [InlineData("A, B - Song - Remix (Album)", "A, B", "Song - Remix", "Album")]
    [InlineData("Not Known - Title (Not Known)", "", "Title", null)]
    [InlineData("Just a name", "", "Just a name", null)]
    public void DeletedSongsAreReadFromTheirName(string itemName, string artist, string title, string? album)
    {
        var track = WrappedService.FromName("id", itemName);
        Assert.Equal(artist, track.Artist);
        Assert.Equal(title, track.Title);
        Assert.Equal(album, track.Album);
        Assert.Null(track.Id);
    }

    private static readonly Dictionary<string, string[]> ArtistGenres = new()
    {
        ["Main"] = ["pop"],
        ["Featured"] = ["hip hop", "Pop"],
        ["Various Artists"] = ["compilation"],
    };

    private static IReadOnlyList<string> GenresOf(string[] song, string[] album, params string[] trackArtists) =>
        WrappedService.GenresOf(song, album, trackArtists, name => ArtistGenres.GetValueOrDefault(name, []));

    [Fact]
    public void SongGenresComeFirst() =>
        Assert.Equal(["rock"], GenresOf(["rock"], ["jazz"], "Main"));

    [Fact]
    public void AlbumGenresStandInForMissingSongGenres() =>
        Assert.Equal(["jazz"], GenresOf([], ["jazz"], "Main"));

    [Fact]
    public void TrackArtistsGenresStandInForMissingSongAndAlbumGenres() =>
        Assert.Equal(["pop", "hip hop"], GenresOf([], [], "Main", "Featured"));

    [Fact]
    public void NoGenresWithoutAnyFromSongAlbumOrTrackArtists() =>
        Assert.Empty(GenresOf([], [], "Unknown"));

    [Fact]
    public void LocalDatesAreReadAsLocalTime()
    {
        Assert.True(WrappedService.TryParseDate("2026-03-01 12:00:00.1234567", out var utc));
        Assert.Equal(DateTimeKind.Utc, utc.Kind);
        Assert.Equal(new DateTime(2026, 3, 1, 12, 0, 0, DateTimeKind.Local).ToUniversalTime(), utc.AddTicks(-1234567));
    }
}
