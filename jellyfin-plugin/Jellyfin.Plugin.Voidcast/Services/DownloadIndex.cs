using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using MediaBrowser.Common.Configuration;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Voidcast.Services;

/// <summary>
/// Knows which YouTube Music tracks are in the download folder. Downloads carry their watch URL in the comment tag,
/// which Jellyfin doesn't store, so the index reads the tags itself: once for all files, then only for new or changed
/// ones. It is saved, so restarts don't read every file again. It also keeps the files' codec and bitrate.
/// </summary>
public partial class DownloadIndex
{
    private static readonly TimeSpan RescanInterval = TimeSpan.FromMinutes(10);
    /// <summary>Raise when the format is read differently, so existing entries get read again.</summary>
    private const int FormatVersion = 1;
    private static readonly HashSet<string> AudioExtensions =
        [".opus", ".ogg", ".oga", ".m4a", ".mp4", ".webm", ".mp3", ".flac"];

    private readonly ILogger<DownloadIndex> _logger;
    private readonly string _indexPath;
    private readonly object _lock = new();
    private Dictionary<string, Entry>? _entries;
    private Dictionary<string, string> _pathsByVideoId = [];
    private DateTime _lastScan = DateTime.MinValue;
    private Task? _scan;

    public DownloadIndex(ILogger<DownloadIndex> logger, IApplicationPaths applicationPaths)
    {
        _logger = logger;
        _indexPath = Path.Combine(applicationPaths.DataPath, "voidcast", "download-index.json");
    }

    /// <summary>
    /// Returns those of <paramref name="videoIds"/> whose tracks are in <paramref name="directory"/>. Rescans it in
    /// the background if the last scan is a while ago, so the very first lookups may miss earlier downloads.
    /// </summary>
    public IReadOnlyList<DownloadedTrack> FindDownloaded(IEnumerable<string> videoIds, string? directory)
    {
        if (directory != null)
        {
            RescanIfStale(directory);
        }

        lock (_lock)
        {
            Load();
            return videoIds.Distinct()
                .Where(id => _pathsByVideoId.TryGetValue(id, out var path) && File.Exists(path))
                .Select(id =>
                {
                    var entry = _entries![_pathsByVideoId[id]];
                    return new DownloadedTrack(id, entry.Codec, entry.BitRate);
                })
                .ToList();
        }
    }

    /// <summary>Returns the path of the downloaded file of <paramref name="videoId"/>, or null if it isn't downloaded.</summary>
    public string? FindPath(string videoId)
    {
        lock (_lock)
        {
            Load();
            return _pathsByVideoId.TryGetValue(videoId, out var path) && File.Exists(path) ? path : null;
        }
    }

    /// <summary>Whether the file at <paramref name="path"/> is a download of <paramref name="videoId"/>, going by its tags.</summary>
    public bool IsDownloadOf(string path, string videoId) => ReadEntry(path, 0).VideoId == videoId;

    /// <summary>Records a finished download.</summary>
    public void Add(string videoId, string path)
    {
        lock (_lock)
        {
            Load();
            _entries![path] = ReadFormat(path, new Entry(videoId, File.GetLastWriteTimeUtc(path).Ticks));
            _pathsByVideoId[videoId] = path;
            Save();
        }
    }

    public void RescanIfStale(string directory)
    {
        lock (_lock)
        {
            if (_scan is { IsCompleted: false } || DateTime.UtcNow - _lastScan < RescanInterval)
            {
                return;
            }

            _lastScan = DateTime.UtcNow;
            _scan = Task.Run(() => Scan(directory));
        }
    }

    private void Scan(string directory)
    {
        try
        {
            if (!Directory.Exists(directory))
            {
                return;
            }

            Dictionary<string, Entry> known;
            lock (_lock)
            {
                Load();
                known = new Dictionary<string, Entry>(_entries!);
            }

            var found = new Dictionary<string, Entry>();
            var read = 0;
            var files = Directory.EnumerateFiles(directory, "*", new EnumerationOptions { RecurseSubdirectories = true, IgnoreInaccessible = true })
                .Where(path => AudioExtensions.Contains(Path.GetExtension(path).ToLowerInvariant()));
            foreach (var path in files)
            {
                var modified = File.GetLastWriteTimeUtc(path).Ticks;
                // Entries of older plugin versions lack the format or have it read differently
                if (known.TryGetValue(path, out var entry) && entry.Modified == modified && (entry.VideoId == null || entry.Format == FormatVersion))
                {
                    found[path] = entry;
                    continue;
                }

                found[path] = ReadEntry(path, modified);
                read++;
            }

            lock (_lock)
            {
                // Keep downloads added while scanning
                foreach (var (path, entry) in _entries!.Where(e => !found.ContainsKey(e.Key) && File.Exists(e.Key)))
                {
                    found[path] = entry;
                }

                _entries = found;
                Reindex();
                Save();
            }

            if (read > 0)
            {
                _logger.LogInformation("Read the tags of {Count} files in {Directory}, {Tracks} YouTube Music tracks known", read, directory, _pathsByVideoId.Count);
            }
        }
        catch (Exception e)
        {
            _logger.LogWarning(e, "Scanning {Directory} for YouTube Music downloads failed", directory);
        }
    }

