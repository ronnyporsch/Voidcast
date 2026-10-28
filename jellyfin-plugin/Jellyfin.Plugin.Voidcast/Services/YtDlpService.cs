using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using MediaBrowser.Common.Configuration;
using MediaBrowser.Controller.MediaEncoding;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Voidcast.Services;

/// <summary>Codec display name (e.g. "Opus") and bitrate in kbps of the audio stream that gets downloaded.</summary>
public record AudioStreamInfo(string? Codec, int? BitRate);

/// <summary>Codecs (display names, e.g. "H.264" and "AAC") and bitrates in kbps of the streams of a video.</summary>
public record VideoStreamInfo(string? VideoCodec, int? VideoBitRate, int? Width, int? Height, int? FrameRate, string? AudioCodec, int? AudioBitRate);

/// <summary>
/// Runs yt-dlp: the configured one, else the standalone release shipped with the plugin (Linux x64/arm64), else the
/// one on the PATH. Lets it solve YouTube's JavaScript challenges with the Deno shipped with the plugin (else a Deno
/// on the PATH); without a JavaScript runtime signed-in requests get no formats. Uses ffmpeg and ffprobe from the PATH if installed, otherwise (e.g. in the official Docker
/// image) Jellyfin's own ffmpeg.
/// </summary>
public partial class YtDlpService
{
    /// <summary>
    /// Picks the best codec/bitrate combination: YouTube's quality tier first (so e.g. a 256 kbps AAC
    /// stream beats 128 kbps Opus), then channels, then the more efficient codec, then bitrate.
    /// Same selection as the Voidcast desktop client.
    /// </summary>
    internal static readonly string[] BestAudio = ["-f", "bestaudio", "-S", "quality,channels,acodec,abr"];

    private static readonly TimeSpan PlaybackCacheLifetime = TimeSpan.FromHours(12);
    // Videos take up much more space
    private static readonly TimeSpan VideoCacheLifetime = TimeSpan.FromHours(2);
    // Beyond this, the least recently played videos get deleted
    private const long VideoCacheMaxBytes = 2L * 1024 * 1024 * 1024;
    private static readonly TimeSpan SelfUpdateInterval = TimeSpan.FromDays(1);

    private readonly ILogger<YtDlpService> _logger;
    private readonly IMediaEncoder _mediaEncoder;
    private readonly string _playbackCacheDir;
    private readonly string _denoCacheDir;
    // Each lookup spawns a yt-dlp process; don't start one per search result at once
    private readonly SemaphoreSlim _streamInfoLookups = new(4, 4);
    private readonly ConcurrentDictionary<string, AudioStreamInfo> _streamInfoCache = new();
    private readonly ConcurrentDictionary<string, IReadOnlyList<int>> _videoHeightsCache = new();
    private readonly ConcurrentDictionary<string, VideoStreamInfo> _videoInfoCache = new();
    private readonly ConcurrentDictionary<string, SemaphoreSlim> _playbackLocks = new();
    private readonly object _cookiesCopyLock = new();
    private string? _lastCookiesKey;
    private string? _cookiesCopySource;
    private DateTime _cookiesCopyModified;
    private DateTime _lastSelfUpdate = DateTime.MinValue;

    public YtDlpService(
        ILogger<YtDlpService> logger,
        IMediaEncoder mediaEncoder,
        IApplicationPaths applicationPaths)
    {
        _logger = logger;
        _mediaEncoder = mediaEncoder;
        _playbackCacheDir = Path.Combine(applicationPaths.CachePath, "voidcast");
        _denoCacheDir = Path.Combine(applicationPaths.CachePath, "voidcast-deno");
    }

    /// <summary>Gets a warning to show to users, e.g. when the configured cookies couldn't be read.</summary>
    public string? CookiesWarning { get; private set; }

    private static Configuration.PluginConfiguration Config =>
        Plugin.Instance?.Configuration ?? new Configuration.PluginConfiguration();

