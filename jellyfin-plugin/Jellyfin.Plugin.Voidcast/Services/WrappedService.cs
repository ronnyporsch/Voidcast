using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using Jellyfin.Data.Enums;
using Jellyfin.Plugin.Voidcast.Api;
using MediaBrowser.Common.Configuration;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Entities.Audio;
using MediaBrowser.Controller.Library;
using Microsoft.Data.Sqlite;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Voidcast.Services;

/// <summary>
/// A user's listening stats ("Wrapped") from the history the Playback Reporting plugin keeps in its database. It
/// notes a row per play (once it ran for 20 s): when it started in the server's local time, the user and item as
/// Guid.ToString("N"), the item's type and a name like "Artist - Title (Album)", and how many seconds it played.
/// </summary>
public class WrappedService
{
    private readonly ILibraryManager _libraryManager;
    private readonly ILogger<WrappedService> _logger;
    private readonly string _databasePath;

    public WrappedService(ILibraryManager libraryManager, IApplicationPaths applicationPaths, ILogger<WrappedService> logger)
    {
        _libraryManager = libraryManager;
        _logger = logger;
        _databasePath = Path.Combine(applicationPaths.DataPath, "playback_reporting.db");
    }

    /// <summary>Whether the Playback Reporting plugin keeps a history on this server.</summary>
    public bool IsAvailable => File.Exists(_databasePath);

    /// <summary>The stats of <paramref name="userId"/>'s plays between the two times, see <see cref="WrappedCalculator.Compute"/>.</summary>
    /// <exception cref="InvalidOperationException">There is no Playback Reporting history.</exception>
    public WrappedDto Compute(Guid userId, DateTime fromUtc, DateTime toUtc, TimeSpan utcOffset)
    {
        if (!IsAvailable)
        {
            throw new InvalidOperationException("The server has no listening history. Install the Playback Reporting plugin on the Jellyfin server.");
        }

        var tracks = new Dictionary<string, WrappedTrack>();
        var artistGenres = new Dictionary<string, IReadOnlyList<string>>(StringComparer.OrdinalIgnoreCase);
        var plays = ReadPlays(userId, toUtc)
            .Select(row => new WrappedPlay(row.StartedUtc, TrackOf(row.ItemId, row.ItemName, tracks, artistGenres), row.Seconds))
            .ToList();
        var result = WrappedCalculator.Compute(plays, fromUtc, toUtc, utcOffset);

        foreach (var artist in result.TopArtists.Append(result.TopNewArtist))
        {
            if (artist != null)
            {
                artist.Id = FindArtistId(artist.Name);
            }
        }

        return result;
    }

    private List<(DateTime StartedUtc, string ItemId, string ItemName, int Seconds)> ReadPlays(Guid userId, DateTime toUtc)
    {
        var rows = new List<(DateTime, string, string, int)>();
        var connectionString = new SqliteConnectionStringBuilder
        {
            DataSource = _databasePath,
            Mode = SqliteOpenMode.ReadOnly,
            // Not kept open: the database belongs to Playback Reporting
            Pooling = false,
            DefaultTimeout = 10,
        }.ToString();
        using var connection = new SqliteConnection(connectionString);
        connection.Open();
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT DateCreated, ItemId, ItemName, PlayDuration FROM PlaybackActivity WHERE UserId = @userId AND ItemType = 'Audio'";
        command.Parameters.AddWithValue("@userId", userId.ToString("N"));
        using var reader = command.ExecuteReader();
        var unreadable = 0;
        while (reader.Read())
        {
            if (reader.IsDBNull(0) || reader.IsDBNull(1) || !TryParseDate(reader.GetValue(0), out var started))
            {
                unreadable++;
                continue;
            }

            if (started >= toUtc)
            {
                continue;
            }

            var itemName = reader.IsDBNull(2) ? string.Empty : reader.GetString(2);
            var seconds = reader.IsDBNull(3) ? 0 : (int)Math.Clamp(reader.GetInt64(3), 0, int.MaxValue);
            rows.Add((started, reader.GetString(1), itemName, seconds));
        }

        if (unreadable > 0)
        {
            _logger.LogWarning("Skipped {Count} rows of the Playback Reporting history without a readable date or item", unreadable);
        }

        return rows;
    }

