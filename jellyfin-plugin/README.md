# Voidcast Jellyfin plugin

Lets Voidcast clients (Android, desktop and web) search YouTube Music and download tracks into the Jellyfin
music library. The plugin searches YouTube Music for the clients; when a client picks a track, the plugin

1. downloads the best audio stream with [yt-dlp](https://github.com/yt-dlp/yt-dlp) (original codec, no re-encoding),
   optionally signed in with a cookies.txt file or the cookies of a browser on the server (e.g. Firefox),
2. looks up its album and tags it like MusicBrainz Picard would (title, artists, album, album artists, track number,
   year, cover, ...),
3. saves it as `Album Artist/YYYY - Album/NN Title.opus` (or `.m4a`) into the download folder and
4. starts a library scan.

Clients can also search YouTube Music artists and browse their albums, singles and EPs and top songs; album tracks
are streamed and downloaded like search results. Clients are told which tracks are already downloaded: the plugin
reads the YouTube URL in the comment tag of the files in the download folder (once, then only new or changed files)
and keeps the index in `<data dir>/voidcast/download-index.json`. The plugin also streams YouTube Music tracks for playback and
reports the codec/bitrate a download would get.

The *Scan for new releases* task (*Dashboard → Scheduled Tasks*, weekly by default, or *Scan now* in a client) looks on
YouTube Music for releases of the library's artists that aren't in the library yet and keeps the result in
`<data dir>/voidcast/new-releases.json`. Administrators choose the artists in the clients' settings: those with at
least a number of songs in the library, or the favorites of all users.

Requires Jellyfin 12.2. The plugin ships the standalone [yt-dlp](https://github.com/yt-dlp/yt-dlp) release for
Linux x64 and arm64 (what the official Docker image runs on) and updates it daily; elsewhere install yt-dlp on the
server's `PATH` or set *yt-dlp path*. It also ships [Deno](https://deno.com), the JavaScript runtime yt-dlp needs to
solve YouTube's challenges: signed in (with cookies), YouTube only offers formats behind them. Elsewhere install Deno
on the `PATH` for signed-in downloads; without it the plugin downloads without signing in. ffmpeg and ffprobe are used from the `PATH` if installed, otherwise (e.g. in
the official Docker image) Jellyfin's own.

## Building

Needs the .NET 10 SDK and network access: the build downloads the latest yt-dlp and Deno releases (verified against
their SHA-256 checksums) into `.tools/` and bundles them; delete that folder to bundle newer releases.

```sh
./build.sh [URL dist/ will be served from]
```

This writes `dist/voidcast_<version>.zip` and `dist/manifest.json`, a Jellyfin plugin repository manifest. Tests run
with `dotnet test Jellyfin.Plugin.Voidcast.Tests`.

The version comes from the latest `plugin-v<major>.<minor>.<patch>` git tag plus the number of commits since then
(`plugin-v1.9.0` and 2 commits: 1.9.0.2), so every build after a new commit has a version Jellyfin installs over the
previous one; set `VERSION` to override it. The changelog is the tag's message (or `CHANGELOG`).

## Releasing

Push an annotated tag, its message is the changelog shown in Jellyfin:

```sh
git tag -a plugin-v1.10.0 -m "What changed"
git push origin plugin-v1.10.0
```

The [release workflow](../.github/workflows/jellyfin-plugin.yml) builds the plugin, attaches the zip to a GitHub
release and adds the version to `manifest.json` on the `plugin-repository` branch.

## Installing

Either

- add `https://raw.githubusercontent.com/ronnyporsch/Voidcast/plugin-repository/manifest.json` as a repository
  under *Dashboard → Plugins → Repositories*, install *Voidcast* from the catalog and restart Jellyfin (updates
  then show up in the catalog too),
- for a local build, serve `dist/` over HTTP (e.g. `cd dist && python3 -m http.server 8765`) and add
  `<URL>/manifest.json` as repository instead, where the URL must be reachable from the Jellyfin server (pass it to
  `build.sh`), or
- unpack the zip into `<Jellyfin config dir>/plugins/Voidcast_<version>/` and restart Jellyfin.

## Configuration

*Dashboard → Plugins → Voidcast*:

| Setting | Default |
|---|---|
| Download folder | First folder of the first music library |
| Cookies file | None (blank: use the browser cookies) |
| Use cookies from browser | `firefox` (blank: download without signing in) |
| Browser profile | Default profile of the user Jellyfin runs as |
| yt-dlp path | The yt-dlp shipped with the plugin, else yt-dlp from the `PATH` |
| Allow all users to download | Off: only administrators can add tracks to the library |
| Parallel downloads | 2 |

Keep yt-dlp up to date (the bundled one updates itself), YouTube changes regularly break older versions.

### Cookies file with Docker

Export the YouTube cookies in Netscape format (e.g. with a "cookies.txt" browser extension while signed in to
music.youtube.com), mount the file and set *Cookies file* to the mount point:

```yaml
services:
  jellyfin:
    volumes:
      - /path/to/cookies.txt:/cookies.txt:ro
```

The file must exist before the container starts, otherwise Docker creates an empty directory in its place. yt-dlp
writes cookies back to the file it's given, so the plugin passes it a copy in Jellyfin's cache folder (refreshed when
the original changes) and the mount can stay read-only.

### Firefox cookies with Docker

The official Jellyfin image contains no browser, so mount the Firefox profile folder (read-only is enough, yt-dlp
copies the cookie database) and set *Browser profile* to the mount point:

```yaml
services:
  jellyfin:
    volumes:
      # Firefox keeps profiles in ~/.mozilla/firefox or (newer versions) ~/.config/mozilla/firefox
      - ~/.config/mozilla/firefox:/firefox:ro
```

yt-dlp uses the most recently used profile below that folder. If the cookies can't be read, the plugin downloads
without signing in and shows a warning in the Voidcast settings.

## API

All endpoints require a Jellyfin access token (`Authorization: MediaBrowser Token="..."` header or `ApiKey` query
parameter).

| Endpoint | |
|---|---|
| `GET /Voidcast/Info` | Plugin version, whether yt-dlp is available and whether the user may download |
| `GET /Voidcast/YouTube/Search?query=...` | YouTube Music song search results |
| `POST /Voidcast/Downloads` | Queue a download; the body is a search result as returned by the search |
| `GET /Voidcast/Downloads[/{id}]` | Status of the user's downloads (`Queued`, `Downloading`, `Importing`, `Completed`, `Failed`) |
| `GET /Voidcast/YouTube/{videoId}/StreamInfo` | Codec and bitrate of the stream a download would get |
| `POST /Voidcast/YouTube/Downloaded[/Details]` | Which of the given video ids (JSON array) are already downloaded; `Details` adds codec and bitrate of the files |
| `GET /Voidcast/YouTube/{videoId}/Audio[?format=mp3]` | The track's audio, for playback |
| `GET /Voidcast/NewReleases` | The latest scan for new releases as the user gets to see it, and the scan's status |
| `GET /Voidcast/NewReleases/Status` | What a running scan got to |
| `POST /Voidcast/NewReleases/Scan` | Starts a scan, unless one is running |
| `POST /Voidcast/NewReleases/Settings` | Administrators: `{"MinSongs": 15, "FavoritesOnly": false}`, used from the next scan |
