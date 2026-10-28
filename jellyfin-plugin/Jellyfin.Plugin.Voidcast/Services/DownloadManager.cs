using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Voidcast.Api;
using MediaBrowser.Common.Net;
using MediaBrowser.Controller.Library;
using MediaBrowser.Controller.Playlists;
using MediaBrowser.Model.Tasks;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Voidcast.Services;

/// <summary>
/// Queues downloads requested by clients, writes tagged files into the music library and triggers a library scan.
/// Also adds tracks to playlists once they are in the library.
/// </summary>
public partial class DownloadManager
{
    private const int MaxRetainedJobs = 200;
    private const int MaxRetainedAdditions = 50;
    private static readonly TimeSpan ImportTimeout = TimeSpan.FromMinutes(3);
    /// <summary>How long a track to add to a playlist may take to show up in the library (large libraries scan slowly).</summary>
    private static readonly TimeSpan PlaylistImportTimeout = TimeSpan.FromMinutes(30);
    private static readonly TimeSpan AlbumTracksLifetime = TimeSpan.FromDays(1);
    private static readonly string[] CoverHostSuffixes = [".googleusercontent.com", ".ytimg.com", ".ggpht.com"];

    private readonly ILogger<DownloadManager> _logger;
    private readonly YtDlpService _ytDlp;
    private readonly YouTubeMusicClient _youTubeMusic;
    private readonly ILibraryManager _libraryManager;
    private readonly ITaskManager _taskManager;
    private readonly IPlaylistManager _playlistManager;
    private readonly IHttpClientFactory _httpClientFactory;
    private readonly DownloadIndex _index;
    private readonly ConcurrentDictionary<Guid, Job> _jobs = new();
    private readonly ConcurrentDictionary<Guid, Addition> _additions = new();
    private readonly SemaphoreSlim _slots;
    /// <summary>Video ids of album tracks, by album id; albums rarely change, so look each up at most daily.</summary>
    private readonly ConcurrentDictionary<string, (DateTime Fetched, IReadOnlyList<string> VideoIds)> _albumTracks = new();
    private readonly SemaphoreSlim _albumLookups = new(4, 4);

    public DownloadManager(
        ILogger<DownloadManager> logger,
        YtDlpService ytDlp,
        YouTubeMusicClient youTubeMusic,
        ILibraryManager libraryManager,
        ITaskManager taskManager,
        IPlaylistManager playlistManager,
        IHttpClientFactory httpClientFactory,
        DownloadIndex index)
    {
        _logger = logger;
        _ytDlp = ytDlp;
        _youTubeMusic = youTubeMusic;
        _libraryManager = libraryManager;
        _taskManager = taskManager;
        _playlistManager = playlistManager;
        _httpClientFactory = httpClientFactory;
        _index = index;
        var slots = Math.Clamp(Plugin.Instance?.Configuration.MaxConcurrentDownloads ?? 2, 1, 8);
        _slots = new SemaphoreSlim(slots, slots);
    }

    /// <summary>Returns the folder downloads go to: the configured one or the first folder of the first music library.</summary>
    public string? GetDownloadDirectory()
    {
        var configured = Plugin.Instance?.Configuration.DownloadDirectory.Trim();
        if (!string.IsNullOrEmpty(configured))
        {
            return configured;
        }

        return _libraryManager.GetVirtualFolders()
            .Where(folder => folder.CollectionType?.ToString() == "music")
            .SelectMany(folder => folder.Locations)
            .FirstOrDefault();
    }

    public DownloadJobDto Enqueue(YouTubeSongDto song, Guid userId) => EnqueueJob(song, userId).Dto;

    private Job EnqueueJob(YouTubeSongDto song, Guid userId)
    {
        ValidateSong(song);
        var targetDir = GetDownloadDirectory()
            ?? throw new InvalidOperationException("No download folder configured and no music library found");

        // Requesting the same track again while it's being downloaded just returns the running job
        var running = _jobs.Values.FirstOrDefault(job =>
            job.UserId == userId && job.Dto.VideoId == song.VideoId && job.Dto.State is DownloadState.Queued or DownloadState.Downloading or DownloadState.Importing);
        if (running != null)
        {
            return running;
        }

        var dto = new DownloadJobDto
        {
            Id = Guid.NewGuid(),
            VideoId = song.VideoId,
            Title = song.Title,
            State = DownloadState.Queued,
            CreatedAt = DateTime.UtcNow,
        };
        var newJob = new Job(dto, userId, new TaskCompletionSource<Guid?>(TaskCreationOptions.RunContinuationsAsynchronously));
        _jobs[dto.Id] = newJob;
        PruneJobs();
        _ = Task.Run(() => ProcessAsync(newJob, song, targetDir));
        return newJob;
    }

