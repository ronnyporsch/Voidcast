using System.Collections.Generic;
using System.Linq;
using Jellyfin.Plugin.Voidcast.Api;
using Jellyfin.Plugin.Voidcast.Services;
using Xunit;

namespace Jellyfin.Plugin.Voidcast.Tests;

public class NewReleaseMatchingTests
{
    private static readonly OwnedArtist Artist = new(
        "A",
        [NewReleaseMatching.NormalizedTitle("Owned")],
        [NewReleaseMatching.NormalizedTitle("Hit")],
        2);

    private static IEnumerable<LibrarySong> Songs(int count, params string[] artists) =>
        Enumerable.Range(0, count).Select(index => new LibrarySong($"Song {index}", "Album", artists));

    private static NewReleaseDto Release(string title, int? year = 2026, string? type = "Album", string artist = "A", string? date = null) =>
        new() { Id = $"{artist}/{title}/{type}", Title = title, Year = year, ReleaseType = type, Artist = artist, ReleaseDate = date };

    private static List<string> New(params NewReleaseDto[] releases) =>
        releases.Where(release => NewReleaseMatching.IsNew(release, Artist, 2025)).Select(release => release.Title).ToList();

    [Fact]
    public void CreditsOnSharedSongsAndDifferentCasesAddUp()
    {
        var artists = NewReleaseMatching.ByArtist(Songs(10, "Main", "Guest").Concat(Songs(5, "GUEST")).Concat(Songs(5, "main")));
        Assert.Equal(15, artists["main"].SongCount);
        Assert.Equal(15, artists["guest"].SongCount);
    }

    [Fact]
    public void ArtistsCreditedTwiceOnASongCountOnce() =>
        Assert.Equal(10, NewReleaseMatching.ByArtist(Songs(10, "Twin", "twin"))["Twin"].SongCount);

    [Fact]
    public void BlankCreditsAreNoArtists() =>
        Assert.Empty(NewReleaseMatching.ByArtist(Songs(1, " ")));

    [Fact]
    public void ReleasesOfTheLibraryAreLeftOut() =>
        Assert.Equal(["Fresh"], New(Release("Owned (Deluxe Edition)"), Release("Owned - Single", type: "Single"), Release("Fresh")));

    [Fact]
    public void SinglesOfOwnedSongsAreLeftOutButAlbumsOfThatNameAreNot()
    {
        var releases = new[] { Release("Hit", type: "Single"), Release("Hit", type: "EP"), Release("Hit") };
        Assert.Equal(["Album"], releases.Where(release => NewReleaseMatching.IsNew(release, Artist, 2025)).Select(release => release.ReleaseType));
    }

    [Fact]
    public void OldReleasesAndOnesWithoutAYearAreLeftOut() =>
        Assert.Equal(["Last year"], New(Release("Old", year: 2024), Release("Last year", year: 2025), Release("Unknown", year: null)));

    [Fact]
    public void EverythingRecentIsNewForArtistsWithoutSongs() =>
        Assert.True(NewReleaseMatching.IsNew(Release("Owned"), null, 2025));

    [Fact]
    public void NewestComeFirstWithAlbumsBeforeSinglesAndOneEditionEach()
    {
        var ordered = NewReleaseMatching.InNewReleasesOrder([Release("Single", type: "Single"), Release("Older", year: 2025), Release("Record (Deluxe)"), Release("Record")]);
        Assert.Equal(["Record", "Single", "Older"], ordered.Select(release => release.Title));
    }

    [Fact]
    public void WithinAYearTheLatestDateComesFirstAndUnknownDatesLast()
    {
        var ordered = NewReleaseMatching.InNewReleasesOrder(
        [
            Release("Unknown"),
            Release("March", type: "Single", date: "2026-03-14"),
            Release("January", date: "2026-01-02"),
            Release("October", artist: "B", type: "EP", date: "2026-10-30"),
            Release("Older", year: 2025, date: "2025-12-31"),
        ]);
        Assert.Equal(["October", "March", "January", "Unknown", "Older"], ordered.Select(release => release.Title));
    }

    [Fact]
    public void TitlesConsistingOfQualifiersKeepThem() =>
        Assert.Equal("untitled", NewReleaseMatching.NormalizedTitle("(Untitled)"));
}
