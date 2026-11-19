using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using Jellyfin.Plugin.Voidcast.Api;

namespace Jellyfin.Plugin.Voidcast.Services;

/// <summary>
/// A song as the listening history knows it. <see cref="Id"/> is null for songs that are no longer in the library, of
/// which only what Playback Reporting noted down is known (no artists to credit, album or genres).
/// </summary>
public sealed record WrappedTrack(
    string Key,
    string? Id,
    string Title,
    string Artist,
    IReadOnlyList<string> Artists,
    string? AlbumId,
    string? Album,
    string? AlbumArtist,
    IReadOnlyList<string> Genres,
    double? RuntimeSeconds);

/// <summary>A row of Playback Reporting's history: <paramref name="Seconds"/> of <paramref name="Track"/> listened to from <paramref name="StartedUtc"/>.</summary>
public sealed record WrappedPlay(DateTime StartedUtc, WrappedTrack Track, int Seconds);

/// <summary>Turns a user's listening history into the stats of <see cref="WrappedDto"/>.</summary>
public static class WrappedCalculator
{
    /// <summary>How long a song has to be listened to for a play to count (shorter songs: half of them).</summary>
    public const int MinPlaySeconds = 30;

    public const int TopCount = 5;

    /// <summary>
    /// The stats of the plays from <paramref name="fromUtc"/> (inclusive) to <paramref name="toUtc"/> (exclusive). Days
    /// and hours are those of the user's time zone, <paramref name="utcOffset"/> ahead of UTC. <paramref name="plays"/> is
    /// the whole history: discoveries are what got played for the first time ever in the period.
    /// </summary>
    public static WrappedDto Compute(IReadOnlyCollection<WrappedPlay> plays, DateTime fromUtc, DateTime toUtc, TimeSpan utcOffset)
    {
        var inRange = plays.Where(play => play.StartedUtc >= fromUtc && play.StartedUtc < toUtc).ToList();
        var counted = inRange.Where(Counts).ToList();
        DateOnly LocalDay(WrappedPlay play) => DateOnly.FromDateTime(play.StartedUtc + utcOffset);

        var result = new WrappedDto
        {
            TotalSeconds = inRange.Sum(play => (long)Seconds(play)),
            PlayCount = counted.Count,
            SongCount = counted.Select(play => play.Track.Key).Distinct().Count(),
            ArtistCount = counted.SelectMany(play => ArtistsOf(play.Track)).Distinct(StringComparer.OrdinalIgnoreCase).Count(),
            AlbumCount = counted.Where(play => play.Track.AlbumId != null).Select(play => play.Track.AlbumId).Distinct().Count(),
            TopSongs = counted.GroupBy(play => play.Track.Key)
                .Select(group => SongOf(group.First().Track, group))
                .OrderByDescending(song => song.Plays).ThenByDescending(song => song.Seconds).ThenBy(song => song.Name, StringComparer.OrdinalIgnoreCase)
                .Take(TopCount).ToList(),
            TopArtists = RankArtists(counted).Take(TopCount).ToList(),
            TopAlbums = counted.Where(play => play.Track.AlbumId != null)
                .GroupBy(play => play.Track.AlbumId!)
                .Select(group => new WrappedAlbumDto
                {
                    Id = group.Key,
                    Name = group.First().Track.Album ?? string.Empty,
                    Artist = group.First().Track.AlbumArtist ?? group.First().Track.Artist,
                    Plays = group.Count(),
                    Seconds = group.Sum(play => (long)Seconds(play)),
                })
                .OrderByDescending(album => album.Plays).ThenByDescending(album => album.Seconds).ThenBy(album => album.Name, StringComparer.OrdinalIgnoreCase)
                .Take(TopCount).ToList(),
            TopGenres = counted
                .SelectMany(play => play.Track.Genres.Distinct(StringComparer.OrdinalIgnoreCase).Select(genre => (Genre: genre, Play: play)))
                .GroupBy(credit => credit.Genre, StringComparer.OrdinalIgnoreCase)
                .Select(group => new WrappedGenreDto { Name = group.First().Genre, Plays = group.Count() })
                .OrderByDescending(genre => genre.Plays).ThenBy(genre => genre.Name, StringComparer.OrdinalIgnoreCase)
                .Take(TopCount).ToList(),
        };

        foreach (var play in counted)
        {
            var local = play.StartedUtc + utcOffset;
            result.PlaysByHour[local.Hour]++;
            // Monday first
            result.PlaysByWeekday[((int)local.DayOfWeek + 6) % 7]++;
        }

        result.BusiestDay = inRange.GroupBy(LocalDay)
            .Select(day => new WrappedDayDto { Date = Format(day.Key), Seconds = day.Sum(play => (long)Seconds(play)), Plays = day.Count(Counts) })
            .Where(day => day.Plays > 0)
            .OrderByDescending(day => day.Seconds).ThenBy(day => day.Date, StringComparer.Ordinal)
            .FirstOrDefault();
        result.LongestStreak = LongestStreak(counted.Select(LocalDay).Distinct().Order().ToList());

        // Discoveries: first played (for long enough to count) in the period
        var firstPlays = plays.Where(Counts).OrderBy(play => play.StartedUtc).ToList();
        var firstSongPlay = new Dictionary<string, DateTime>();
        var firstArtistPlay = new Dictionary<string, DateTime>(StringComparer.OrdinalIgnoreCase);
        foreach (var play in firstPlays)
        {
            firstSongPlay.TryAdd(play.Track.Key, play.StartedUtc);
            foreach (var artist in ArtistsOf(play.Track))
            {
                firstArtistPlay.TryAdd(artist, play.StartedUtc);
            }
        }

        bool IsNew(DateTime first) => first >= fromUtc;
        result.NewSongCount = firstSongPlay.Values.Count(first => IsNew(first) && first < toUtc);
        var newArtists = firstArtistPlay.Where(entry => IsNew(entry.Value) && entry.Value < toUtc).Select(entry => entry.Key)
            .ToHashSet(StringComparer.OrdinalIgnoreCase);
        result.NewArtistCount = newArtists.Count;
        result.TopNewArtist = RankArtists(counted).FirstOrDefault(artist => newArtists.Contains(artist.Name));

        result.OnRepeat = counted.GroupBy(play => (play.Track.Key, Day: LocalDay(play)))
            .Where(group => group.Count() > 1)
            .OrderByDescending(group => group.Count()).ThenBy(group => group.Key.Day)
            .Select(group => new WrappedRepeatDto { Song = SongOf(group.First().Track, group), Date = Format(group.Key.Day), Plays = group.Count() })
            .FirstOrDefault();

        result.MostSkipped = inRange.Where(IsSkip)
            .GroupBy(play => play.Track.Key)
            .Where(group => group.Count() > 1)
            .OrderByDescending(group => group.Count()).ThenBy(group => group.First().Track.Title, StringComparer.OrdinalIgnoreCase)
            .Select(group => new WrappedSkipDto { Song = SongOf(group.First().Track, []), Skips = group.Count() })
            .FirstOrDefault();

        return result;
    }

