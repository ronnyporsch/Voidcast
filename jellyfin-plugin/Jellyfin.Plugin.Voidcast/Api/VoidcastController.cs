using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Net.Mime;
using System.Security.Claims;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Voidcast.Services;
using MediaBrowser.Controller.Playlists;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Voidcast.Api;

/// <summary>
/// Endpoints used by Voidcast clients: the server searches YouTube Music for them, downloads the tracks
/// they pick into the library and streams tracks for playback.
/// </summary>
[ApiController]
[Authorize]
[Route("Voidcast")]
[Produces(MediaTypeNames.Application.Json)]
public class VoidcastController : ControllerBase
{
    private readonly YtDlpService _ytDlp;
    private readonly YouTubeMusicClient _youTubeMusic;
    private readonly DownloadManager _downloads;
    private readonly IPlaylistManager _playlistManager;
    private readonly ILogger<VoidcastController> _logger;

    public VoidcastController(
        YtDlpService ytDlp,
        YouTubeMusicClient youTubeMusic,
        DownloadManager downloads,
        IPlaylistManager playlistManager,
        ILogger<VoidcastController> logger)
    {
        _logger = logger;
        _ytDlp = ytDlp;
        _youTubeMusic = youTubeMusic;
        _downloads = downloads;
        _playlistManager = playlistManager;
    }

    /// <summary>Tells clients the plugin is installed and whether the user may download.</summary>
    [HttpGet("Info")]
    public async Task<ActionResult<PluginInfoDto>> GetInfo(CancellationToken cancellationToken)
    {
        return new PluginInfoDto
        {
            Version = Plugin.Instance!.Version.ToString(),
            YtDlpAvailable = YtDlpService.IsAvailable,
            YtDlpVersion = await _ytDlp.GetVersionAsync(cancellationToken).ConfigureAwait(false),
            CanDownload = MayDownload(),
            DownloadDirectory = _downloads.GetDownloadDirectory(),
            Warning = _ytDlp.CookiesWarning,
            VideoAvailable = true,
            PlaylistAdditionAvailable = true,
            SongLookupAvailable = true,
        };
    }

    /// <summary>Searches YouTube Music for songs, with <paramref name="includeVideos"/> also for (music) videos.</summary>
    [HttpGet("YouTube/Search")]
    public async Task<ActionResult<List<YouTubeSongDto>>> Search([FromQuery] string query, [FromQuery] bool includeVideos, CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(query))
        {
            return new List<YouTubeSongDto>();
        }

