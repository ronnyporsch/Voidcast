using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Net.Http;
using System.Net.Mime;
using System.Text;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Voidcast.Api;
using MediaBrowser.Common.Net;

namespace Jellyfin.Plugin.Voidcast.Services;

public record YouTubeAlbumTrack(
    string? VideoId,
    string Title,
    int? TrackNumber,
    /// <summary>Track artist credit, empty if the album lists none (i.e. it's the album artist).</summary>
    string Artist,
    IReadOnlyList<YouTubeArtistDto> Artists,
    /// <summary>In seconds, 0 if unknown.</summary>
    int Duration,
    /// <summary>Identifies the track within the album's playlist, see <see cref="YouTubeMusicClient.GetAlbumAsync"/>.</summary>
    string? PlaylistSetVideoId,
    /// <summary>Whether <see cref="VideoId"/> is a music video rather than the audio track.</summary>
    bool IsVideo);

public record YouTubeAlbum(
    string Title,
    /// <summary>Artist credit as displayed, e.g. "Daft Punk, Pharrell Williams &amp; Nile Rodgers".</summary>
    string Artist,
    IReadOnlyList<YouTubeArtistDto> Artists,
    /// <summary>As displayed by YouTube Music, e.g. "Album", "Single" or "EP".</summary>
    string? ReleaseType,
    int? Year,
    int? TrackCount,
    string? ThumbnailUrl,
    IReadOnlyList<YouTubeAlbumTrack> Tracks);

/// <summary>
/// Minimal client for the YouTube Music web (InnerTube) API: searching songs and artists, browsing artist pages and
/// looking up albums (for browsing and tagging).
/// </summary>
public partial class YouTubeMusicClient
{
    private const string BaseUrl = "https://music.youtube.com";
    private const string ClientVersion = "1.20260101.01.00";
    // Restricts search results to the "Songs" shelf
    private const string SongsFilterParams = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D";
    // Restricts search results to the "Videos" shelf
    private const string VideosFilterParams = "EgWKAQIQAWoKEAkQBRAKEAMQBA%3D%3D";
    // Restricts search results to the "Artists" shelf
    private const string ArtistsFilterParams = "EgWKAQIgAWoKEAkQBRAKEAMQBA%3D%3D";
    // Titles of the artist page shelves listing the artist's own releases (the client requests English)
    private static readonly HashSet<string> DiscographyShelves = ["Albums", "Singles", "Singles & EPs", "EPs"];
    private static readonly HashSet<string> JoinPhrases =
        [",", "&", "and", "x", "feat.", "feat", "ft.", "ft", "featuring", "with", "vs.", "vs"];

    private readonly IHttpClientFactory _httpClientFactory;

    public YouTubeMusicClient(IHttpClientFactory httpClientFactory)
    {
        _httpClientFactory = httpClientFactory;
    }

    public static string WatchUrl(string videoId) => BaseUrl + "/watch?v=" + videoId;

    /// <summary>YouTube thumbnail URLs encode their size (e.g. <c>=w60-h60-l90-rj</c>); request a specific one instead.</summary>
    public static string ResizeThumbnail(string url, int size) =>
        ThumbnailSizeRegex().Replace(url, $"=w{size}-h{size}");

    public static bool IsValidChannelId(string id) => ChannelIdRegex().IsMatch(id);

    public static bool IsValidAlbumId(string id) => AlbumIdRegex().IsMatch(id);

    public async Task<List<YouTubeSongDto>> SearchSongsAsync(string query, CancellationToken cancellationToken)
    {
        var items = await SearchAsync(query, SongsFilterParams, cancellationToken).ConfigureAwait(false);
        return items
            .Select(item => ParseSong(item))
            .OfType<YouTubeSongDto>()
            .DistinctBy(song => song.VideoId)
            .ToList();
    }

    /// <summary>Searches songs and (music) videos, interleaved so the best matches of both come first.</summary>
    public async Task<List<YouTubeSongDto>> SearchSongsAndVideosAsync(string query, CancellationToken cancellationToken)
    {
        var songs = SearchSongsAsync(query, cancellationToken);
        var videos = SearchVideosAsync(query, cancellationToken);
        var songList = await songs.ConfigureAwait(false);
        var videoList = await videos.ConfigureAwait(false);
        return Enumerable.Range(0, Math.Max(songList.Count, videoList.Count))
            .SelectMany(i => new[] { songList.ElementAtOrDefault(i), videoList.ElementAtOrDefault(i) })
            .OfType<YouTubeSongDto>()
            .DistinctBy(song => song.VideoId)
            .ToList();
    }

