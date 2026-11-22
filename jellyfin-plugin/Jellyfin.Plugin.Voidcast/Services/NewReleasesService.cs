using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Data.Enums;
using Jellyfin.Database.Implementations.Entities;
using Jellyfin.Plugin.Voidcast.Api;
using MediaBrowser.Common.Configuration;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Entities.Audio;
using MediaBrowser.Controller.Library;
using MediaBrowser.Model.Tasks;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Voidcast.Services;

/// <summary>
/// Looks on YouTube Music for releases the library doesn't have, see <see cref="NewReleasesScanTask"/>. Runs without
/// anyone watching on the IP that also streams and downloads, so it goes slowly and gives up when YouTube refuses.
/// </summary>
public class NewReleasesService
{
    private const int MaxArtistsAtOnce = 2;
    private const int MaxFailuresInARow = 10;

    private readonly ILogger<NewReleasesService> _logger;
    private readonly YouTubeMusicClient _youTubeMusic;
    private readonly ILibraryManager _libraryManager;
    private readonly IUserManager _userManager;
    private readonly ITaskManager _taskManager;
    private readonly string _path;
    private readonly string _artistIdsPath;
    private readonly object _lock = new();
    private NewReleasesStatusDto _status = new();
    private NewReleasesScanDto? _scan;
    private bool _loaded;
    private bool _fullScanRequested;

    public NewReleasesService(
        ILogger<NewReleasesService> logger,
        YouTubeMusicClient youTubeMusic,
        ILibraryManager libraryManager,
        IUserManager userManager,
        ITaskManager taskManager,
        IApplicationPaths applicationPaths)
    {
        _logger = logger;
        _youTubeMusic = youTubeMusic;
        _libraryManager = libraryManager;
        _userManager = userManager;
        _taskManager = taskManager;
        _path = Path.Combine(applicationPaths.DataPath, "voidcast", "new-releases.json");
        _artistIdsPath = Path.Combine(applicationPaths.DataPath, "voidcast", "new-releases-artist-ids.json");
    }

    public NewReleasesStatusDto Status
    {
        get
        {
            lock (_lock)
            {
                return _status;
            }
        }
    }

    /// <summary>
    /// Starts a scan, unless one is running. A <paramref name="full"/> one looks everything up again rather than taking
    /// over artist channels and release dates from earlier scans.
    /// </summary>
    public void RequestScan(bool full = false)
    {
        lock (_lock)
        {
            if (_status.IsScanning)
            {
                return;
            }

            _fullScanRequested = full;

            // Already now, so that a client asking right away doesn't take the scan for finished
            _status = new NewReleasesStatusDto { IsScanning = true };
        }

        _taskManager.QueueIfNotRunning<NewReleasesScanTask>();
    }

    /// <summary>
    /// The latest scan as <paramref name="userId"/> gets to see it: without what got into the library since, and with
    /// only artists of the libraries they can access or, if the scan was of favorites, their own favorites.
    /// </summary>
    public NewReleasesDto GetReleases(Guid userId)
    {
        var scan = LoadScan();
        // Requests with an API key have no user
        var user = userId == Guid.Empty ? null : _userManager.GetUserById(userId);
        if (scan == null || user == null)
        {
            return new NewReleasesDto { Status = Status };
        }

        var library = ReadLibrary(user);
        var favorites = scan.FavoritesOnly ? FavoriteArtists(user) : null;
        var sinceYear = DateTime.UtcNow.Year - 1;
        return new NewReleasesDto
        {
            Status = Status,
            Scan = scan with
            {
                ArtistCount = favorites?.Count ?? scan.ArtistCount,
                Releases = scan.Releases
                    .Where(release => favorites?.Contains(release.Artist) ?? library.ContainsKey(release.Artist))
                    .Where(release => NewReleaseMatching.IsNew(release, library.GetValueOrDefault(release.Artist), sinceYear))
                    .ToList(),
            },
        };
    }

    /// <summary>Looks for new releases of the artists the plugin's settings say; the previous result stays if it fails.</summary>
    public async Task ScanAsync(IProgress<double> progress, CancellationToken cancellationToken)
    {
        // The task manager calls this on the thread of whoever started the task, possibly a client's request
        await Task.Yield();
        bool full;
        lock (_lock)
        {
            full = _fullScanRequested;
            _fullScanRequested = false;
        }

        SetStatus(_ => new NewReleasesStatusDto { IsScanning = true });
        try
        {
            var scan = await FindReleasesAsync(full, progress, cancellationToken).ConfigureAwait(false);
            SaveScan(scan);
            _logger.LogInformation(
                "Found {Releases} new releases of {Artists} artists, {Failed} couldn't be checked",
                scan.Releases.Count,
                scan.ArtistCount,
                scan.FailedArtists);
        }
        catch (Exception e) when (e is not OperationCanceledException)
        {
            SetStatus(status => status with { Error = e.Message });
            throw;
        }
        finally
        {
            SetStatus(status => status with { IsScanning = false });
        }
    }