    private static void ValidateSong(YouTubeSongDto song)
    {
        if (!YtDlpService.IsValidVideoId(song.VideoId))
        {
            throw new ArgumentException("Invalid YouTube video id");
        }

        if (string.IsNullOrWhiteSpace(song.Title))
        {
            throw new ArgumentException("A title is required");
        }

        if (song.StartTime < 0 || song.EndTime <= (song.StartTime ?? 0))
        {
            throw new ArgumentException("The end must be after the start");
        }
    }

    /// <summary>
    /// Adds <paramref name="songs"/> to <paramref name="playlist"/> in the given order, downloading those that aren't
    /// in the library yet; tracks already in the playlist are skipped. Runs in the background, also if the client goes
    /// away. <paramref name="mayDownload"/> tells whether the user may download tracks that aren't in the library.
    /// </summary>
    public PlaylistAdditionDto AddToPlaylist(Playlist playlist, IReadOnlyList<YouTubeSongDto> songs, Guid userId, bool mayDownload)
    {
        foreach (var song in songs)
        {
            ValidateSong(song);
        }

        var paths = songs.Select(song => _index.FindPath(song.VideoId)).ToList();
        if (!mayDownload && paths.Any(path => path == null))
        {
            throw new UnauthorizedAccessException("Only administrators may download into the library (see the Voidcast plugin settings)");
        }

        var entries = songs.Select((song, i) => new AdditionEntry(song, paths[i], paths[i] == null ? EnqueueJob(song, userId) : null)).ToList();
        var dto = new PlaylistAdditionDto
        {
            Id = Guid.NewGuid(),
            PlaylistId = playlist.Id.ToString("N"),
            PlaylistName = playlist.Name,
            Total = songs.Count,
            Jobs = entries.Select(entry => entry.Job?.Dto).OfType<DownloadJobDto>().ToList(),
            CreatedAt = DateTime.UtcNow,
        };
        var addition = new Addition(dto, userId);
        _additions[dto.Id] = addition;
        PruneAdditions();
        _ = Task.Run(() => ProcessAdditionAsync(addition, playlist.Id, entries));
        return dto;
    }

    public PlaylistAdditionDto? GetAddition(Guid id, Guid userId) =>
        _additions.TryGetValue(id, out var addition) && addition.UserId == userId ? addition.Dto : null;

    public IReadOnlyList<PlaylistAdditionDto> GetAdditions(Guid userId) =>
        _additions.Values.Where(addition => addition.UserId == userId).Select(addition => addition.Dto).OrderByDescending(dto => dto.CreatedAt).ToList();

    /// <summary>Adds the tracks one after another, so the playlist gets them in the requested order however downloads finish.</summary>
    private async Task ProcessAdditionAsync(Addition addition, Guid playlistId, IReadOnlyList<AdditionEntry> entries)
    {
        var dto = addition.Dto;
        foreach (var entry in entries)
        {
            try
            {
                var itemId = await GetLibraryItemAsync(entry).ConfigureAwait(false)
                    ?? throw new InvalidOperationException("It didn't show up in the library, the library scan may still be running");
                // Re-read every time: the playlist may have been edited while the downloads ran
                var playlist = _libraryManager.GetItemById(playlistId) as Playlist
                    ?? throw new InvalidOperationException("The playlist was deleted");
                if (playlist.GetManageableItems().Any(item => item.Item2?.Id == itemId))
                {
                    dto.AlreadyInPlaylist++;
                    continue;
                }

                await _playlistManager.AddItemToPlaylistAsync(playlistId, [itemId], null, addition.UserId).ConfigureAwait(false);
                dto.Added++;
            }
            catch (Exception e)
            {
                _logger.LogWarning(e, "Could not add {VideoId} to playlist {PlaylistId}", entry.Song.VideoId, playlistId);
                lock (dto.Failures)
                {
                    dto.Failures.Add(entry.Song.Title + ": " + e.Message);
                }
            }
        }

        dto.Finished = true;
    }