    public static bool IsValidVideoId(string videoId) => VideoIdRegex().IsMatch(videoId);

    /// <summary>Gets a value indicating whether yt-dlp is available.</summary>
    public static bool IsAvailable => FindExecutable() != null;

    /// <summary>Gets the yt-dlp release shipped with the plugin for this platform, or null if there is none.</summary>
    private static string? BundledPath
    {
        get
        {
            var asset = (OperatingSystem.IsLinux() ? RuntimeInformation.OSArchitecture : (Architecture?)null) switch
            {
                Architecture.X64 => "yt-dlp_linux",
                Architecture.Arm64 => "yt-dlp_linux_aarch64",
                _ => null,
            };
            var pluginDir = Path.GetDirectoryName(Plugin.Instance?.AssemblyFilePath);
            return asset == null || pluginDir == null ? null : Path.Combine(pluginDir, "yt-dlp", asset);
        }
    }

    /// <summary>Gets the Deno release shipped with the plugin for this platform, or null if there is none.</summary>
    private static string? BundledDenoPath
    {
        get
        {
            var arch = (OperatingSystem.IsLinux() ? RuntimeInformation.OSArchitecture : (Architecture?)null) switch
            {
                Architecture.X64 => "x86_64",
                Architecture.Arm64 => "aarch64",
                _ => null,
            };
            var pluginDir = Path.GetDirectoryName(Plugin.Instance?.AssemblyFilePath);
            return arch == null || pluginDir == null ? null : Path.Combine(pluginDir, "deno", "deno_" + arch);
        }
    }

    /// <summary>Returns the yt-dlp executable, or null if there is none.</summary>
    public static string? FindExecutable()
    {
        var configured = Config.YtDlpPath.Trim();
        if (configured.Length > 0)
        {
            return File.Exists(configured) ? configured : null;
        }

        var bundled = BundledPath;
        if (bundled != null && File.Exists(bundled) && EnsureExecutable(bundled))
        {
            return bundled;
        }

        return FindOnPath(OperatingSystem.IsWindows() ? "yt-dlp.exe" : "yt-dlp");
    }

    /// <summary>Zip extraction may drop the executable bit of the bundled release. Returns false if it can't be set.</summary>
    private static bool EnsureExecutable(string path)
    {
        const UnixFileMode Execute = UnixFileMode.UserExecute | UnixFileMode.GroupExecute | UnixFileMode.OtherExecute;
        if (OperatingSystem.IsWindows())
        {
            return true;
        }

        try
        {
            var mode = File.GetUnixFileMode(path);
            if ((mode & UnixFileMode.UserExecute) == 0)
            {
                File.SetUnixFileMode(path, mode | Execute);
            }

            return true;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            return false;
        }
    }

    public async Task<string?> GetVersionAsync(CancellationToken cancellationToken)
    {
        var executable = FindExecutable();
        if (executable == null)
        {
            return null;
        }

        var result = await RunProcessAsync(executable, ["--version"], cancellationToken).ConfigureAwait(false);
        return result.ExitCode == 0 ? result.Output.Trim() : null;
    }

    /// <summary>Returns codec and bitrate of the audio stream <see cref="BestAudio"/> selects, or null if unknown.</summary>
    public async Task<AudioStreamInfo?> GetAudioStreamInfoAsync(string videoId, CancellationToken cancellationToken)
    {
        RequireValidId(videoId);
        InvalidateCachesIfCookiesChanged();
        if (_streamInfoCache.TryGetValue(videoId, out var cached))
        {
            return cached;
        }

        await _streamInfoLookups.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var output = await RunAsync(
                ["--no-playlist", "--quiet", "--no-warnings", "--skip-download", .. BestAudio, "--print", "%(acodec)s %(abr)s", YouTubeMusicClient.WatchUrl(videoId)],
                cancellationToken).ConfigureAwait(false);
            var parts = output.Split('\n', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
                .FirstOrDefault()?.Split(' ');
            if (parts is not { Length: 2 })
            {
                return null;
            }

            int? bitRate = double.TryParse(parts[1], NumberStyles.Float, CultureInfo.InvariantCulture, out var abr)
                ? (int)Math.Round(abr)
                : null;
            var info = new AudioStreamInfo(CodecName(parts[0]), bitRate);
            if (info.Codec == null && info.BitRate == null)
            {
                return null;
            }

            _streamInfoCache[videoId] = info;
            return info;
        }
        finally
        {
            _streamInfoLookups.Release();
        }
    }

