using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Data.Enums;
using MediaBrowser.Common.Configuration;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Library;
using MediaBrowser.Model.Entities;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Voidcast.Services;

/// <summary>
/// Knows which YouTube Music tracks are in the library: Jellyfin keeps their video id as provider id (see
/// <see cref="YouTubeMusicId"/>), along with their codec and bitrate. Downloads the library scan hasn't picked up yet
/// are remembered until it has.
/// </summary>
public class DownloadedTracks
{
    private readonly ILogger<DownloadedTracks> _logger;
    private readonly ILibraryManager _libraryManager;
    /// <summary>Where earlier plugin versions kept the video ids of the download folder's files.</summary>
    private readonly string _legacyIndexPath;
    /// <summary>Paths of finished downloads by video id, until they are in the library.</summary>
    private readonly ConcurrentDictionary<string, string> _pending = new();
    private int _migrationStarted;

    public DownloadedTracks(ILogger<DownloadedTracks> logger, ILibraryManager libraryManager, IApplicationPaths applicationPaths)
    {
        _logger = logger;
        _libraryManager = libraryManager;
        _legacyIndexPath = Path.Combine(applicationPaths.DataPath, "voidcast", "download-index.json");
    }

    /// <summary>Returns those of <paramref name="videoIds"/> whose tracks are in the library or were just downloaded.</summary>
    public IReadOnlyList<DownloadedTrack> FindDownloaded(IEnumerable<string> videoIds)
    {
        var ids = videoIds.Distinct().ToArray();
        var inLibrary = FindItems(ids)
            .Select(item => (VideoId: item.GetProviderId(YouTubeMusicId.Key)!, Item: item))
            .DistinctBy(entry => entry.VideoId)
            .ToDictionary(entry => entry.VideoId, entry => entry.Item);
        return ids
            .Select(id => inLibrary.TryGetValue(id, out var item) ? FromItem(id, item) : FromPending(id))
            .OfType<DownloadedTrack>()
            .ToList();
    }

    /// <summary>Returns the path of the downloaded file of <paramref name="videoId"/>, or null if it isn't downloaded.</summary>
    public string? FindPath(string videoId) =>
        FindItems([videoId]).Select(item => item.Path).FirstOrDefault(File.Exists) ?? PendingPath(videoId);

    /// <summary>Whether the file at <paramref name="path"/> is a download of <paramref name="videoId"/>, going by its tags.</summary>
    public bool IsDownloadOf(string path, string videoId)
    {
        try
        {
            return YouTubeMusicId.Read(path) == videoId;
        }
        catch (Exception e)
        {
            _logger.LogDebug(e, "Could not read the tags of {Path}", path);
            return false;
        }
    }

    /// <summary>Records a finished download, until the library scan picks it up.</summary>
    public void Add(string videoId, string path) => _pending[videoId] = path;

    private IReadOnlyList<BaseItem> FindItems(string[] videoIds)
    {
        MigrateLegacyIndex();
        if (videoIds.Length == 0)
        {
            return [];
        }

        var items = _libraryManager.GetItemList(new InternalItemsQuery
        {
            IncludeItemTypes = [BaseItemKind.Audio],
            Recursive = true,
            HasAnyProviderIds = new Dictionary<string, string[]> { [YouTubeMusicId.Key] = videoIds },
        });
        foreach (var item in items)
        {
            if (item.GetProviderId(YouTubeMusicId.Key) is { } id)
            {
                _pending.TryRemove(id, out _);
            }
        }

        return items;
    }

    private static DownloadedTrack FromItem(string videoId, BaseItem item)
    {
        var stream = item.GetMediaStreams().FirstOrDefault(stream => stream.Type == MediaStreamType.Audio);
        // Not every format reports the stream's (e.g. Opus); the file's is close enough
        var bitRate = stream?.BitRate > 0 ? stream.BitRate : item.TotalBitrate;
        return new DownloadedTrack(videoId, CodecName(stream?.Codec), bitRate > 0 ? (int)Math.Round(bitRate.Value / 1000.0) : null);
    }