    public async Task<List<YouTubeSongDto>> SearchVideosAsync(string query, CancellationToken cancellationToken)
    {
        var items = await SearchAsync(query, VideosFilterParams, cancellationToken).ConfigureAwait(false);
        return items
            .Select(item => ParseSong(item, isVideo: true))
            .OfType<YouTubeSongDto>()
            .DistinctBy(song => song.VideoId)
            .ToList();
    }

    public async Task<List<YouTubeArtistSummaryDto>> SearchArtistsAsync(string query, CancellationToken cancellationToken)
    {
        var items = await SearchAsync(query, ArtistsFilterParams, cancellationToken).ConfigureAwait(false);
        return items
            .Select(item =>
            {
                var name = Join(FlexColumnRuns(item, 0));
                return PageType(item) == "MUSIC_PAGE_TYPE_ARTIST" && BrowseId(item) is { } id && name.Length > 0
                    ? new YouTubeArtistSummaryDto { Id = id, Name = name, ThumbnailUrl = Thumbnail(item) }
                    : null;
            })
            .OfType<YouTubeArtistSummaryDto>()
            .DistinctBy(artist => artist.Id)
            .ToList();
    }

    /// <summary>Looks up a song or video by its id (e.g. from a link), or returns null if unavailable.</summary>
    public async Task<YouTubeSongDto?> GetSongAsync(string videoId, CancellationToken cancellationToken)
    {
        var root = await PostAsync("next", new JsonObject { ["videoId"] = videoId }, cancellationToken).ConfigureAwait(false);
        // The watch page's queue starts with the video; songs with a music video (or vice versa) come wrapped with it
        var item = Path(root, "contents", "singleColumnMusicWatchNextResultsRenderer", "tabbedRenderer", "watchNextTabbedResultsRenderer", "tabs")
            .Items().FirstOrDefault()
            .Pipe(tab => Path(tab, "tabRenderer", "content", "musicQueueRenderer", "content", "playlistPanelRenderer", "contents"))
            .Items()
            .SelectMany(entry => new[]
            {
                Path(entry, "playlistPanelVideoRenderer"),
                Path(entry, "playlistPanelVideoWrapperRenderer", "primaryRenderer", "playlistPanelVideoRenderer"),
            }.Concat(Path(entry, "playlistPanelVideoWrapperRenderer", "counterpart").Items()
                .Select(counterpart => Path(counterpart, "counterpartRenderer", "playlistPanelVideoRenderer"))))
            .FirstOrDefault(renderer => String(renderer, "videoId") == videoId);
        var title = Join(Path(item, "title", "runs").Items());
        if (title.Length == 0)
        {
            return null;
        }

        // "<artist runs> • <album> • <year>" for songs, "<artist runs> • <views> • <likes>" for videos
        var bylineRuns = Path(item, "longBylineText", "runs").Items();
        var artistRuns = bylineRuns.TakeWhile(run => String(run, "text")?.Trim() != "•").ToList();
        var albumRun = bylineRuns.FirstOrDefault(run => PageType(run) == "MUSIC_PAGE_TYPE_ALBUM");
        var length = Join(Path(item, "lengthText", "runs").Items());
        return new YouTubeSongDto
        {
            VideoId = videoId,
            Title = title,
            Artist = Join(artistRuns),
            Artists = ParseArtists(artistRuns),
            Album = String(albumRun, "text") ?? string.Empty,
            AlbumId = BrowseId(albumRun) ?? string.Empty,
            ThumbnailUrl = String(Path(item, "thumbnail", "thumbnails").Items().LastOrDefault(), "url"),
            Duration = DurationRegex().IsMatch(length) ? Seconds(length) : 0,
            IsVideo = MusicVideoType(item, null) is { } type && type != "MUSIC_VIDEO_TYPE_ATV",
        };
    }