    /// <summary>
    /// Downloads the best audio stream of <paramref name="videoId"/> into <paramref name="directory"/> as
    /// <c>audio.&lt;ext&gt;</c>, remuxed into an audio container without re-encoding (e.g. webm -> opus),
    /// or converted when <paramref name="audioFormat"/> is given. Only the part from <paramref name="startTime"/> to
    /// <paramref name="endTime"/> (in seconds) gets downloaded if either is given. Returns the file.
    /// </summary>
    public async Task<string> DownloadAudioAsync(
        string videoId,
        string directory,
        string? audioFormat,
        CancellationToken cancellationToken,
        int? startTime = null,
        int? endTime = null)
    {
        RequireValidId(videoId);
        List<string> args = ["--no-playlist", "--quiet", "--no-warnings", .. BestAudio, "-x"];
        if (audioFormat != null)
        {
            args.AddRange(["--audio-format", audioFormat, "--audio-quality", "0"]);
        }

        if (startTime != null || endTime != null)
        {
            var start = (startTime ?? 0).ToString(CultureInfo.InvariantCulture);
            var end = endTime?.ToString(CultureInfo.InvariantCulture) ?? "inf";
            args.AddRange(["--download-sections", $"*{start}-{end}"]);
        }

        args.AddRange(["-o", Path.Combine(directory, "audio.%(ext)s"), YouTubeMusicClient.WatchUrl(videoId)]);
        await RunAsync(args, cancellationToken).ConfigureAwait(false);
        return Directory.EnumerateFiles(directory, "audio.*").SingleOrDefault()
            ?? throw new IOException("yt-dlp did not produce an audio file");
    }

    /// <summary>
    /// Returns a cached audio file of <paramref name="videoId"/> for playback (in its original codec, or as MP3 for
    /// clients that can't decode Opus/AAC), downloading it first if necessary.
    /// </summary>
    public Task<string> GetPlaybackFileAsync(string videoId, bool mp3, CancellationToken cancellationToken)
    {
        RequireValidId(videoId);
        return GetCachedFileAsync(
            videoId + (mp3 ? ".mp3" : string.Empty),
            "audio",
            dir => DownloadAudioAsync(videoId, dir, mp3 ? "mp3" : null, cancellationToken),
            cancellationToken);
    }

    /// <summary>
    /// Returns a cached MP4 of <paramref name="videoId"/> with picture and sound for playback, in the highest resolution
    /// up to <paramref name="maxHeight"/> (H.264/AAC where available, which every player decodes), downloading it first
    /// if necessary.
    /// </summary>
    public Task<string> GetVideoFileAsync(string videoId, int maxHeight, CancellationToken cancellationToken)
    {
        RequireValidId(videoId);
        maxHeight = Math.Clamp(maxHeight, 144, 4320);
        return GetCachedFileAsync(
            VideoCacheKey(videoId, maxHeight),
            "video",
            async dir =>
            {
                await RunAsync(
                    [
                        "--no-playlist", "--quiet", "--no-warnings", .. VideoFormat(maxHeight),
                        // Remembers which streams got picked, see GetVideoStreamInfoAsync
                        "--print-to-file", VideoInfoTemplate, Path.Combine(dir, VideoInfoFile),
                        "--merge-output-format", "mp4",
                        "-o", Path.Combine(dir, "video.%(ext)s"),
                        YouTubeMusicClient.WatchUrl(videoId),
                    ],
                    cancellationToken).ConfigureAwait(false);
                return Directory.EnumerateFiles(dir, "video.*").SingleOrDefault(file => !file.EndsWith(".part", StringComparison.Ordinal))
                    ?? throw new IOException("yt-dlp did not produce a video file");
            },
            cancellationToken);
    }