    /// <summary>Returns the library item of the entry's track, waiting for its download and the library scan.</summary>
    private async Task<Guid?> GetLibraryItemAsync(AdditionEntry entry)
    {
        var path = entry.Path;
        if (entry.Job != null)
        {
            var imported = await entry.Job.Imported.Task.ConfigureAwait(false);
            if (imported != null)
            {
                return imported;
            }

            path = entry.Job.Dto.Path;
        }
        else if (_libraryManager.FindByPath(path!, false) is { } item)
        {
            return item.Id;
        }
        else
        {
            // Downloaded, but not scanned yet
            QueueLibraryScan();
        }

        return path != null ? await WaitForItemAsync(path, PlaylistImportTimeout).ConfigureAwait(false) : null;
    }

    /// <summary>Returns those of <paramref name="videoIds"/> that are already in the download folder.</summary>
    public IReadOnlyList<DownloadedTrack> FindDownloaded(IEnumerable<string> videoIds) =>
        _index.FindDownloaded(videoIds, GetDownloadDirectory());

    /// <summary>Returns those of <paramref name="albumIds"/> whose tracks are all in the download folder.</summary>
    public async Task<IReadOnlyList<string>> FindDownloadedAlbumsAsync(IEnumerable<string> albumIds, CancellationToken cancellationToken)
    {
        var albums = await Task.WhenAll(albumIds.Distinct().Select(async albumId =>
            (Id: albumId, VideoIds: await GetAlbumTracksAsync(albumId, cancellationToken).ConfigureAwait(false)))).ConfigureAwait(false);
        var downloaded = FindDownloaded(albums.SelectMany(album => album.VideoIds)).Select(track => track.VideoId).ToHashSet();
        return albums
            .Where(album => album.VideoIds.Count > 0 && album.VideoIds.All(downloaded.Contains))
            .Select(album => album.Id)
            .ToList();
    }