    /// <summary>Looks up an artist page by its channel id, or returns null if unavailable.</summary>
    public async Task<YouTubeArtistPageDto?> GetArtistAsync(string channelId, CancellationToken cancellationToken)
    {
        var root = await PostAsync("browse", new JsonObject { ["browseId"] = channelId }, cancellationToken).ConfigureAwait(false);
        // musicImmersiveHeaderRenderer for most artists, musicVisualHeaderRenderer for some
        var header = Path(root, "header") is JsonObject headers ? headers.Select(entry => entry.Value).FirstOrDefault() : null;
        var name = Join(Path(header, "title", "runs").Items());
        if (name.Length == 0)
        {
            return null;
        }

        var sections = Path(root, "contents", "singleColumnBrowseResultsRenderer", "tabs").Items().FirstOrDefault()
            .Pipe(tab => Path(tab, "tabRenderer", "content", "sectionListRenderer", "contents"))
            .Items();
        var albums = sections
            .Select(section => Path(section, "musicCarouselShelfRenderer"))
            .Where(shelf => shelf != null)
            .Select(shelf => (Shelf: shelf, Title: Path(shelf, "header", "musicCarouselShelfBasicHeaderRenderer", "title", "runs").Items().FirstOrDefault()))
            .Where(shelf => PageType(shelf.Title) == "MUSIC_PAGE_TYPE_ARTIST_DISCOGRAPHY" || DiscographyShelves.Contains(String(shelf.Title, "text") ?? string.Empty))
            .Select(shelf => GetDiscographyAsync(shelf.Shelf, shelf.Title, cancellationToken))
            .ToList();
        var songs = sections.Select(section => Path(section, "musicShelfRenderer")).FirstOrDefault(shelf => shelf != null)
            .Pipe(shelf => GetTopSongsAsync(shelf, cancellationToken));

        var description = sections.Select(section => Path(section, "musicDescriptionShelfRenderer", "description", "runs"))
            .Select(runs => Join(runs.Items()))
            .FirstOrDefault(text => text.Length > 0)
            ?? Join(Path(header, "description", "runs").Items());

        return new YouTubeArtistPageDto
        {
            Id = channelId,
            Name = name,
            ThumbnailUrl = Thumbnail(header),
            Description = description.Length > 0 ? description : null,
            Albums = (await Task.WhenAll(albums).ConfigureAwait(false)).SelectMany(list => list).DistinctBy(album => album.Id).ToList(),
            Songs = await songs.ConfigureAwait(false),
        };
    }

    /// <summary>Looks up an album by its browse id (see <see cref="YouTubeSongDto.AlbumId"/>), or returns null if unavailable.</summary>
    public async Task<YouTubeAlbum?> GetAlbumAsync(string albumId, CancellationToken cancellationToken)
    {
        var root = await PostAsync("browse", new JsonObject { ["browseId"] = albumId }, cancellationToken).ConfigureAwait(false);
        var album = ParseAlbum(root);
        // Albums with music videos list those instead of the audio tracks, sometimes the same video for several tracks
        // (e.g. a single's edit and extended mix); the album's playlist lists the audio tracks
        var playlistId = AlbumPlaylistRegex().Match(String(Path(root, "microformat", "microformatDataRenderer"), "urlCanonical") ?? string.Empty);
        return album != null && album.Tracks.Any(track => track.IsVideo) && playlistId.Success
            ? await WithAudioTracksAsync(album, playlistId.Groups[1].Value, cancellationToken).ConfigureAwait(false)
            : album;
    }

    private async Task<YouTubeAlbum> WithAudioTracksAsync(YouTubeAlbum album, string playlistId, CancellationToken cancellationToken)
    {
        JsonNode? root;
        try
        {
            root = await PostAsync("browse", new JsonObject { ["browseId"] = "VL" + playlistId }, cancellationToken).ConfigureAwait(false);
        }
        catch (HttpRequestException)
        {
            // The videos are better than nothing
            return album;
        }

        var videoIds = Path(root, "contents", "twoColumnBrowseResultsRenderer", "secondaryContents", "sectionListRenderer", "contents").Items()
            .SelectMany(section => Path(section, "musicPlaylistShelfRenderer", "contents").Items())
            .Select(item => Path(item, "musicResponsiveListItemRenderer", "playlistItemData"))
            .Select(data => (SetVideoId: String(data, "playlistSetVideoId"), VideoId: String(data, "videoId")))
            .Where(item => item.SetVideoId != null && item.VideoId != null)
            .DistinctBy(item => item.SetVideoId)
            .ToDictionary(item => item.SetVideoId!, item => item.VideoId!);
        return album with
        {
            Tracks = album.Tracks
                .Select(track => track.IsVideo && track.PlaylistSetVideoId != null && videoIds.TryGetValue(track.PlaylistSetVideoId, out var videoId)
                    ? track with { VideoId = videoId, IsVideo = false }
                    : track)
                .ToList(),
        };
    }