    /// <summary>Playback Reporting writes the server's local time, without a time zone; UTC times would end in Z.</summary>
    public static bool TryParseDate(object value, out DateTime utc)
    {
        if (value is string text)
        {
            return DateTime.TryParse(text, CultureInfo.InvariantCulture, DateTimeStyles.AssumeLocal | DateTimeStyles.AdjustToUniversal, out utc);
        }

        if (value is DateTime date)
        {
            utc = date.Kind == DateTimeKind.Utc ? date : DateTime.SpecifyKind(date, DateTimeKind.Local).ToUniversalTime();
            return true;
        }

        utc = default;
        return false;
    }

    private WrappedTrack TrackOf(
        string itemId,
        string itemName,
        Dictionary<string, WrappedTrack> tracks,
        Dictionary<string, IReadOnlyList<string>> artistGenres)
    {
        if (tracks.TryGetValue(itemId, out var known))
        {
            return known;
        }

        var track = Guid.TryParse(itemId, out var id) && _libraryManager.GetItemById(id) is Audio audio
            ? FromLibrary(audio, name => ArtistGenres(name, artistGenres))
            : FromName(itemId, itemName);
        tracks[itemId] = track;
        return track;
    }

    private static WrappedTrack FromLibrary(Audio audio, Func<string, IReadOnlyList<string>> artistGenres)
    {
        var album = audio.AlbumEntity;
        var artists = audio.Artists.Count > 0 ? audio.Artists : audio.AlbumArtists;
        var genres = GenresOf(audio.Genres, album?.Genres ?? [], audio.Artists, artistGenres);
        return new WrappedTrack(
            audio.Id.ToString("N"),
            audio.Id.ToString("N"),
            audio.Name ?? string.Empty,
            string.Join(", ", artists),
            artists.ToList(),
            album?.Id.ToString("N"),
            audio.Album,
            audio.AlbumArtists.Count > 0 ? string.Join(", ", audio.AlbumArtists) : null,
            genres,
            audio.RunTimeTicks is > 0 and var ticks ? TimeSpan.FromTicks(ticks).TotalSeconds : null);
    }

    /// <summary>
    /// The genres a song counts for: its own, else its album's (which, for albums whose songs have none, usually come from
    /// online metadata), else those of all its track artists. Album artists are left out, as on compilations they are
    /// e.g. "Various Artists" rather than who made the song.
    /// </summary>
    public static IReadOnlyList<string> GenresOf(
        IReadOnlyList<string> songGenres,
        IReadOnlyList<string> albumGenres,
        IReadOnlyList<string> trackArtists,
        Func<string, IReadOnlyList<string>> artistGenres)
    {
        if (songGenres.Count > 0)
        {
            return songGenres;
        }

        if (albumGenres.Count > 0)
        {
            return albumGenres;
        }

        return trackArtists.SelectMany(artistGenres).Distinct(StringComparer.OrdinalIgnoreCase).ToList();
    }

    /// <summary>The genres of the library's artist named <paramref name="name"/>, looked up once per <paramref name="known"/>.</summary>
    private IReadOnlyList<string> ArtistGenres(string name, Dictionary<string, IReadOnlyList<string>> known)
    {
        if (!known.TryGetValue(name, out var genres))
        {
            var query = new InternalItemsQuery { IncludeItemTypes = [BaseItemKind.MusicArtist], Name = name, Limit = 1 };
            genres = _libraryManager.GetItemList(query).FirstOrDefault()?.Genres ?? [];
            known[name] = genres;
        }

        return genres;
    }

    /// <summary>A song no longer in the library, from the name Playback Reporting noted: "Album artists - Title (Album)".</summary>
    public static WrappedTrack FromName(string itemId, string itemName)
    {
        var artist = string.Empty;
        var title = itemName.Trim();
        string? album = null;
        var separator = title.IndexOf(" - ", StringComparison.Ordinal);
        if (separator > 0)
        {
            artist = title[..separator];
            title = title[(separator + 3)..];
        }

        var albumStart = title.LastIndexOf(" (", StringComparison.Ordinal);
        if (albumStart > 0 && title.EndsWith(')'))
        {
            album = title[(albumStart + 2)..^1];
            title = title[..albumStart];
        }

        const string Unknown = "Not Known";
        return new WrappedTrack(
            itemId,
            null,
            title,
            artist == Unknown ? string.Empty : artist,
            [],
            null,
            album == Unknown ? null : album,
            null,
            [],
            null);
    }

    private string? FindArtistId(string name)
    {
        var query = new InternalItemsQuery { IncludeItemTypes = [BaseItemKind.MusicArtist], Name = name, Limit = 1 };
        return _libraryManager.GetItemList(query).FirstOrDefault()?.Id.ToString("N");
    }
}
