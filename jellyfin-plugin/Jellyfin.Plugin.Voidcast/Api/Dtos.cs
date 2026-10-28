using System;
using System.Collections.Generic;

namespace Jellyfin.Plugin.Voidcast.Api;

/// <summary>
/// Tags of a downloaded track, modelled after what MusicBrainz Picard writes: <see cref="Artist"/> and
/// <see cref="AlbumArtist"/> are the credits as displayed, <see cref="Artists"/> and <see cref="AlbumArtists"/>
/// the individual (multi-valued) artists.
/// </summary>
public class TrackTags
{
    public string Title { get; set; } = string.Empty;

    public string Artist { get; set; } = string.Empty;

    public List<string> Artists { get; set; } = [];

    public string? Album { get; set; }

    public string? Genre { get; set; }

    public string? AlbumArtist { get; set; }

    public List<string> AlbumArtists { get; set; } = [];

    public int? TrackNumber { get; set; }

    public int? TotalTracks { get; set; }

    public int? Year { get; set; }

    /// <summary>Gets or sets the MusicBrainz primary type, e.g. "album", "single" or "ep".</summary>
    public string? ReleaseType { get; set; }

    public string? Media { get; set; }

    public string Comment { get; set; } = string.Empty;
}

public class YouTubeArtistDto
{
    /// <summary>Gets or sets the YouTube Music channel id, empty for artists without an artist page.</summary>
    public string Id { get; set; } = string.Empty;

    public string Name { get; set; } = string.Empty;
}

/// <summary>A YouTube Music search result. Clients send it back as is to download the track.</summary>
public class YouTubeSongDto
{
    public string VideoId { get; set; } = string.Empty;

    public string Title { get; set; } = string.Empty;

    /// <summary>Gets or sets the artist credit as displayed, e.g. "Daft Punk, Pharrell Williams &amp; Nile Rodgers".</summary>
    public string Artist { get; set; } = string.Empty;

    public List<YouTubeArtistDto> Artists { get; set; } = [];

    public string Album { get; set; } = string.Empty;

    /// <summary>Gets or sets the album's browse id, used to look up track numbers etc. for tagging.</summary>
    public string AlbumId { get; set; } = string.Empty;

    /// <summary>Gets or sets the URL of the square album cover thumbnail.</summary>
    public string? ThumbnailUrl { get; set; }

    /// <summary>Gets or sets the duration in seconds.</summary>
    public int Duration { get; set; }

    /// <summary>Gets or sets a value indicating whether this is a (music) video rather than a song with a still cover.</summary>
    public bool IsVideo { get; set; }

    /// <summary>Gets or sets the genre to tag a download with; YouTube Music doesn't tell one.</summary>
    public string? Genre { get; set; }

    /// <summary>Gets or sets where a download starts in seconds, to cut off an intro; null keeps the start.</summary>
    public int? StartTime { get; set; }

    /// <summary>Gets or sets where a download ends in seconds, to cut off an outro; null keeps the end.</summary>
    public int? EndTime { get; set; }
}

/// <summary>A YouTube Music artist search result.</summary>
public class YouTubeArtistSummaryDto
{
    /// <summary>Gets or sets the channel id of the artist page.</summary>
    public string Id { get; set; } = string.Empty;

    public string Name { get; set; } = string.Empty;

    public string? ThumbnailUrl { get; set; }
}

/// <summary>An album, single or EP on an artist page.</summary>
public class YouTubeAlbumSummaryDto
{
    /// <summary>Gets or sets the album's browse id, see <see cref="YouTubeSongDto.AlbumId"/>.</summary>
    public string Id { get; set; } = string.Empty;

    public string Title { get; set; } = string.Empty;

    /// <summary>Gets or sets the release type as displayed by YouTube Music, e.g. "Album", "Single" or "EP".</summary>
    public string? ReleaseType { get; set; }

    public int? Year { get; set; }

    public string? ThumbnailUrl { get; set; }
}

public class YouTubeArtistPageDto
{
    public string Id { get; set; } = string.Empty;

    public string Name { get; set; } = string.Empty;

    public string? ThumbnailUrl { get; set; }

    public string? Description { get; set; }

    /// <summary>Gets or sets the artist's albums, singles and EPs, newest first per release type.</summary>
    public List<YouTubeAlbumSummaryDto> Albums { get; set; } = [];

    /// <summary>Gets or sets the artist's most popular songs.</summary>
    public List<YouTubeSongDto> Songs { get; set; } = [];
}

public class YouTubeAlbumDto
{
    public string Id { get; set; } = string.Empty;

    public string Title { get; set; } = string.Empty;

    /// <summary>Gets or sets the artist credit as displayed.</summary>
    public string Artist { get; set; } = string.Empty;

    public List<YouTubeArtistDto> Artists { get; set; } = [];

    public string? ReleaseType { get; set; }

    public int? Year { get; set; }

    public string? ThumbnailUrl { get; set; }

    /// <summary>Gets or sets the playable tracks; clients send them back as is to download them.</summary>
    public List<YouTubeSongDto> Songs { get; set; } = [];
}

public enum DownloadState
{
    Queued,
    Downloading,
    /// <summary>The file is in the library folder; waiting for the library scan to pick it up.</summary>
    Importing,
    Completed,
    Failed,
}

public class DownloadJobDto
{
    public Guid Id { get; set; }

    public string VideoId { get; set; } = string.Empty;

    public string Title { get; set; } = string.Empty;

    public DownloadState State { get; set; }

    public string? Message { get; set; }

    /// <summary>Gets or sets the path of the written file on the server.</summary>
    public string? Path { get; set; }

    /// <summary>Gets or sets the library item id of the imported track, once the library scan found it.</summary>
    public string? ItemId { get; set; }

    public DateTime CreatedAt { get; set; }
}

/// <summary>Adds YouTube Music tracks to a playlist, downloading those that aren't in the library yet.</summary>
public class PlaylistAdditionDto
{
    public Guid Id { get; set; }

    public string PlaylistId { get; set; } = string.Empty;

    public string PlaylistName { get; set; } = string.Empty;

    /// <summary>Gets or sets the number of requested tracks.</summary>
    public int Total { get; set; }

    public int Added { get; set; }

    /// <summary>Gets or sets the number of tracks that were in the playlist already.</summary>
    public int AlreadyInPlaylist { get; set; }

    /// <summary>Gets or sets why tracks couldn't be added, one entry per track.</summary>
    public List<string> Failures { get; set; } = [];

    public bool Finished { get; set; }

    /// <summary>Gets or sets the download jobs of the tracks that weren't in the library.</summary>
    public List<DownloadJobDto> Jobs { get; set; } = [];

    public DateTime CreatedAt { get; set; }
}

public class PluginInfoDto
{
    public string Version { get; set; } = string.Empty;

    /// <summary>Gets or sets a value indicating whether yt-dlp is available.</summary>
    public bool YtDlpAvailable { get; set; }

    public string? YtDlpVersion { get; set; }

    /// <summary>Gets or sets a value indicating whether the requesting user may download tracks into the library.</summary>
    public bool CanDownload { get; set; }

    public string? DownloadDirectory { get; set; }

    public string? Warning { get; set; }

    /// <summary>Gets or sets a value indicating whether search includes videos and videos can be streamed with picture.</summary>
    public bool VideoAvailable { get; set; }

    /// <summary>Gets or sets a value indicating whether tracks can be added to playlists (downloading them first).</summary>
    public bool PlaylistAdditionAvailable { get; set; }
}
