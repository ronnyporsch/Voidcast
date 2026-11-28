<p align="center">
  <img src="desktopApp/icons/icon.png" alt="Voidcast icon" width="128">
</p>

# Voidcast

[![Jellyfin](https://img.shields.io/badge/jellyfin-12.2%2B-00A4DC?logo=jellyfin)](https://jellyfin.org/)
![Linux](https://img.shields.io/badge/Linux-FCC624?logo=linux&logoColor=black)
![Windows](https://img.shields.io/badge/Windows-0078D6?logo=data:image/svg%2bxml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCI+PHBhdGggZmlsbD0id2hpdGUiIGQ9Ik0wIDBoMTEuNHYxMS40SDB6TTEyLjYgMEgyNHYxMS40SDEyLjZ6TTAgMTIuNmgxMS40VjI0SDB6TTEyLjYgMTIuNkgyNFYyNEgxMi42eiIvPjwvc3ZnPg==)
![macOS](https://img.shields.io/badge/macOS-000000?logo=apple&logoColor=white)
![Android](https://img.shields.io/badge/Android-3DDC84?logo=android&logoColor=white)
![Web](https://img.shields.io/badge/Web-654FF0?logo=webassembly&logoColor=white)

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