    /// <summary>Looks up an album with its playable tracks for clients, or returns null if unavailable.</summary>
    public async Task<YouTubeAlbumDto?> GetAlbumDetailsAsync(string albumId, CancellationToken cancellationToken)
    {
        var album = await GetAlbumAsync(albumId, cancellationToken).ConfigureAwait(false);
        if (album == null)
        {
            return null;
        }

        return new YouTubeAlbumDto
        {
            Id = albumId,
            Title = album.Title,
            Artist = album.Artist,
            Artists = album.Artists.ToList(),
            ReleaseType = album.ReleaseType,
            Year = album.Year,
            ThumbnailUrl = album.ThumbnailUrl,
            Songs = album.Tracks
                .Where(track => track.VideoId != null)
                .Select(track => new YouTubeSongDto
                {
                    VideoId = track.VideoId!,
                    Title = track.Title,
                    Artist = track.Artist.Length > 0 ? track.Artist : album.Artist,
                    Artists = (track.Artists.Count > 0 ? track.Artists : album.Artists).ToList(),
                    Album = album.Title,
                    AlbumId = albumId,
                    ThumbnailUrl = album.ThumbnailUrl,
                    Duration = track.Duration,
                })
                .ToList(),
        };
    }

    private async Task<List<JsonNode?>> SearchAsync(string query, string filterParams, CancellationToken cancellationToken)
    {
        var root = await PostAsync("search", new JsonObject { ["query"] = query, ["params"] = filterParams }, cancellationToken)
            .ConfigureAwait(false);
        return Path(root, "contents", "tabbedSearchResultsRenderer", "tabs").Items().FirstOrDefault()
            .Pipe(tab => Path(tab, "tabRenderer", "content", "sectionListRenderer", "contents"))
            .Items()
            .SelectMany(section => Path(section, "musicShelfRenderer", "contents").Items())
            .Select(item => Path(item, "musicResponsiveListItemRenderer"))
            .Where(item => item != null)
            .ToList();
    }

    /// <summary>The artist page only shows the latest releases; the shelf title links to all of them.</summary>
    private async Task<List<YouTubeAlbumSummaryDto>> GetDiscographyAsync(JsonNode? shelf, JsonNode? title, CancellationToken cancellationToken)
    {
        // Artist page items of the "Albums" shelf only show the year
        var defaultType = String(title, "text") == "Albums" ? "Album" : null;
        var items = Path(shelf, "contents").Items();
        var endpoint = Path(title, "navigationEndpoint", "browseEndpoint");
        if (PageType(title) == "MUSIC_PAGE_TYPE_ARTIST_DISCOGRAPHY" && String(endpoint, "browseId") is { } browseId)
        {
            try
            {
                var root = await PostAsync("browse", new JsonObject { ["browseId"] = browseId, ["params"] = String(endpoint, "params") }, cancellationToken)
                    .ConfigureAwait(false);
                var all = Path(root, "contents", "singleColumnBrowseResultsRenderer", "tabs").Items().FirstOrDefault()
                    .Pipe(tab => Path(tab, "tabRenderer", "content", "sectionListRenderer", "contents"))
                    .Items()
                    .SelectMany(section => Path(section, "gridRenderer", "items").Items())
                    .ToList();
                if (all.Count > 0)
                {
                    items = all;
                }
            }
            catch (HttpRequestException)
            {
                // The artist page's selection is better than nothing
            }
        }

        return items
            .Select(item => ParseAlbumSummary(Path(item, "musicTwoRowItemRenderer"), defaultType))
            .OfType<YouTubeAlbumSummaryDto>()
            .ToList();
    }