    /// <summary>Maps ffprobe codec names to display names as in <see cref="AudioStreamInfo"/>.</summary>
    internal static string? CodecName(string? codec) => codec?.ToLowerInvariant() switch
    {
        null or "" => null,
        "opus" => "Opus",
        "vorbis" => "Vorbis",
        "flac" => "FLAC",
        "mp3" => "MP3",
        "alac" => "ALAC",
        "aac" => "AAC",
        var other => other.ToUpperInvariant(),
    };

    private string? PendingPath(string videoId)
    {
        if (!_pending.TryGetValue(videoId, out var path))
        {
            return null;
        }

        if (File.Exists(path))
        {
            return path;
        }

        _pending.TryRemove(videoId, out _);
        return null;
    }

    private DownloadedTrack? FromPending(string videoId)
    {
        var path = PendingPath(videoId);
        if (path == null)
        {
            return null;
        }

        try
        {
            using var file = TagLib.File.Create(path, TagLib.ReadStyle.Average);
            var properties = file.Properties;
            var codec = properties?.Codecs.FirstOrDefault(codec => codec != null)?.Description switch
            {
                { } description when description.Contains("Opus", StringComparison.OrdinalIgnoreCase) => "Opus",
                { } description when description.Contains("Vorbis", StringComparison.OrdinalIgnoreCase) => "Vorbis",
                { } description when description.Contains("MPEG-4", StringComparison.OrdinalIgnoreCase) || description.Contains("AAC", StringComparison.OrdinalIgnoreCase) => "AAC",
                _ => null,
            };
            // Opus doesn't report one; the average without the embedded cover is close enough
            int? bitRate = properties?.AudioBitrate > 0 ? properties.AudioBitrate : null;
            if (bitRate == null && properties?.Duration.TotalSeconds > 1)
            {
                var audioBytes = new FileInfo(path).Length - file.Tag.Pictures.Sum(picture => (long)(picture.Data?.Count ?? 0));
                bitRate = (int)Math.Round(audioBytes * 8 / properties.Duration.TotalSeconds / 1000);
            }

            return new DownloadedTrack(videoId, codec, bitRate);
        }
        catch (Exception e)
        {
            _logger.LogDebug(e, "Could not read the audio properties of {Path}", path);
            return new DownloadedTrack(videoId, null, null);
        }
    }

    /// <summary>
    /// Earlier plugin versions kept their own index of the download folder. Jellyfin only runs
    /// <see cref="YouTubeMusicIdProvider"/> on new and changed files, so the tracks in that index get their provider id
    /// from it, once, in the background; lookups may miss them until then.
    /// </summary>
    private void MigrateLegacyIndex()
    {
        if (Interlocked.Exchange(ref _migrationStarted, 1) == 1 || !File.Exists(_legacyIndexPath))
        {
            return;
        }

        _ = Task.Run(async () =>
        {
            try
            {
                var entries = JsonSerializer.Deserialize<Dictionary<string, LegacyEntry>>(await File.ReadAllTextAsync(_legacyIndexPath).ConfigureAwait(false)) ?? [];
                var linked = 0;
                var missing = 0;
                foreach (var (path, entry) in entries.Where(entry => entry.Value.VideoId != null))
                {
                    if (_libraryManager.FindByPath(path, false) is not { } item)
                    {
                        missing++;
                        continue;
                    }

                    if (item.GetProviderId(YouTubeMusicId.Key) != entry.VideoId)
                    {
                        item.SetProviderId(YouTubeMusicId.Key, entry.VideoId!);
                        await item.UpdateToRepositoryAsync(ItemUpdateType.MetadataImport, CancellationToken.None).ConfigureAwait(false);
                        linked++;
                    }
                }

                File.Delete(_legacyIndexPath);
                _logger.LogInformation(
                    "Moved the YouTube Music ids of {Count} tracks from {Path} into the library ({Missing} files weren't in it)",
                    linked,
                    _legacyIndexPath,
                    missing);
            }
            catch (Exception e)
            {
                _logger.LogWarning(e, "Could not move the YouTube Music ids from {Path} into the library", _legacyIndexPath);
            }
        });
    }

    private sealed record LegacyEntry(string? VideoId);
}

/// <summary>A downloaded YouTube Music track; codec display name and bitrate in kbps as in <see cref="AudioStreamInfo"/>.</summary>
public record DownloadedTrack(string VideoId, string? Codec, int? BitRate);