    /// <summary>
    /// Codecs and bitrates of the streams <see cref="GetVideoFileAsync"/> picks (or picked) for these arguments, or null
    /// if unknown. Doesn't download the video.
    /// </summary>
    public async Task<VideoStreamInfo?> GetVideoStreamInfoAsync(string videoId, int maxHeight, CancellationToken cancellationToken)
    {
        RequireValidId(videoId);
        maxHeight = Math.Clamp(maxHeight, 144, 4320);
        InvalidateCachesIfCookiesChanged();
        var key = VideoCacheKey(videoId, maxHeight);
        var recorded = Path.Combine(_playbackCacheDir, key, VideoInfoFile);
        if (File.Exists(recorded))
        {
            return ParseVideoInfo(await File.ReadAllTextAsync(recorded, cancellationToken).ConfigureAwait(false));
        }

        if (_videoInfoCache.TryGetValue(key, out var cached))
        {
            return cached;
        }

        var output = await RunAsync(
            ["--no-playlist", "--quiet", "--no-warnings", "--skip-download", .. VideoFormat(maxHeight), "--print", VideoInfoTemplate, YouTubeMusicClient.WatchUrl(videoId)],
            cancellationToken).ConfigureAwait(false);
        var info = ParseVideoInfo(output);
        if (info != null)
        {
            _videoInfoCache[key] = info;
        }

        return info;
    }

    private static string VideoCacheKey(string videoId, int maxHeight) =>
        videoId + ".video" + maxHeight.ToString(CultureInfo.InvariantCulture);

    /// <summary>The highest resolution up to <paramref name="maxHeight"/>, preferring H.264/AAC (which every player decodes).</summary>
    private static string[] VideoFormat(int maxHeight) =>
        ["-f", "bv*+ba/b", "-S", $"res:{maxHeight.ToString(CultureInfo.InvariantCulture)},vcodec:h264,acodec:aac"];

    private const string VideoInfoFile = "format.txt";
    private const string VideoInfoTemplate = "%(vcodec)s|%(vbr)s|%(tbr)s|%(width)s|%(height)s|%(fps)s|%(acodec)s|%(abr)s";