    /// <summary>The artist page only shows the top 5 songs; the shelf title links to a playlist of up to 100.</summary>
    private async Task<List<YouTubeSongDto>> GetTopSongsAsync(JsonNode? shelf, CancellationToken cancellationToken)
    {
        var items = Path(shelf, "contents").Items();
        var endpoint = Path(Path(shelf, "title", "runs").Items().FirstOrDefault(), "navigationEndpoint", "browseEndpoint");
        if (String(endpoint, "browseId") is { } browseId)
        {
            try
            {
                var root = await PostAsync("browse", new JsonObject { ["browseId"] = browseId, ["params"] = String(endpoint, "params") }, cancellationToken)
                    .ConfigureAwait(false);
                var all = Path(root, "contents", "singleColumnBrowseResultsRenderer", "tabs").Items().FirstOrDefault()
                    .Pipe(tab => Path(tab, "tabRenderer", "content", "sectionListRenderer", "contents"))
                    .Items()
                    .SelectMany(section => Path(section, "musicPlaylistShelfRenderer", "contents").Items())
                    .ToList();
                if (all.Count > 0)
                {
                    items = all;
                }
            }
            catch (HttpRequestException)
            {
                // The artist page's top songs are better than nothing
            }
        }

        return items
            .Select(item => ParseSong(Path(item, "musicResponsiveListItemRenderer")))
            .OfType<YouTubeSongDto>()
            .DistinctBy(song => song.VideoId)
            .ToList();
    }