        try
        {
            return includeVideos
                ? await _youTubeMusic.SearchSongsAndVideosAsync(query, cancellationToken).ConfigureAwait(false)
                : await _youTubeMusic.SearchSongsAsync(query, cancellationToken).ConfigureAwait(false);
        }
        catch (HttpRequestException e)
        {
            return Problem(e.Message, statusCode: StatusCodes.Status502BadGateway);
        }
    }

    /// <summary>Searches YouTube Music for artists.</summary>
    [HttpGet("YouTube/Artists/Search")]
    public async Task<ActionResult<List<YouTubeArtistSummaryDto>>> SearchArtists([FromQuery] string query, CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(query))
        {
            return new List<YouTubeArtistSummaryDto>();
        }

        try
        {
            return await _youTubeMusic.SearchArtistsAsync(query, cancellationToken).ConfigureAwait(false);
        }
        catch (HttpRequestException e)
        {
            return Problem(e.Message, statusCode: StatusCodes.Status502BadGateway);
        }
    }

    /// <summary>A song or video by its id, e.g. from a link pasted into the search.</summary>
    [HttpGet("YouTube/Songs/{videoId}")]
    public async Task<ActionResult<YouTubeSongDto>> GetSong([FromRoute] string videoId, CancellationToken cancellationToken)
    {
        if (!YtDlpService.IsValidVideoId(videoId))
        {
            return BadRequest();
        }

        try
        {
            var song = await _youTubeMusic.GetSongAsync(videoId, cancellationToken).ConfigureAwait(false);
            return song != null ? song : NotFound();
        }
        catch (HttpRequestException e)
        {
            return Problem(e.Message, statusCode: StatusCodes.Status502BadGateway);
        }
    }

    /// <summary>A YouTube Music artist with their albums, singles and EPs and their most popular songs.</summary>
    [HttpGet("YouTube/Artists/{channelId}")]
    public async Task<ActionResult<YouTubeArtistPageDto>> GetArtist([FromRoute] string channelId, CancellationToken cancellationToken)
    {
        if (!YouTubeMusicClient.IsValidChannelId(channelId))
        {
            return BadRequest();
        }

        try
        {
            var artist = await _youTubeMusic.GetArtistAsync(channelId, cancellationToken).ConfigureAwait(false);
            return artist != null ? artist : NotFound();
        }
        catch (HttpRequestException e)
        {
            return Problem(e.Message, statusCode: StatusCodes.Status502BadGateway);
        }
    }

    /// <summary>A YouTube Music album with its tracks, which can be streamed and downloaded like search results.</summary>
    [HttpGet("YouTube/Albums/{albumId}")]
    public async Task<ActionResult<YouTubeAlbumDto>> GetAlbum([FromRoute] string albumId, CancellationToken cancellationToken)
    {
        if (!YouTubeMusicClient.IsValidAlbumId(albumId))
        {
            return BadRequest();
        }

        try
        {
            var album = await _youTubeMusic.GetAlbumDetailsAsync(albumId, cancellationToken).ConfigureAwait(false);
            return album != null ? album : NotFound();
        }
        catch (HttpRequestException e)
        {
            return Problem(e.Message, statusCode: StatusCodes.Status502BadGateway);
        }
    }

    /// <summary>Queues a download of a YouTube Music track (a result of <see cref="Search"/>) into the music library.</summary>
    [HttpPost("Downloads")]
    [ProducesResponseType(StatusCodes.Status202Accepted)]
    public ActionResult<DownloadJobDto> RequestDownload([FromBody] YouTubeSongDto song)
    {
        if (!MayDownload())
        {
            return Problem("Only administrators may download into the library (see the Voidcast plugin settings)", statusCode: StatusCodes.Status403Forbidden);
        }

        try
        {
            var job = _downloads.Enqueue(song, UserId);
            return AcceptedAtAction(nameof(GetDownload), new { id = job.Id }, job);
        }
        catch (ArgumentException e)
        {
            return Problem(e.Message, statusCode: StatusCodes.Status400BadRequest);
        }
        catch (InvalidOperationException e)
        {
            return Problem(e.Message, statusCode: StatusCodes.Status503ServiceUnavailable);
        }
    }

    /// <summary>
    /// Returns those of the given video ids that are already in the library's download folder, also ones
    /// downloaded before the server restarted. Lookups are cheap; the folder's tags are read in the background.
    /// </summary>
    [HttpPost("YouTube/Downloaded")]
    public ActionResult<IReadOnlyList<string>> GetDownloaded([FromBody] List<string> videoIds) =>
        Ok(_downloads.FindDownloaded(videoIds.Where(YtDlpService.IsValidVideoId).Take(500)).Select(track => track.VideoId).ToList());

    /// <summary>Like <see cref="GetDownloaded"/>, with codec and bitrate of the downloaded files.</summary>
    [HttpPost("YouTube/Downloaded/Details")]
    public ActionResult<IReadOnlyList<DownloadedTrack>> GetDownloadedDetails([FromBody] List<string> videoIds) =>
        Ok(_downloads.FindDownloaded(videoIds.Where(YtDlpService.IsValidVideoId).Take(500)));

    /// <summary>Returns those of the given album ids whose tracks are all in the library's download folder.</summary>
    [HttpPost("YouTube/Albums/Downloaded")]
    public async Task<ActionResult<IReadOnlyList<string>>> GetDownloadedAlbums([FromBody] List<string> albumIds, CancellationToken cancellationToken) =>
        Ok(await _downloads.FindDownloadedAlbumsAsync(albumIds.Where(YouTubeMusicClient.IsValidAlbumId).Take(200), cancellationToken).ConfigureAwait(false));

    /// <summary>
    /// Adds YouTube Music tracks to a playlist of the user, in the given order. Tracks that aren't in the library are
    /// downloaded first; they are added once the library scan found them, in the background. Returns the progress.
    /// </summary>
    [HttpPost("YouTube/Playlists/{playlistId}/Items")]
    [ProducesResponseType(StatusCodes.Status202Accepted)]
    public ActionResult<PlaylistAdditionDto> AddToPlaylist([FromRoute] Guid playlistId, [FromBody] List<YouTubeSongDto> songs)
    {
        if (songs.Count is 0 or > 500)
        {
            return Problem("Between 1 and 500 tracks can be added at once", statusCode: StatusCodes.Status400BadRequest);
        }

        var playlist = _playlistManager.GetPlaylistForUser(playlistId, UserId);
        if (playlist == null)
        {
            return NotFound();
        }

        if (playlist.OwnerUserId != UserId && !playlist.Shares.Any(share => share.UserId == UserId && share.CanEdit))
        {
            return Problem("You may not edit this playlist", statusCode: StatusCodes.Status403Forbidden);
        }

        try
        {
            var addition = _downloads.AddToPlaylist(playlist, songs, UserId, MayDownload());
            return AcceptedAtAction(nameof(GetPlaylistAddition), new { id = addition.Id }, addition);
        }
        catch (UnauthorizedAccessException e)
        {
            return Problem(e.Message, statusCode: StatusCodes.Status403Forbidden);
        }
        catch (ArgumentException e)
        {
            return Problem(e.Message, statusCode: StatusCodes.Status400BadRequest);
        }
        catch (InvalidOperationException e)
        {
            return Problem(e.Message, statusCode: StatusCodes.Status503ServiceUnavailable);
        }
    }

    [HttpGet("YouTube/Playlists/Additions")]
    public ActionResult<IReadOnlyList<PlaylistAdditionDto>> GetPlaylistAdditions() => Ok(_downloads.GetAdditions(UserId));

    [HttpGet("YouTube/Playlists/Additions/{id}")]
    public ActionResult<PlaylistAdditionDto> GetPlaylistAddition([FromRoute] Guid id) =>
        _downloads.GetAddition(id, UserId) is { } addition ? addition : NotFound();

    [HttpGet("Downloads")]
    public ActionResult<IReadOnlyList<DownloadJobDto>> GetDownloads() => Ok(_downloads.GetJobs(UserId));

    [HttpGet("Downloads/{id}")]
    public ActionResult<DownloadJobDto> GetDownload([FromRoute] Guid id) =>
        _downloads.GetJob(id, UserId) is { } job ? job : NotFound();

    /// <summary>Codec and bitrate of the stream a download would get (YouTube's search results don't say).</summary>
    [HttpGet("YouTube/{videoId}/StreamInfo")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    public async Task<ActionResult<AudioStreamInfo>> GetStreamInfo([FromRoute] string videoId, CancellationToken cancellationToken)
    {
        if (!YtDlpService.IsValidVideoId(videoId))
        {
            return BadRequest();
        }

        try
        {
            var info = await _ytDlp.GetAudioStreamInfoAsync(videoId, cancellationToken).ConfigureAwait(false);
            return info != null ? info : NoContent();
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            // The client lost interest, e.g. the search results scrolled out of view
            return NoContent();
        }
        catch (IOException e)
        {
            // Only informational, clients just don't show codec and bitrate
            _logger.LogWarning("No stream info for {VideoId}: {Message}", videoId, e.Message);
            return NoContent();
        }
    }

    /// <summary>
    /// Audio of a YouTube Music track for clients that can't run yt-dlp themselves. <paramref name="format"/>
    /// "mp3" converts it for players without Opus/AAC support; otherwise the original stream is served.
    /// </summary>
    [HttpGet("YouTube/{videoId}/Audio")]
    public async Task<ActionResult> GetAudio([FromRoute] string videoId, [FromQuery] string? format, CancellationToken cancellationToken)
    {
        if (!YtDlpService.IsValidVideoId(videoId))
        {
            return BadRequest();
        }

        try
        {
            var path = await _ytDlp.GetPlaybackFileAsync(videoId, format == "mp3", cancellationToken).ConfigureAwait(false);
            return PhysicalFile(path, ContentTypeOf(path), enableRangeProcessing: true);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            // The client skipped the track before it was ready
            return new EmptyResult();
        }
    }

    /// <summary>
    /// A YouTube video with picture and sound (MP4) in the highest resolution up to <paramref name="maxHeight"/>
    /// (default 1080), see <see cref="GetVideoHeights"/>.
    /// </summary>
    [HttpGet("YouTube/{videoId}/Video")]
    public async Task<ActionResult> GetVideo([FromRoute] string videoId, [FromQuery] int? maxHeight, CancellationToken cancellationToken)
    {
        if (!YtDlpService.IsValidVideoId(videoId))
        {
            return BadRequest();
        }

        try
        {
            var path = await _ytDlp.GetVideoFileAsync(videoId, maxHeight ?? 1080, cancellationToken).ConfigureAwait(false);
            return PhysicalFile(path, Path.GetExtension(path) == ".webm" ? "video/webm" : "video/mp4", enableRangeProcessing: true);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            return new EmptyResult();
        }
        catch (IOException e)
        {
            return Problem(e.Message, statusCode: StatusCodes.Status502BadGateway);
        }
    }

    /// <summary>Codecs and bitrates of the video and audio streams <see cref="GetVideo"/> serves for these arguments.</summary>
    [HttpGet("YouTube/{videoId}/VideoInfo")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    public async Task<ActionResult<VideoStreamInfo>> GetVideoInfo([FromRoute] string videoId, [FromQuery] int? maxHeight, CancellationToken cancellationToken)
    {
        if (!YtDlpService.IsValidVideoId(videoId))
        {
            return BadRequest();
        }

        try
        {
            var info = await _ytDlp.GetVideoStreamInfoAsync(videoId, maxHeight ?? 1080, cancellationToken).ConfigureAwait(false);
            return info != null ? info : NoContent();
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            return NoContent();
        }
        catch (IOException e)
        {
            // Only informational
            _logger.LogWarning("No video info for {VideoId}: {Message}", videoId, e.Message);
            return NoContent();
        }
    }

    /// <summary>The video heights (e.g. 1080) a YouTube video is available in, highest first.</summary>
    [HttpGet("YouTube/{videoId}/VideoHeights")]
    public async Task<ActionResult<IReadOnlyList<int>>> GetVideoHeights([FromRoute] string videoId, CancellationToken cancellationToken)
    {
        if (!YtDlpService.IsValidVideoId(videoId))
        {
            return BadRequest();
        }

        try
        {
            return Ok(await _ytDlp.GetVideoHeightsAsync(videoId, cancellationToken).ConfigureAwait(false));
        }
        catch (IOException e)
        {
            return Problem(e.Message, statusCode: StatusCodes.Status502BadGateway);
        }
    }

    private Guid UserId =>
        Guid.TryParse(User.FindFirstValue("Jellyfin-UserId"), out var id) ? id : Guid.Empty;

    private bool MayDownload() =>
        User.IsInRole("Administrator") || Plugin.Instance!.Configuration.AllowNonAdminDownloads;

    private static string ContentTypeOf(string path) => Path.GetExtension(path).ToLowerInvariant() switch
    {
        ".opus" or ".ogg" or ".oga" => "audio/ogg",
        ".m4a" or ".mp4" => "audio/mp4",
        ".webm" => "audio/webm",
        ".mp3" => "audio/mpeg",
        _ => "application/octet-stream",
    };
}