    private Entry ReadEntry(string path, long modified)
    {
        try
        {
            using var file = TagLib.File.Create(path, TagLib.ReadStyle.Average);
            var comment = file.Tag.Comment;
            var videoId = comment != null && WatchUrlRegex().Match(comment) is { Success: true } match ? match.Groups[1].Value : null;
            return videoId == null ? new Entry(null, modified) : Format(file, path, new Entry(videoId, modified));
        }
        catch (Exception e)
        {
            _logger.LogDebug(e, "Could not read the tags of {Path}", path);
            return new Entry(null, modified);
        }
    }

    private Entry ReadFormat(string path, Entry entry)
    {
        try
        {
            using var file = TagLib.File.Create(path, TagLib.ReadStyle.Average);
            return Format(file, path, entry);
        }
        catch (Exception e)
        {
            _logger.LogDebug(e, "Could not read the audio properties of {Path}", path);
            return entry;
        }
    }

    /// <summary>Adds codec display name (as in <see cref="AudioStreamInfo"/>) and bitrate in kbps.</summary>
    private static Entry Format(TagLib.File file, string path, Entry entry)
    {
        var properties = file.Properties;
        var description = properties?.Codecs.FirstOrDefault(codec => codec != null)?.Description ?? string.Empty;
        var codec = description switch
        {
            _ when description.Contains("Opus", StringComparison.OrdinalIgnoreCase) => "Opus",
            _ when description.Contains("Vorbis", StringComparison.OrdinalIgnoreCase) => "Vorbis",
            _ when description.Contains("Flac", StringComparison.OrdinalIgnoreCase) => "FLAC",
            _ when description.Contains("Layer 3", StringComparison.OrdinalIgnoreCase) => "MP3",
            _ when description.Contains("ALAC", StringComparison.OrdinalIgnoreCase) => "ALAC",
            _ when description.Contains("MPEG-4", StringComparison.OrdinalIgnoreCase) || description.Contains("AAC", StringComparison.OrdinalIgnoreCase) => "AAC",
            _ => Path.GetExtension(path).TrimStart('.').ToUpperInvariant() switch
            {
                "OPUS" or "WEBM" => "Opus",
                "M4A" or "MP4" => "AAC",
                var extension => extension,
            },
        };

        // Not every format reports it (e.g. Opus); the average without the embedded cover is close enough
        int? bitRate = properties?.AudioBitrate > 0 ? properties.AudioBitrate : null;
        if (bitRate == null && properties?.Duration.TotalSeconds > 1)
        {
            var audioBytes = new FileInfo(path).Length - file.Tag.Pictures.Sum(picture => (long)(picture.Data?.Count ?? 0));
            bitRate = (int)Math.Round(audioBytes * 8 / properties.Duration.TotalSeconds / 1000);
        }

        return entry with { Codec = codec, BitRate = bitRate, Format = FormatVersion };
    }

    // Callers hold _lock
    private void Load()
    {
        if (_entries != null)
        {
            return;
        }

        try
        {
            _entries = File.Exists(_indexPath)
                ? JsonSerializer.Deserialize<Dictionary<string, Entry>>(File.ReadAllText(_indexPath)) ?? []
                : [];
        }
        catch (Exception e) when (e is IOException or JsonException)
        {
            _logger.LogWarning(e, "Could not read {Path}, rebuilding it", _indexPath);
            _entries = [];
        }

        Reindex();
    }

    private void Reindex()
    {
        _pathsByVideoId = _entries!
            .Where(entry => entry.Value.VideoId != null)
            .GroupBy(entry => entry.Value.VideoId!)
            .ToDictionary(group => group.Key, group => group.First().Key);
    }

    private void Save()
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(_indexPath)!);
            var partial = _indexPath + ".part";
            File.WriteAllText(partial, JsonSerializer.Serialize(_entries));
            File.Move(partial, _indexPath, true);
        }
        catch (IOException e)
        {
            _logger.LogWarning(e, "Could not save {Path}", _indexPath);
        }
    }

    [GeneratedRegex(@"youtube\.com/watch\?(?:.*&)?v=([\w-]{11})")]
    private static partial Regex WatchUrlRegex();

    /// <summary>
    /// A file of the download folder; <see cref="VideoId"/> is null for files that aren't YouTube Music downloads,
    /// whose format isn't read.
    /// </summary>
    private sealed record Entry(string? VideoId, long Modified, string? Codec = null, int? BitRate = null, int Format = 0);
}

/// <summary>A downloaded YouTube Music track; codec display name and bitrate in kbps as in <see cref="AudioStreamInfo"/>.</summary>
public record DownloadedTrack(string VideoId, string? Codec, int? BitRate);