    private async Task<IReadOnlyList<string>> GetAlbumTracksAsync(string albumId, CancellationToken cancellationToken)
    {
        if (_albumTracks.TryGetValue(albumId, out var cached) && DateTime.UtcNow - cached.Fetched < AlbumTracksLifetime)
        {
            return cached.VideoIds;
        }

        // Don't hit YouTube Music with dozens of requests at once when a client opens an artist
        await _albumLookups.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var album = await GetAlbumAsync(albumId, cancellationToken).ConfigureAwait(false);
            if (album == null)
            {
                return [];
            }

            // Unavailable tracks can't be downloaded, so they don't count
            var videoIds = album.Tracks.Select(track => track.VideoId).OfType<string>().ToList();
            _albumTracks[albumId] = (DateTime.UtcNow, videoIds);
            return videoIds;
        }
        finally
        {
            _albumLookups.Release();
        }
    }

    public DownloadJobDto? GetJob(Guid id, Guid userId) =>
        _jobs.TryGetValue(id, out var job) && job.UserId == userId ? job.Dto : null;

    public IReadOnlyList<DownloadJobDto> GetJobs(Guid userId) =>
        _jobs.Values.Where(job => job.UserId == userId).Select(job => job.Dto).OrderByDescending(dto => dto.CreatedAt).ToList();

    private async Task ProcessAsync(Job job, YouTubeSongDto song, string targetDir)
    {
        try
        {
            await _slots.WaitAsync().ConfigureAwait(false);
            string target;
            try
            {
                job.Dto.State = DownloadState.Downloading;
                target = await DownloadAsync(song, targetDir, CancellationToken.None).ConfigureAwait(false);
            }
            finally
            {
                _slots.Release();
            }

            job.Dto.Path = target;
            _index.Add(song.VideoId, target);
            job.Dto.State = DownloadState.Importing;
            job.Dto.Message = "Saved to " + target + ", scanning library";
            _logger.LogInformation("Downloaded {VideoId} to {Path}", song.VideoId, target);

            var itemId = await ImportAsync(target).ConfigureAwait(false);
            job.Dto.ItemId = itemId?.ToString("N");
            job.Dto.Message = itemId != null
                ? "Saved to " + target + " and added to the library"
                : "Saved to " + target + ", it will appear once the library scan finishes";
            if (_ytDlp.CookiesWarning != null)
            {
                job.Dto.Message += ". " + _ytDlp.CookiesWarning;
            }

            job.Dto.State = DownloadState.Completed;
            job.Imported.TrySetResult(itemId);
        }
        catch (Exception e)
        {
            _logger.LogError(e, "Downloading {VideoId} failed", song.VideoId);
            job.Dto.Message = e.Message;
            job.Dto.State = DownloadState.Failed;
            job.Imported.TrySetException(e);
        }
    }

    /// <summary>
    /// Downloads the track (or the part of it the client asked for) in its original codec (no re-encoding), tags it (with details of its album if it can
    /// be looked up) and moves it to
    /// <c>targetDir/Album Artist/YYYY - Album/NN Title.ext</c> (see <see cref="GetRelativePath"/>). Returns the file path.
    /// </summary>
    private async Task<string> DownloadAsync(YouTubeSongDto song, string targetDir, CancellationToken cancellationToken)
    {
        var workDir = Directory.CreateTempSubdirectory("voidcast-dl").FullName;
        try
        {
            var audio = await _ytDlp.DownloadAudioAsync(song.VideoId, workDir, null, cancellationToken, song.StartTime, song.EndTime)
                .ConfigureAwait(false);
            var album = await GetAlbumAsync(song.AlbumId, cancellationToken).ConfigureAwait(false);
            var tags = TrackTagsBuilder.Build(song, album);
            // The YouTube Music search thumbnail is the square album cover; the video thumbnail often isn't
            var cover = await DownloadCoverAsync(
                song.ThumbnailUrl != null ? YouTubeMusicClient.ResizeThumbnail(song.ThumbnailUrl, 544) : null,
                cancellationToken).ConfigureAwait(false);

            AudioTagger.Write(audio, tags, cover);

            var target = Path.Combine(targetDir, GetRelativePath(tags) + Path.GetExtension(audio).ToLowerInvariant());
            Directory.CreateDirectory(Path.GetDirectoryName(target)!);
            // Copy under a non-audio extension first so the library scan never picks up a half-written file
            var partial = target + ".part";
            File.Copy(audio, partial, true);
            File.Move(partial, target, true);
            return target;
        }
        finally
        {
            try
            {
                Directory.Delete(workDir, true);
            }
            catch (IOException e)
            {
                _logger.LogDebug(e, "Could not delete {Dir}", workDir);
            }
        }
    }

    /// <summary>
    /// Returns the path of the file without extension, following the Picard naming script
    /// <code>
    /// $if2($getmulti(%albumartists%,0),$getmulti(%artists%,0),%artist%)/
    /// $if(%album%,$if(%date%,$left(%date%,4) - ,)%album%/,)
    /// $if($gt(%totaldiscs%,1),CD %discnumber%/,)
    /// $if($and(%album%,%tracknumber%),$num(%tracknumber%,2) ,)
    /// %title%
    /// </code>
    /// The disc folder never applies: YouTube Music albums have no discs.
    /// </summary>
    private static string GetRelativePath(TrackTags tags)
    {
        var artist = new[] { tags.AlbumArtists.FirstOrDefault(), tags.Artists.FirstOrDefault(), tags.Artist }
            .FirstOrDefault(name => !string.IsNullOrWhiteSpace(name)) ?? "Unknown Artist";
        var parts = new List<string> { artist };

        var hasAlbum = !string.IsNullOrWhiteSpace(tags.Album);
        if (hasAlbum)
        {
            parts.Add(tags.Year != null ? $"{tags.Year.Value:0000} - {tags.Album}" : tags.Album!);
        }

        var trackPrefix = hasAlbum && tags.TrackNumber != null
            ? tags.TrackNumber.Value.ToString("00", System.Globalization.CultureInfo.InvariantCulture) + " "
            : string.Empty;
        parts.Add(trackPrefix + tags.Title);

        return Path.Combine(parts.Select(SanitizeFileName).ToArray());
    }

    private async Task<YouTubeAlbum?> GetAlbumAsync(string albumId, CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(albumId))
        {
            return null;
        }

        try
        {
            return await _youTubeMusic.GetAlbumAsync(albumId, cancellationToken).ConfigureAwait(false);
        }
        catch (Exception e) when (e is HttpRequestException or System.Text.Json.JsonException)
        {
            // Tag what the search result provides
            _logger.LogWarning(e, "Could not look up album {AlbumId}", albumId);
            return null;
        }
    }

    private async Task<byte[]?> DownloadCoverAsync(string? url, CancellationToken cancellationToken)
    {
        // Only fetch from YouTube's image hosts: the URL is part of the client's request
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri)
            || uri.Scheme != Uri.UriSchemeHttps
            || !CoverHostSuffixes.Any(suffix => uri.Host.EndsWith(suffix, StringComparison.OrdinalIgnoreCase)))
        {
            return null;
        }

        try
        {
            var client = _httpClientFactory.CreateClient(NamedClient.Default);
            return await client.GetByteArrayAsync(uri, cancellationToken).ConfigureAwait(false);
        }
        catch (HttpRequestException e)
        {
            _logger.LogWarning(e, "Could not download cover {Url}", url);
            return null;
        }
    }

    /// <summary>Queues a library scan and waits (bounded) for the track to show up. Returns its item id if it did.</summary>
    private Task<Guid?> ImportAsync(string path)
    {
        QueueLibraryScan();
        return WaitForItemAsync(path, ImportTimeout);
    }

    private void QueueLibraryScan()
    {
        var scan = _taskManager.ScheduledTasks.FirstOrDefault(task => task.ScheduledTask.Key == "RefreshLibrary");
        if (scan != null)
        {
            // Runs again after the current scan if one is in progress, which may have passed the folder already
            _taskManager.QueueScheduledTask(scan.ScheduledTask, new TaskOptions());
        }
        else
        {
            _ = _libraryManager.ValidateMediaLibrary(new Progress<double>(), CancellationToken.None);
        }
    }

    private async Task<Guid?> WaitForItemAsync(string path, TimeSpan timeout)
    {
        var deadline = DateTime.UtcNow + timeout;
        while (DateTime.UtcNow < deadline)
        {
            await Task.Delay(TimeSpan.FromSeconds(2)).ConfigureAwait(false);
            var item = _libraryManager.FindByPath(path, false);
            if (item != null)
            {
                return item.Id;
            }
        }

        return null;
    }

    private void PruneJobs()
    {
        foreach (var old in _jobs.Values
                     .Where(job => job.Dto.State is DownloadState.Completed or DownloadState.Failed)
                     .OrderByDescending(job => job.Dto.CreatedAt)
                     .Skip(MaxRetainedJobs))
        {
            _jobs.TryRemove(old.Dto.Id, out _);
        }
    }

    private void PruneAdditions()
    {
        foreach (var old in _additions.Values
                     .Where(addition => addition.Dto.Finished)
                     .OrderByDescending(addition => addition.Dto.CreatedAt)
                     .Skip(MaxRetainedAdditions))
        {
            _additions.TryRemove(old.Dto.Id, out _);
        }
    }

    private static string SanitizeFileName(string name)
    {
        var sanitized = InvalidFileNameChars().Replace(name, "_").Trim().Trim('.');
        if (sanitized.Length > 120)
        {
            sanitized = sanitized[..120];
        }

        return sanitized.Length == 0 ? "Unknown" : sanitized;
    }

    [GeneratedRegex(@"[\\/:*?""<>|\p{Cc}]")]
    private static partial Regex InvalidFileNameChars();

    /// <summary><paramref name="Imported"/> completes with the library item id (null if the scan didn't find it in time).</summary>
    private sealed record Job(DownloadJobDto Dto, Guid UserId, TaskCompletionSource<Guid?> Imported);

    private sealed record Addition(PlaylistAdditionDto Dto, Guid UserId);

    /// <summary>A track to add to a playlist: <paramref name="Path"/> if downloaded before, else its download <paramref name="Job"/>.</summary>
    private sealed record AdditionEntry(YouTubeSongDto Song, string? Path, Job? Job);
}
