using MediaBrowser.Model.Plugins;

namespace Jellyfin.Plugin.Voidcast.Configuration;

public class PluginConfiguration : BasePluginConfiguration
{
    /// <summary>
    /// Gets or sets the folder downloads are written to (as Album Artist/YYYY - Album/NN Title.ext).
    /// Blank uses the first folder of the first music library.
    /// </summary>
    public string DownloadDirectory { get; set; } = string.Empty;

    /// <summary>
    /// Gets or sets the path of the yt-dlp executable. Blank uses the one shipped with the plugin (Linux x64/arm64), else the one on the PATH.
    /// </summary>
    public string YtDlpPath { get; set; } = string.Empty;

    /// <summary>
    /// Gets or sets the path of a Netscape-format cookies.txt file with the YouTube login (<c>--cookies</c>), e.g. one
    /// mounted into the container. Takes precedence over <see cref="CookiesFromBrowser"/>; blank for none.
    /// </summary>
    public string CookiesFile { get; set; } = string.Empty;

    /// <summary>
    /// Gets or sets the browser yt-dlp reads YouTube cookies from (<c>--cookies-from-browser</c>), blank for none.
    /// Signed-in requests can get higher quality streams (YouTube Music Premium) and avoid bot checks.
    /// </summary>
    public string CookiesFromBrowser { get; set; } = "firefox";

    /// <summary>
    /// Gets or sets the browser profile name or path (e.g. a Firefox profile folder mounted into the container).
    /// Blank uses the browser's default profile of the user Jellyfin runs as.
    /// </summary>
    public string BrowserProfile { get; set; } = string.Empty;

    public bool AllowNonAdminDownloads { get; set; }

    public int MaxConcurrentDownloads { get; set; } = 2;

    /// <summary>Gets or sets how many songs in the library an artist needs for new releases to be looked for.</summary>
    public int NewReleasesMinSongs { get; set; } = 15;

    /// <summary>
    /// Gets or sets a value indicating whether new releases are looked for from the users' favorite artists instead,
    /// whatever number of songs the library has of them.
    /// </summary>
    public bool NewReleasesFavoritesOnly { get; set; }
}