    private async Task<NewReleasesScanDto> FindReleasesAsync(bool full, IProgress<double> progress, CancellationToken cancellationToken)
    {
        var config = Plugin.Instance!.Configuration;
        var minSongs = config.NewReleasesMinSongs;
        var favoritesOnly = config.NewReleasesFavoritesOnly;
        var library = ReadLibrary(null);
        var artists = favoritesOnly
            ? _userManager.GetUsers().SelectMany(FavoriteArtists).Distinct(StringComparer.OrdinalIgnoreCase)
                .Select(name => library.GetValueOrDefault(name) ?? new OwnedArtist(name, [], [], 0))
                .ToList()
            : library.Values.Where(artist => artist.SongCount >= minSongs).ToList();
        SetStatus(status => status with { ArtistCount = artists.Count });

        // Saves looking up again what doesn't change between scans: which channel an artist has, and when a release came
        // out. A full scan starts over, and what it finds replaces what earlier scans remembered.
        var artistIds = new ConcurrentDictionary<string, string>(full ? [] : LoadArtistIds(), StringComparer.OrdinalIgnoreCase);
        var previousReleases = full ? [] : LoadScan()?.Releases ?? [];
        var releaseDates = previousReleases
            .Where(release => release.ReleaseDate != null)
            .GroupBy(release => release.Id)
            .ToDictionary(group => group.Key, group => group.First().ReleaseDate);
        var cache = new ScanCache(artistIds, releaseDates);

        var sinceYear = DateTime.UtcNow.Year - 1;
        var found = new List<NewReleaseDto>();
        var failed = 0;
        var failuresInARow = 0;
        var done = 0;
        using var giveUp = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        var options = new ParallelOptions { MaxDegreeOfParallelism = MaxArtistsAtOnce, CancellationToken = giveUp.Token };
        try
        {
            await Parallel.ForEachAsync(artists, options, async (artist, token) =>
            {
                try
                {
                    var releases = await FindReleasesAsync(artist, sinceYear, cache, token).ConfigureAwait(false);
                    Interlocked.Exchange(ref failuresInARow, 0);
                    lock (found)
                    {
                        found.AddRange(releases);
                    }
                }
                catch (Exception e) when (!token.IsCancellationRequested)
                {
                    _logger.LogDebug(e, "Could not look up the releases of {Artist}", artist.Name);
                    Interlocked.Increment(ref failed);
                    if (Interlocked.Increment(ref failuresInARow) >= MaxFailuresInARow)
                    {
                        await giveUp.CancelAsync().ConfigureAwait(false);
                    }
                }

                var checkedArtists = Interlocked.Increment(ref done);
                SetStatus(status => status with { CheckedArtists = Math.Max(status.CheckedArtists, checkedArtists) });
                progress.Report(100.0 * checkedArtists / artists.Count);
            }).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            throw new HttpRequestException("YouTube Music keeps refusing requests");
        }
        finally
        {
            SaveArtistIds(artistIds);
        }

        if (artists.Count > 0 && failed == artists.Count)
        {
            throw new HttpRequestException("Couldn't reach YouTube Music");
        }

        return new NewReleasesScanDto
        {
            ScannedAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
            ArtistCount = artists.Count,
            FailedArtists = failed,
            MinSongs = minSongs,
            FavoritesOnly = favoritesOnly,
            Releases = NewReleaseMatching.InNewReleasesOrder(found),
        };
    }

    /// <summary>Empty if YouTube Music has no artist of exactly that name.</summary>
    private async Task<List<NewReleaseDto>> FindReleasesAsync(OwnedArtist artist, int sinceYear, ScanCache cache, CancellationToken cancellationToken)
    {
        var (artistId, page) = await GetArtistPageAsync(artist.Name, cache.ArtistIds, cancellationToken).ConfigureAwait(false);
        if (page == null)
        {
            return [];
        }

        var releases = new List<NewReleaseDto>();
        foreach (var release in page.Albums.Where(album => NewReleaseMatching.IsNew(album, artist, sinceYear)))
        {
            releases.Add(new NewReleaseDto
            {
                Id = release.Id,
                Title = release.Title,
                ReleaseType = release.ReleaseType,
                Year = release.Year,
                ThumbnailUrl = release.ThumbnailUrl,
                ReleaseDate = cache.ReleaseDates.GetValueOrDefault(release.Id)
                    ?? await GetReleaseDateAsync(release.Id, cancellationToken).ConfigureAwait(false),
                // As in the library, the results get matched with it by this name
                Artist = artist.Name,
                ArtistId = artistId!,
            });
        }

        return releases;
    }