    private async Task<JsonNode?> PostAsync(string endpoint, JsonObject parameters, CancellationToken cancellationToken)
    {
        parameters["context"] = new JsonObject
        {
            ["client"] = new JsonObject { ["clientName"] = "WEB_REMIX", ["clientVersion"] = ClientVersion, ["hl"] = "en" },
        };
        using var request = new HttpRequestMessage(HttpMethod.Post, $"{BaseUrl}/youtubei/v1/{endpoint}?prettyPrint=false")
        {
            Content = new StringContent(parameters.ToJsonString(), Encoding.UTF8, MediaTypeNames.Application.Json),
        };
        request.Headers.TryAddWithoutValidation("Origin", BaseUrl);
        request.Headers.TryAddWithoutValidation("Referer", BaseUrl + "/");
        var client = _httpClientFactory.CreateClient(NamedClient.Default);
        using var response = await client.SendAsync(request, cancellationToken).ConfigureAwait(false);
        if (!response.IsSuccessStatusCode)
        {
            throw new HttpRequestException($"YouTube Music {endpoint} failed: {(int)response.StatusCode}");
        }

        return JsonNode.Parse(await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false));
    }

    /// <summary><paramref name="isVideo"/> applies if the item doesn't tell whether it's a video.</summary>
    private static YouTubeSongDto? ParseSong(JsonNode? item, bool isVideo = false)
    {
        var titleRun = FlexColumnRuns(item, 0).FirstOrDefault();
        var title = String(titleRun, "text");
        var videoId = String(Path(titleRun, "navigationEndpoint", "watchEndpoint"), "videoId")
            ?? String(Path(item, "playlistItemData"), "videoId");
        if (title == null || videoId == null)
        {
            return null;
        }

        // Search results: "<artist runs> • <album> • <duration>" in the second column; artist pages and albums: artists in
        // the second column, plays in the third, the album (if any) in the fourth and the duration in a fixed column
        var artistRuns = FlexColumnRuns(item, 1).TakeWhile(run => String(run, "text")?.Trim() != "•").ToList();
        var details = Path(item, "flexColumns").Items().Skip(1)
            .Select(column => Path(column, "musicResponsiveListItemFlexColumnRenderer", "text", "runs"))
            .Concat(Path(item, "fixedColumns").Items().Select(column => Path(column, "musicResponsiveListItemFixedColumnRenderer", "text", "runs")))
            .SelectMany(runs => runs.Items())
            .ToList();
        var albumRun = details.FirstOrDefault(run => PageType(run) == "MUSIC_PAGE_TYPE_ALBUM");
        var duration = details.Select(run => String(run, "text")?.Trim())
            .LastOrDefault(text => text != null && DurationRegex().IsMatch(text))
            ?.Pipe(Seconds) ?? 0;

        return new YouTubeSongDto
        {
            VideoId = videoId,
            Title = title,
            Artist = string.Concat(artistRuns.Select(run => String(run, "text"))).Trim(),
            Artists = ParseArtists(artistRuns),
            Album = String(albumRun, "text") ?? string.Empty,
            AlbumId = BrowseId(albumRun) ?? string.Empty,
            ThumbnailUrl = Thumbnail(item),
            Duration = duration,
            IsVideo = MusicVideoType(titleRun, item) is { } type ? type != "MUSIC_VIDEO_TYPE_ATV" : isVideo,
        };
    }

    /// <summary>
    /// E.g. <c>MUSIC_VIDEO_TYPE_ATV</c> for songs (audio with a still cover), <c>MUSIC_VIDEO_TYPE_OMV</c> for official music
    /// videos or <c>MUSIC_VIDEO_TYPE_UGC</c> for other videos; null if unknown.
    /// </summary>
    private static string? MusicVideoType(JsonNode? titleRun, JsonNode? item)
    {
        static string? Type(JsonNode? watchEndpoint) => String(
            Path(watchEndpoint, "watchEndpointMusicSupportedConfigs", "watchEndpointMusicConfig"), "musicVideoType");
        return Type(Path(titleRun, "navigationEndpoint", "watchEndpoint"))
            ?? Type(Path(item, "overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer", "playNavigationEndpoint", "watchEndpoint"));
    }

    /// <summary>Seconds of a duration like "3:45" or "1:02:03".</summary>
    private static int Seconds(string duration) =>
        duration.Split(':').Aggregate(0, (acc, part) => (acc * 60) + int.Parse(part, CultureInfo.InvariantCulture));

    private static YouTubeAlbumSummaryDto? ParseAlbumSummary(JsonNode? item, string? defaultType)
    {
        var titleRun = Path(item, "title", "runs").Items().FirstOrDefault();
        var id = PageType(titleRun) == "MUSIC_PAGE_TYPE_ALBUM" ? BrowseId(titleRun)
            : PageType(item) == "MUSIC_PAGE_TYPE_ALBUM" ? BrowseId(item)
            : null;
        var title = String(titleRun, "text");
        if (id == null || string.IsNullOrEmpty(title))
        {
            return null;
        }

        // "Album • 2013", "Single • 2023" or just "2013"
        var subtitle = Path(item, "subtitle", "runs").Items().Select(run => String(run, "text")?.Trim())
            .OfType<string>().Where(text => text.Length > 0 && text != "•").ToList();
        var year = subtitle.FirstOrDefault(text => YearRegex().IsMatch(text));
        return new YouTubeAlbumSummaryDto
        {
            Id = id,
            Title = title,
            ReleaseType = subtitle.FirstOrDefault(text => !YearRegex().IsMatch(text)) ?? defaultType,
            Year = year != null ? int.Parse(year, CultureInfo.InvariantCulture) : null,
            ThumbnailUrl = String(Path(item, "thumbnailRenderer", "musicThumbnailRenderer", "thumbnail", "thumbnails").Items().LastOrDefault(), "url"),
        };
    }

    private static YouTubeAlbum? ParseAlbum(JsonNode? root)
    {
        var tabs = Path(root, "contents", "twoColumnBrowseResultsRenderer");
        var header = Path(Path(tabs, "tabs").Items().FirstOrDefault(), "tabRenderer", "content", "sectionListRenderer", "contents")
            .Items().FirstOrDefault()
            .Pipe(section => Path(section, "musicResponsiveHeaderRenderer"));
        if (header == null)
        {
            return null;
        }

        List<JsonNode?> Runs(string key) => Path(header, key, "runs").Items();

        var title = Join(Runs("title"));
        if (title.Length == 0)
        {
            return null;
        }

        var artistRuns = Runs("straplineTextOne");
        // Subtitle: "Album • 2013"
        var subtitle = Runs("subtitle").Select(run => String(run, "text")?.Trim())
            .OfType<string>().Where(text => text.Length > 0 && text != "•").ToList();

        var tracks = Path(tabs, "secondaryContents", "sectionListRenderer", "contents").Items()
            .SelectMany(section => Path(section, "musicShelfRenderer", "contents").Items())
            .Select(item => Path(item, "musicResponsiveListItemRenderer"))
            .Where(renderer => renderer != null)
            .Select(renderer =>
            {
                var song = ParseSong(renderer);
                return new YouTubeAlbumTrack(
                    String(Path(renderer, "playlistItemData"), "videoId"),
                    Join(FlexColumnRuns(renderer, 0)),
                    int.TryParse(String(Path(renderer, "index", "runs").Items().FirstOrDefault(), "text"), out var number) ? number : null,
                    song?.Artist ?? string.Empty,
                    song?.Artists ?? [],
                    song?.Duration ?? 0,
                    String(Path(renderer, "playlistItemData"), "playlistSetVideoId"),
                    song?.IsVideo ?? false);
            })
            .Where(track => track.Title.Length > 0)
            .ToList();

        // "13 songs • 1 hour, 14 minutes"; long track lists are paginated, so prefer this count over tracks.Count
        var songCount = Runs("secondSubtitle")
            .Select(run => SongCountRegex().Match(String(run, "text") ?? string.Empty))
            .FirstOrDefault(match => match.Success)?.Groups[1].Value;
        var year = subtitle.FirstOrDefault(text => YearRegex().IsMatch(text));

        return new YouTubeAlbum(
            title,
            Join(artistRuns),
            ParseArtists(artistRuns),
            subtitle.FirstOrDefault(text => !YearRegex().IsMatch(text)),
            year != null ? int.Parse(year, CultureInfo.InvariantCulture) : null,
            songCount != null ? int.Parse(songCount, CultureInfo.InvariantCulture) : (tracks.Count > 0 ? tracks.Count : null),
            Thumbnail(header),
            tracks);
    }

    /// <summary>Splits artist runs like "A", ", ", "B", " &amp; ", "C" into the individual artists.</summary>
    private static List<YouTubeArtistDto> ParseArtists(IEnumerable<JsonNode?> runs) => runs
        .Select(run =>
        {
            var name = String(run, "text")?.Trim() ?? string.Empty;
            if (PageType(run) == "MUSIC_PAGE_TYPE_ARTIST")
            {
                return new YouTubeArtistDto { Id = BrowseId(run) ?? string.Empty, Name = name };
            }

            return name.Length == 0 || JoinPhrases.Contains(name.ToLowerInvariant()) ? null : new YouTubeArtistDto { Name = name };
        })
        .OfType<YouTubeArtistDto>()
        .DistinctBy(artist => artist.Name)
        .ToList();

    private static List<JsonNode?> FlexColumnRuns(JsonNode? item, int column) =>
        Path(Path(item, "flexColumns").Items().ElementAtOrDefault(column), "musicResponsiveListItemFlexColumnRenderer", "text", "runs").Items();

    private static string Join(IEnumerable<JsonNode?> runs) => string.Concat(runs.Select(run => String(run, "text"))).Trim();

    /// <summary>URL of the largest thumbnail of a list item or header.</summary>
    private static string? Thumbnail(JsonNode? node) =>
        String(Path(node, "thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails").Items().LastOrDefault(), "url");

    private static JsonNode? Path(JsonNode? node, params string[] keys) =>
        keys.Aggregate(node, (current, key) => current is JsonObject obj ? obj[key] : null);

    private static string? String(JsonNode? node, string key) =>
        node is JsonObject obj && obj[key] is JsonValue value && value.TryGetValue<string>(out var text) ? text : null;

    private static string? BrowseId(JsonNode? run) => String(Path(run, "navigationEndpoint", "browseEndpoint"), "browseId");

    private static string? PageType(JsonNode? run) => String(
        Path(run, "navigationEndpoint", "browseEndpoint", "browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig"),
        "pageType");

    [GeneratedRegex(@"=w\d+-h\d+")]
    private static partial Regex ThumbnailSizeRegex();

    [GeneratedRegex(@"^\d+(:\d{2})+$")]
    private static partial Regex DurationRegex();

    [GeneratedRegex(@"^\d{4}$")]
    private static partial Regex YearRegex();

    [GeneratedRegex(@"(\d+) songs?")]
    private static partial Regex SongCountRegex();

    [GeneratedRegex(@"^UC[\w-]{22}$")]
    private static partial Regex ChannelIdRegex();

    [GeneratedRegex(@"^MPREb_[\w-]+$")]
    private static partial Regex AlbumIdRegex();

    [GeneratedRegex(@"[?&]list=(OLAK5uy_[\w-]+)")]
    private static partial Regex AlbumPlaylistRegex();
}

internal static class JsonNodeExtensions
{
    public static List<JsonNode?> Items(this JsonNode? node) => node is JsonArray array ? array.ToList() : [];

    public static TResult Pipe<T, TResult>(this T value, Func<T, TResult> transform) => transform(value);
}