    private static VideoStreamInfo? ParseVideoInfo(string output)
    {
        var parts = output.Split('\n', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .FirstOrDefault()?.Split('|');
        if (parts is not { Length: 8 })
        {
            return null;
        }

        static int? Number(string text) =>
            double.TryParse(text, NumberStyles.Float, CultureInfo.InvariantCulture, out var value) && value > 0 ? (int)Math.Round(value) : null;
        var audioBitRate = Number(parts[7]);
        // Not all formats report the video bitrate; the total minus the audio is close
        var videoBitRate = Number(parts[1]) ?? (Number(parts[2]) is { } total ? total - (audioBitRate ?? 0) : null);
        return new VideoStreamInfo(
            VideoCodecName(parts[0]),
            videoBitRate > 0 ? videoBitRate : null,
            Number(parts[3]),
            Number(parts[4]),
            Number(parts[5]),
            CodecName(parts[6]),
            audioBitRate);
    }

    /// <summary>Maps yt-dlp video codec ids (e.g. <c>avc1.640028</c>, <c>vp09.00.40.08</c>) to display names.</summary>
    private static string? VideoCodecName(string vcodec) => vcodec switch
    {
        "none" or "NA" => null,
        _ when vcodec.StartsWith("avc", StringComparison.Ordinal) || vcodec.StartsWith("h264", StringComparison.Ordinal) => "H.264",
        _ when vcodec.StartsWith("hev", StringComparison.Ordinal) || vcodec.StartsWith("hvc", StringComparison.Ordinal) || vcodec.StartsWith("h265", StringComparison.Ordinal) => "H.265",
        _ when vcodec.StartsWith("vp09", StringComparison.Ordinal) || vcodec == "vp9" => "VP9",
        _ when vcodec.StartsWith("av01", StringComparison.Ordinal) => "AV1",
        _ when vcodec.StartsWith("vp8", StringComparison.Ordinal) => "VP8",
        _ => vcodec.Split('.')[0].ToUpperInvariant(),
    };

    /// <summary>Video heights (e.g. 1080) <paramref name="videoId"/> is available in, highest first.</summary>
    public async Task<IReadOnlyList<int>> GetVideoHeightsAsync(string videoId, CancellationToken cancellationToken)
    {
        RequireValidId(videoId);
        InvalidateCachesIfCookiesChanged();
        if (_videoHeightsCache.TryGetValue(videoId, out var cached))
        {
            return cached;
        }

        var output = await RunAsync(
            ["--no-playlist", "--quiet", "--no-warnings", "--skip-download", "--print", "%(formats)j", YouTubeMusicClient.WatchUrl(videoId)],
            cancellationToken).ConfigureAwait(false);
        var formats = JsonNode.Parse(output.Trim()) as JsonArray ?? [];
        var heights = formats
            .Where(format => format?["vcodec"]?.GetValue<string>() is { } vcodec && vcodec != "none"
                // Storyboards (thumbnail sheets for seeking) have a height too
                && format["protocol"]?.GetValue<string>() != "mhtml")
            .Select(format => format?["height"] is JsonValue height && height.TryGetValue<int>(out var value) ? value : 0)
            .Where(height => height > 0)
            .Distinct()
            .OrderDescending()
            .ToList();
        _videoHeightsCache[videoId] = heights;
        return heights;
    }

    /// <summary>
    /// Returns the file named <paramref name="name"/>.* that <paramref name="download"/> put into the playback cache folder
    /// <paramref name="key"/>, running it first if there is none.
    /// </summary>
    private async Task<string> GetCachedFileAsync(string key, string name, Func<string, Task<string>> download, CancellationToken cancellationToken)
    {
        var semaphore = _playbackLocks.GetOrAdd(key, _ => new SemaphoreSlim(1, 1));
        await semaphore.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var dir = Path.Combine(_playbackCacheDir, key);
            var existing = Directory.Exists(dir) ? Directory.EnumerateFiles(dir, name + ".*").FirstOrDefault() : null;
            if (existing != null && !existing.EndsWith(".part", StringComparison.Ordinal))
            {
                Directory.SetLastWriteTimeUtc(dir, DateTime.UtcNow);
                return existing;
            }

            CleanUpPlaybackCache();
            if (Directory.Exists(dir))
            {
                Directory.Delete(dir, true);
            }

            Directory.CreateDirectory(dir);
            try
            {
                var file = await download(dir).ConfigureAwait(false);
                if (key.Contains(".video", StringComparison.Ordinal))
                {
                    LimitVideoCacheSize(keep: dir);
                }

                return file;
            }
            catch
            {
                Directory.Delete(dir, true);
                throw;
            }
        }
        finally
        {
            semaphore.Release();
        }
    }

