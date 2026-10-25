# Voidcast

A music player for **Jellyfin** servers that brings **YouTube Music** right next to the music already in the library.

A search shows the songs already in the library alongside what's on YouTube Music. Anything can be played straight
away, music videos included, and a single click downloads a song into the library, properly tagged and with its
cover. Voidcast runs on Android, on the desktop (Linux, Windows, macOS) and in the browser.

## Features

- Listen to music from your Jellyfin library and YouTube Music all in one place.
- Download songs from YouTube and add them to your Jellyfin playlists, fully tagged and in the best available quality.
- Manage playlists and favorites.
- Browse the library by playlists, artists, albums, songs, genres or favorites, sorted alphabetically, by release date or by date added, either way round.
- Select several songs or albums: with Ctrl- and Shift-clicks on the desktop, on Android by long pressing and moving the finger across them, like in a photo gallery.
- Synced lyrics.
- Gapless playback and crossfade
- Offline listening on Android.
- Remote control of other devices.
- YouTube music videos
- basic support for Navidrome and other Subsonic servers (YouTube integration and remote control require Jellyfin instead).

## Requirements

- A **Jellyfin** server (version 12.2 or newer).
- The **Voidcast Jellyfin plugin** on that server for everything YouTube Music related.

To install the plugin, add this repository in Jellyfin under *Dashboard → Plugins → Repositories*:

```
https://raw.githubusercontent.com/ronnyporsch/Voidcast/plugin-repository/manifest.json
```

Then install *Voidcast* from the catalog and restart Jellyfin. Updates will show up in the catalog
automatically. The [plugin README](./jellyfin-plugin/README.md) explains the settings, for example how to sign in to
YouTube.

Without the plugin, Voidcast works as a regular Jellyfin music player.

## Installing the app

### Arch Linux

```sh
cd packaging/arch && makepkg -si
```

This adds Voidcast to the application menu and a `voidcast` command. Music videos additionally need `vlc`.

### Windows, macOS and other Linux systems

Build an installer on the system it is meant for:

- Linux (Debian/Ubuntu): `./gradlew :desktopApp:packageDeb`
- Windows: `./gradlew :desktopApp:packageMsi`
- macOS: `./gradlew :desktopApp:packageDmg`

The installer ends up in `desktopApp/build/compose/`. Music videos on the desktop need [VLC 3](https://www.videolan.org/).

### Android

`./gradlew :androidApp:assembleDebug` builds the app at `androidApp/build/outputs/apk/debug/androidApp-debug.apk`.
The server has to be reachable over HTTPS, as Android doesn't allow unencrypted connections.

### Web

`./gradlew :webApp:wasmJsBrowserDistribution` builds the web app into `webApp/build/dist/`; put it on any web
server. The music server has to allow requests from the address the web app is hosted on (CORS).

### Self-signed certificates

A server with a self-signed certificate can be trusted on the login screen or in the settings (Android and desktop).
In the web app, the browser decides.

## Development

See [DEVELOPMENT.md](./DEVELOPMENT.md) for building, testing and how the project is organized.