    /// <summary>The time listened, at most the song's length: Playback Reporting may count paused time as well.</summary>
    public static int Seconds(WrappedPlay play)
    {
        var seconds = Math.Max(0, play.Seconds);
        return play.Track.RuntimeSeconds is > 0 and var runtime ? Math.Min(seconds, (int)Math.Ceiling(runtime)) : seconds;
    }

    /// <summary>Whether the song was listened to long enough for a play to count.</summary>
    public static bool Counts(WrappedPlay play)
    {
        var needed = play.Track.RuntimeSeconds is > 0 and var runtime ? Math.Min(MinPlaySeconds, runtime / 2) : MinPlaySeconds;
        return Seconds(play) >= needed;
    }

    /// <summary>Whether the song got stopped before half of it played. Only known for songs of the library.</summary>
    public static bool IsSkip(WrappedPlay play) =>
        play.Track.RuntimeSeconds is > 0 and var runtime && Seconds(play) < runtime / 2;

    private static IEnumerable<string> ArtistsOf(WrappedTrack track) =>
        track.Artists.Where(artist => !string.IsNullOrWhiteSpace(artist)).Distinct(StringComparer.OrdinalIgnoreCase);

    private static IEnumerable<WrappedArtistDto> RankArtists(IEnumerable<WrappedPlay> counted) =>
        counted.SelectMany(play => ArtistsOf(play.Track).Select(artist => (Artist: artist, Play: play)))
            .GroupBy(credit => credit.Artist, StringComparer.OrdinalIgnoreCase)
            .Select(group => new WrappedArtistDto
            {
                Name = group.First().Artist,
                Plays = group.Count(),
                Seconds = group.Sum(credit => (long)Seconds(credit.Play)),
            })
            .OrderByDescending(artist => artist.Plays).ThenByDescending(artist => artist.Seconds).ThenBy(artist => artist.Name, StringComparer.OrdinalIgnoreCase);

    private static WrappedSongDto SongOf(WrappedTrack track, IEnumerable<WrappedPlay> plays)
    {
        var list = plays as IReadOnlyCollection<WrappedPlay> ?? plays.ToList();
        return new WrappedSongDto
        {
            Id = track.Id,
            Name = track.Title,
            Artist = track.Artist,
            Album = track.Album,
            AlbumId = track.AlbumId,
            Plays = list.Count,
            Seconds = list.Sum(play => (long)Seconds(play)),
        };
    }

    private static WrappedStreakDto? LongestStreak(List<DateOnly> days)
    {
        WrappedStreakDto? best = null;
        var start = 0;
        for (var i = 0; i < days.Count; i++)
        {
            if (i > 0 && days[i].DayNumber != days[i - 1].DayNumber + 1)
            {
                start = i;
            }

            var length = i - start + 1;
            if (best == null || length > best.Days)
            {
                best = new WrappedStreakDto { Days = length, Start = Format(days[start]), End = Format(days[i]) };
            }
        }

        return best;
    }

    private static string Format(DateOnly day) => day.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
}