    /// <summary>Runs yt-dlp with <paramref name="args"/>, signed in via the configured cookies if possible.</summary>
    public async Task<string> RunAsync(IReadOnlyList<string> args, CancellationToken cancellationToken)
    {
        var executable = FindExecutable() ?? throw new InvalidOperationException(
            Config.YtDlpPath.Trim().Length > 0 ? $"yt-dlp not found at {Config.YtDlpPath}" : "yt-dlp is not installed on the server");
        if (executable == BundledPath)
        {
            SelfUpdateInBackground(executable);
        }

        args = [.. FfmpegArguments(), .. JsRuntimeArguments(), .. args];
        var (cookieArgs, cookiesSource, cookiesProblem) = CookiesArguments();
        if (cookiesProblem != null)
        {
            CookiesWarning = cookiesProblem;
            _logger.LogWarning("{Warning}", CookiesWarning);
        }
        else if (cookieArgs != null)
        {
            var signedIn = await RunProcessAsync(executable, [.. cookieArgs, .. args], cancellationToken)
                .ConfigureAwait(false);
            if (signedIn.ExitCode == 0)
            {
                CookiesWarning = null;
                return signedIn.Output;
            }

            if (IsCookieError(signedIn.Error))
            {
                // Don't fail every request because e.g. the browser profile isn't mounted into the container
                CookiesWarning = $"Could not read {cookiesSource}, downloading without signing in: {LastLine(signedIn.Error, signedIn.ExitCode)}";
            }
            else if (signedIn.Error.Contains("Requested format is not available", StringComparison.Ordinal))
            {
                // Signed in, yt-dlp uses YouTube clients whose formats need a JavaScript runtime
                CookiesWarning = "YouTube offered no usable formats when signed in (yt-dlp needs a JavaScript runtime such as Deno for that), downloading without signing in";
            }
            else
            {
                throw new IOException("yt-dlp failed: " + LastLine(signedIn.Error, signedIn.ExitCode));
            }

            _logger.LogWarning("{Warning}", CookiesWarning);
        }

        var result = await RunProcessAsync(executable, args, cancellationToken).ConfigureAwait(false);
        if (result.ExitCode != 0)
        {
            throw new IOException("yt-dlp failed: " + LastLine(result.Error, result.ExitCode));
        }

        return result.Output;
    }

    /// <summary>Points yt-dlp at the bundled Deno; a Deno on the PATH it finds by itself.</summary>
    private static string[] JsRuntimeArguments()
    {
        var deno = BundledDenoPath;
        return deno != null && File.Exists(deno) && EnsureExecutable(deno) ? ["--js-runtimes", "deno:" + deno] : [];
    }

    /// <summary>Points yt-dlp at Jellyfin's ffmpeg (and the ffprobe next to it) unless both are on the PATH.</summary>
    private string[] FfmpegArguments()
    {
        string Exe(string name) => OperatingSystem.IsWindows() ? name + ".exe" : name;
        var ffmpeg = _mediaEncoder.EncoderPath;
        if ((FindOnPath(Exe("ffmpeg")) != null && FindOnPath(Exe("ffprobe")) != null) || string.IsNullOrEmpty(ffmpeg))
        {
            return [];
        }

        return ["--ffmpeg-location", Path.GetDirectoryName(ffmpeg) ?? ffmpeg];
    }

    /// <summary>
    /// Returns the yt-dlp arguments for the configured cookies (the cookies file, else the browser) and a description
    /// of where they come from, or a problem to warn about if the cookies file can't be used.
    /// </summary>
    private (string[]? Args, string? Source, string? Problem) CookiesArguments()
    {
        var file = Config.CookiesFile.Trim();
        if (file.Length > 0)
        {
            if (!File.Exists(file))
            {
                return (null, null, $"Cookies file {file} not found, downloading without signing in");
            }

            try
            {
                return (["--cookies", WritableCookiesCopy(file)], "cookies file " + file, null);
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException)
            {
                return (null, null, $"Could not read cookies file {file}, downloading without signing in: {e.Message}");
            }
        }

        var browser = Config.CookiesFromBrowser.Trim();
        if (browser.Length == 0)
        {
            return (null, null, null);
        }

        var profile = Config.BrowserProfile.Trim();
        return (["--cookies-from-browser", profile.Length > 0 ? browser + ":" + profile : browser], browser + " cookies", null);
    }

