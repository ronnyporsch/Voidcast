<p align="center">
  <img src="desktopApp/icons/icon.png" alt="Voidcast icon" width="512">
</p>

# Voidcast

A music player for **Jellyfin** servers that brings **YouTube Music** right next to the music already in the library.

## Features

- Listen to music from your Jellyfin library and YouTube Music all in one place.
- Download songs from YouTube and add them to your Jellyfin playlists, fully tagged and in the best available quality
- Song radio: start a radio from any song to discover similar music from YouTube Music
- Manage Playlists
- Support for Synced lyrics
- Gapless playback and crossfade
- Offline listening: download playlists, albums and songs to your device
- Remote control of other Jellyfin clients
- Music videos
- Basic support for Navidrome and other Subsonic servers (YouTube integration and remote control require Jellyfin instead)

## Requirements

- A **Jellyfin** server (version 12.2 or newer).
- The **Voidcast Jellyfin plugin** on that server for everything YouTube Music related.

To install the plugin, add this repository in Jellyfin under *Dashboard → Plugins → Repositories*:

```
https://raw.githubusercontent.com/ronnyporsch/Voidcast/plugin-repository/manifest.json
```

Then install *Voidcast* from the catalog and restart Jellyfin. The [plugin README](./jellyfin-plugin/README.md) explains the settings, for example how to sign in to YouTube.

Without the plugin, Voidcast works as a regular Jellyfin music player.

## Installing the app

### Arch Linux

```sh
cd packaging/arch && makepkg -si
```

This adds Voidcast to the application menu and a `voidcast` command.

### Windows, macOS and other Linux systems

Build an installer on the system it is meant for:

- Linux (Debian/Ubuntu/Mint): `./gradlew :desktopApp:packageDeb`
- Linux (Fedora/OpenSUSE/RHEL): `./gradlew :desktopApp:packageRpm`
- Windows: `./gradlew :desktopApp:packageMsi`
- macOS: `./gradlew :desktopApp:packageDmg`

The installer ends up in `desktopApp/build/compose/`. The Desktop version requires [VLC 3](https://www.videolan.org/) to be installed.

### Android

`./gradlew :androidApp:assembleRelease` builds the app at `androidApp/build/outputs/apk/release/androidApp-release.apk`.

### Web

`./gradlew :webApp:wasmJsBrowserDistribution` builds the web app into `webApp/build/dist/`

## Development

See [DEVELOPMENT.md](./DEVELOPMENT.md) for building, testing and how the project is organized.