    /// <summary>
    /// The page of the artist on YouTube Music of exactly that name, both null if there's none. Goes to the channel it
    /// had before right away, and searches again only if that one is gone.
    /// </summary>
    private async Task<(string? Id, YouTubeArtistPageDto? Page)> GetArtistPageAsync(
        string name,
        ConcurrentDictionary<string, string> artistIds,
        CancellationToken cancellationToken)
    {
        if (artistIds.TryGetValue(name, out var cachedId))
        {
            var cachedPage = await _youTubeMusic.GetArtistAsync(cachedId, cancellationToken).ConfigureAwait(false);
            if (cachedPage != null)
            {
                return (cachedId, cachedPage);
            }

            artistIds.TryRemove(name, out _);
        }

        var results = await _youTubeMusic.SearchArtistsAsync(name, cancellationToken).ConfigureAwait(false);
        var match = results.FirstOrDefault(result => result.Name.Equals(name, StringComparison.OrdinalIgnoreCase));
        if (match == null)
        {
            // Not remembered, as the artist may still turn up
            return (null, null);
        }

        var page = await _youTubeMusic.GetArtistAsync(match.Id, cancellationToken).ConfigureAwait(false);
        if (page != null)
        {
            artistIds[name] = match.Id;
        }

        return (match.Id, page);
    }

    private async Task<string?> GetReleaseDateAsync(string albumId, CancellationToken cancellationToken)
    {
        try
        {
            return await _youTubeMusic.GetAlbumReleaseDateAsync(albumId, cancellationToken).ConfigureAwait(false);
        }
        catch (HttpRequestException)
        {
            // Only for ordering
            return null;
        }
    }

    /// <summary>The artists in the libraries <paramref name="user"/> can access; all libraries' without a user.</summary>
    private Dictionary<string, OwnedArtist> ReadLibrary(User? user)
    {
        var query = new InternalItemsQuery(user) { IncludeItemTypes = [BaseItemKind.Audio], Recursive = true };
        return NewReleaseMatching.ByArtist(
            _libraryManager.GetItemList(query).OfType<Audio>().Select(song => new LibrarySong(song.Name, song.Album, song.Artists)));
    }

    private HashSet<string> FavoriteArtists(User user)
    {
        var query = new InternalItemsQuery(user) { IsFavorite = true };
        return _libraryManager.GetArtists(query).Items.Select(artist => artist.Item.Name).ToHashSet(StringComparer.OrdinalIgnoreCase);
    }

    private void SetStatus(Func<NewReleasesStatusDto, NewReleasesStatusDto> update)
    {
        lock (_lock)
        {
            _status = update(_status);
        }
    }

    private Dictionary<string, string> LoadArtistIds()
    {
        try
        {
            return File.Exists(_artistIdsPath)
                ? JsonSerializer.Deserialize<Dictionary<string, string>>(File.ReadAllText(_artistIdsPath)) ?? []
                : [];
        }
        catch (Exception e) when (e is IOException or JsonException)
        {
            _logger.LogWarning(e, "Could not read {Path}", _artistIdsPath);
            return [];
        }
    }

    private void SaveArtistIds(IDictionary<string, string> artistIds)
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(_artistIdsPath)!);
            var partial = _artistIdsPath + ".part";
            File.WriteAllText(partial, JsonSerializer.Serialize(artistIds.OrderBy(entry => entry.Key).ToDictionary()));
            File.Move(partial, _artistIdsPath, true);
        }
        catch (IOException e)
        {
            _logger.LogWarning(e, "Could not save {Path}", _artistIdsPath);
        }
    }

    private NewReleasesScanDto? LoadScan()
    {
        lock (_lock)
        {
            if (_loaded)
            {
                return _scan;
            }

            _loaded = true;
            try
            {
                _scan = File.Exists(_path) ? JsonSerializer.Deserialize<NewReleasesScanDto>(File.ReadAllText(_path)) : null;
            }
            catch (Exception e) when (e is IOException or JsonException)
            {
                _logger.LogWarning(e, "Could not read {Path}", _path);
            }

            return _scan;
        }
    }

    private void SaveScan(NewReleasesScanDto scan)
    {
        lock (_lock)
        {
            _scan = scan;
            _loaded = true;
            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(_path)!);
                var partial = _path + ".part";
                File.WriteAllText(partial, JsonSerializer.Serialize(scan));
                File.Move(partial, _path, true);
            }
            catch (IOException e)
            {
                _logger.LogWarning(e, "Could not save {Path}", _path);
            }
        }
    }
}

/// <summary>What a scan takes over from earlier ones, see <see cref="NewReleasesService.ScanAsync"/>.</summary>
internal sealed record ScanCache(ConcurrentDictionary<string, string> ArtistIds, IReadOnlyDictionary<string, string?> ReleaseDates);