    /// <summary>
    /// yt-dlp saves the cookie jar back to its <c>--cookies</c> file (and fails on a read-only mount), so it gets a copy
    /// in the cache, refreshed whenever the original changes.
    /// </summary>
    private string WritableCookiesCopy(string file)
    {
        var copy = Path.Combine(_playbackCacheDir, "cookies.txt");
        lock (_cookiesCopyLock)
        {
            var modified = File.GetLastWriteTimeUtc(file);
            if (file != _cookiesCopySource || modified != _cookiesCopyModified || !File.Exists(copy))
            {
                Directory.CreateDirectory(_playbackCacheDir);
                File.Copy(file, copy + ".tmp", true);
                File.Move(copy + ".tmp", copy, true);
                _cookiesCopySource = file;
                _cookiesCopyModified = modified;
            }
        }

        return copy;
    }

    /// <summary>Identifies the configured cookies, to notice when they change.</summary>
    private static string CookiesKey()
    {
        var file = Config.CookiesFile.Trim();
        if (file.Length > 0)
        {
            return "file:" + file + (File.Exists(file) ? ":" + File.GetLastWriteTimeUtc(file).Ticks.ToString(CultureInfo.InvariantCulture) : string.Empty);
        }

        return "browser:" + Config.CookiesFromBrowser.Trim() + ":" + Config.BrowserProfile.Trim();
    }

    private static bool IsCookieError(string error) =>
        error.Contains("cookies database", StringComparison.OrdinalIgnoreCase)
        || error.Contains("cookie database", StringComparison.OrdinalIgnoreCase)
        || error.Contains("cookies from browser", StringComparison.OrdinalIgnoreCase)
        || error.Contains("unsupported browser", StringComparison.OrdinalIgnoreCase)
        || error.Contains("failed to decrypt", StringComparison.OrdinalIgnoreCase)
        || error.Contains("netscape format", StringComparison.OrdinalIgnoreCase)
        || error.Contains("cookies file", StringComparison.OrdinalIgnoreCase);

    private void InvalidateCachesIfCookiesChanged()
    {
        var key = CookiesKey();
        if (key != _lastCookiesKey)
        {
            // Signed in, other (e.g. Premium) streams may be available
            _lastCookiesKey = key;
            _streamInfoCache.Clear();
            _videoHeightsCache.Clear();
            _videoInfoCache.Clear();
        }
    }

    private void SelfUpdateInBackground(string executable)
    {
        if (DateTime.UtcNow - _lastSelfUpdate < SelfUpdateInterval)
        {
            return;
        }

        _lastSelfUpdate = DateTime.UtcNow;
        // YouTube changes often break older yt-dlp versions; only the standalone release we ship can update itself
        _ = Task.Run(async () =>
        {
            try
            {
                var result = await RunProcessAsync(executable, ["-U"], CancellationToken.None).ConfigureAwait(false);
                _logger.LogInformation("yt-dlp self-update: {Output}", LastLine(result.Output, result.ExitCode));
            }
            catch (Exception e)
            {
                _logger.LogWarning(e, "yt-dlp self-update failed");
            }
        });
    }

    private void CleanUpPlaybackCache()
    {
        if (!Directory.Exists(_playbackCacheDir))
        {
            return;
        }

        foreach (var dir in Directory.EnumerateDirectories(_playbackCacheDir))
        {
            try
            {
                var lifetime = Path.GetFileName(dir).Contains(".video", StringComparison.Ordinal) ? VideoCacheLifetime : PlaybackCacheLifetime;
                if (DateTime.UtcNow - Directory.GetLastWriteTimeUtc(dir) > lifetime)
                {
                    Directory.Delete(dir, true);
                }
            }
            catch (IOException e)
            {
                _logger.LogDebug(e, "Could not delete {Dir}", dir);
            }
        }
    }

