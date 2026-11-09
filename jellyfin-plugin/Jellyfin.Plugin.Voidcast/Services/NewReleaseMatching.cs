using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.RegularExpressions;
using Jellyfin.Plugin.Voidcast.Api;

namespace Jellyfin.Plugin.Voidcast.Services;

/// <summary>A song of the library, as far as finding new releases cares.</summary>
public record LibrarySong(string Title, string? Album, IReadOnlyList<string> Artists);

/// <summary>A library artist; <see cref="Albums"/> and <see cref="Songs"/> are normalized titles, see <see cref="NewReleaseMatching.NormalizedTitle"/>.</summary>
public record OwnedArtist(string Name, HashSet<string> Albums, HashSet<string> Songs, int SongCount);

/// <summary>Decides which YouTube Music releases are new to a library.</summary>
public static partial class NewReleaseMatching
{
    /// <summary>The artists credited on <paramref name="songs"/> by their name, which is told apart ignoring case.</summary>
    public static Dictionary<string, OwnedArtist> ByArtist(IEnumerable<LibrarySong> songs)
    {
        var artists = new Dictionary<string, OwnedArtist>(StringComparer.OrdinalIgnoreCase);
        foreach (var song in songs)
        {
            foreach (var name in song.Artists.Where(name => !string.IsNullOrWhiteSpace(name)).Distinct(StringComparer.OrdinalIgnoreCase))
            {
                var artist = artists.GetValueOrDefault(name) ?? new OwnedArtist(name, [], [], 0);
                if (!string.IsNullOrWhiteSpace(song.Album))
                {
                    artist.Albums.Add(NormalizedTitle(song.Album));
                }

                artist.Songs.Add(NormalizedTitle(song.Title));
                artists[name] = artist with { SongCount = artist.SongCount + 1 };
            }
        }

        return artists;
    }

    /// <summary>
    /// Whether <paramref name="release"/> came out from <paramref name="sinceYear"/> on and the library doesn't have
    /// it. A single or EP also counts as owned if the library has a song named like it, e.g. from the album that
    /// followed it. <paramref name="artist"/> is null if the library has no songs of the artist.
    /// </summary>
    public static bool IsNew(YouTubeAlbumSummaryDto release, OwnedArtist? artist, int sinceYear)
    {
        if ((release.Year ?? 0) < sinceYear)
        {
            return false;
        }

        var title = NormalizedTitle(release.Title);
        return artist == null || (!artist.Albums.Contains(title) && (IsAlbum(release) || !artist.Songs.Contains(title)));
    }

    /// <summary>
    /// Newest first, those without a known date last in their year, otherwise albums before singles; of several
    /// editions of a release only the first stays.
    /// </summary>
    public static List<NewReleaseDto> InNewReleasesOrder(IEnumerable<NewReleaseDto> releases) =>
        releases
            .OrderByDescending(release => release.Year ?? 0)
            // yyyy-MM-dd sorts like the dates, null lowest
            .ThenByDescending(release => release.ReleaseDate, StringComparer.Ordinal)
            .ThenBy(release => IsAlbum(release) ? 0 : 1)
            .ThenBy(release => release.Artist, StringComparer.OrdinalIgnoreCase)
            .ThenBy(release => release.Title, StringComparer.OrdinalIgnoreCase)
            .DistinctBy(release => release.Id)
            .DistinctBy(release => (release.Artist.ToLowerInvariant(), NormalizedTitle(release.Title)))
            .ToList();

    /// <summary>For comparing the titles of different sources: without case, punctuation and qualifiers like "(Deluxe Edition)".</summary>
    public static string NormalizedTitle(string title)
    {
        var lower = title.ToLowerInvariant();
        var withoutQualifiers = Letters(TitleQualifiersRegex().Replace(lower, string.Empty));
        return withoutQualifiers.Length > 0 ? withoutQualifiers : Letters(lower);
    }

    private static bool IsAlbum(YouTubeAlbumSummaryDto release) => release.ReleaseType is null or "Album";

    private static string Letters(string text) => string.Concat(text.Where(char.IsLetterOrDigit));

    [GeneratedRegex(@"[(\[].*?[)\]]|\s-\s(single|ep)$")]
    private static partial Regex TitleQualifiersRegex();
}
