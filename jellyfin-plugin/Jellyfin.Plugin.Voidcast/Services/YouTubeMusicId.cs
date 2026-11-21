using System;
using System.Collections.Generic;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Entities.Audio;
using MediaBrowser.Controller.Library;
using MediaBrowser.Controller.Providers;
using MediaBrowser.Model.Entities;
using MediaBrowser.Model.Providers;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Voidcast.Services;

/// <summary>
/// The YouTube Music video id of library tracks, kept by Jellyfin as provider id <see cref="Key"/>. Downloads carry
/// their watch URL in the comment tag, which Jellyfin doesn't store; <see cref="YouTubeMusicIdProvider"/> reads it
/// when Jellyfin scans a file.
/// </summary>
public static partial class YouTubeMusicId
{
    /// <summary>The provider id key, also what clients read from an item's <c>ProviderIds</c>.</summary>
    public const string Key = "YouTubeMusic";

    /// <summary>Returns the video id in the comment tag of the file at <paramref name="path"/>, or null if it has none.</summary>
    public static string? Read(string path)
    {
        using var file = TagLib.File.Create(path, TagLib.ReadStyle.None);
        var comment = file.Tag.Comment;
        return comment != null && WatchUrlRegex().Match(comment) is { Success: true } match ? match.Groups[1].Value : null;
    }

    [GeneratedRegex(@"youtube\.com/watch\?(?:.*&)?v=([\w-]{11})")]
    private static partial Regex WatchUrlRegex();
}

/// <summary>
/// Sets <see cref="YouTubeMusicId.Key"/> from the comment tag whenever Jellyfin refreshes a track's metadata (new and
/// changed files on library scans). Forced, so it runs whatever the library's metadata settings.
/// </summary>
public class YouTubeMusicIdProvider : ICustomMetadataProvider<Audio>, IForcedProvider
{
    private readonly ILogger<YouTubeMusicIdProvider> _logger;

    public YouTubeMusicIdProvider(ILogger<YouTubeMusicIdProvider> logger)
    {
        _logger = logger;
    }

    public string Name => "Voidcast YouTube Music id";

    public Task<ItemUpdateType> FetchAsync(Audio item, MetadataRefreshOptions options, CancellationToken cancellationToken)
    {
        string? videoId;
        try
        {
            videoId = YouTubeMusicId.Read(item.Path);
        }
        catch (Exception e)
        {
            _logger.LogDebug(e, "Could not read the tags of {Path}", item.Path);
            return Task.FromResult(ItemUpdateType.None);
        }

        var current = item.GetProviderId(YouTubeMusicId.Key);
        if (videoId == current)
        {
            return Task.FromResult(ItemUpdateType.None);
        }

        if (videoId == null)
        {
            // The comment got edited
            item.RemoveProviderId(YouTubeMusicId.Key);
        }
        else
        {
            item.SetProviderId(YouTubeMusicId.Key, videoId);
        }

        return Task.FromResult(ItemUpdateType.MetadataImport);
    }
}

/// <summary>Lists the id among a track's external ids in Jellyfin's metadata editor.</summary>
public class YouTubeMusicExternalId : IExternalId
{
    public string ProviderName => "YouTube Music";

    public string Key => YouTubeMusicId.Key;

    public ExternalIdMediaType? Type => ExternalIdMediaType.Track;

    public bool Supports(IHasProviderIds item) => item is Audio;
}

/// <summary>Links tracks with the id to YouTube Music, e.g. in Jellyfin's web client.</summary>
public class YouTubeMusicExternalUrlProvider : IExternalUrlProvider
{
    public string Name => "YouTube Music";

    public IEnumerable<string> GetExternalUrls(BaseItem item)
    {
        if (item is Audio && item.GetProviderId(YouTubeMusicId.Key) is { } videoId && YtDlpService.IsValidVideoId(videoId))
        {
            yield return YouTubeMusicClient.WatchUrl(videoId);
        }
    }
}