    /// <summary>
    /// Deletes the least recently played videos until all of them fit into <see cref="VideoCacheMaxBytes"/>, except
    /// <paramref name="keep"/> (just downloaded) and ones still downloading.
    /// </summary>
    private void LimitVideoCacheSize(string keep)
    {
        var videos = Directory.EnumerateDirectories(_playbackCacheDir)
            .Where(dir => Path.GetFileName(dir).Contains(".video", StringComparison.Ordinal))
            .Select(dir => new DirectoryInfo(dir))
            .OrderByDescending(dir => dir.LastWriteTimeUtc)
            .ToList();
        long total = 0;
        foreach (var dir in videos)
        {
            long size;
            try
            {
                size = dir.EnumerateFiles("*", SearchOption.AllDirectories).Sum(file => file.Length);
            }
            catch (IOException)
            {
                continue;
            }

            total += size;
            var downloading = _playbackLocks.TryGetValue(dir.Name, out var semaphore) && semaphore.CurrentCount == 0;
            if (total <= VideoCacheMaxBytes || dir.FullName == Path.GetFullPath(keep) || downloading)
            {
                continue;
            }

            try
            {
                // Clients still streaming it keep their open file
                dir.Delete(true);
                total -= size;
                _logger.LogDebug("Deleted cached video {Dir} ({Size} bytes) to stay below the cache limit", dir.Name, size);
            }
            catch (IOException e)
            {
                _logger.LogDebug(e, "Could not delete {Dir}", dir.FullName);
            }
        }
    }

    private async Task<(int ExitCode, string Output, string Error)> RunProcessAsync(
        string executable,
        IEnumerable<string> args,
        CancellationToken cancellationToken)
    {
        var startInfo = new ProcessStartInfo(executable)
        {
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true,
        };
        // Deno's cache would default to the home folder, which may not be writable for the user Jellyfin runs as
        startInfo.Environment["DENO_DIR"] = _denoCacheDir;
        foreach (var arg in args)
        {
            startInfo.ArgumentList.Add(arg);
        }

        using var process = Process.Start(startInfo) ?? throw new IOException("Could not start " + executable);
        try
        {
            var output = process.StandardOutput.ReadToEndAsync(cancellationToken);
            var error = process.StandardError.ReadToEndAsync(cancellationToken);
            await process.WaitForExitAsync(cancellationToken).ConfigureAwait(false);
            return (process.ExitCode, await output.ConfigureAwait(false), await error.ConfigureAwait(false));
        }
        finally
        {
            if (!process.HasExited)
            {
                process.Kill(true);
            }
        }
    }

    private static string LastLine(string output, int exitCode) =>
        output.Split('\n', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries).LastOrDefault()
        ?? "exit code " + exitCode.ToString(CultureInfo.InvariantCulture);

    /// <summary>Maps yt-dlp codec ids (e.g. <c>mp4a.40.2</c>, <c>opus</c>) to display names.</summary>
    private static string? CodecName(string acodec) => acodec switch
    {
        "none" or "NA" => null,
        _ when acodec.StartsWith("mp4a", StringComparison.Ordinal) => "AAC",
        "opus" => "Opus",
        "vorbis" => "Vorbis",
        _ when acodec.StartsWith("mp3", StringComparison.Ordinal) => "MP3",
        _ when acodec.StartsWith("ac-3", StringComparison.Ordinal) => "AC-3",
        _ when acodec.StartsWith("ec-3", StringComparison.Ordinal) => "E-AC-3",
        _ => acodec.ToUpperInvariant(),
    };

    private static string? FindOnPath(string executable) =>
        (Environment.GetEnvironmentVariable("PATH") ?? string.Empty)
            .Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries)
            .Select(dir => Path.Combine(dir, executable))
            .FirstOrDefault(File.Exists);

    private static void RequireValidId(string videoId)
    {
        if (!IsValidVideoId(videoId))
        {
            throw new ArgumentException("Invalid YouTube video id: " + videoId, nameof(videoId));
        }
    }

    [GeneratedRegex("^[A-Za-z0-9_-]{1,64}$")]
    private static partial Regex VideoIdRegex();
}
