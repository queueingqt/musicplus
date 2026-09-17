# Lightwave

A full-featured music player for the [Light Phone III](https://www.thelightphone.com/),
built on Light's own [Light SDK](https://github.com/lightphone/light-sdk), streaming
from a self-hosted [Navidrome](https://www.navidrome.org/) server (or any other
Subsonic-API-compatible server — Gonic, Airsonic, etc.).

**Status: scaffold.** The library/search/playback data layer and the app's
architecture are real and wired together; several screens are stubs following the
established pattern rather than fully polished UI. See the `TODO`s scattered through
`src/` for what's simplified — they're deliberate, not oversights.

## Features

- Browse by album or artist (ID3-tagged library, via `getAlbumList2`/`getArtists`)
- Search across artists, albums, and tracks (`search3`)
- Favorites — star/unstar artists, albums, and tracks, synced with the server
- Download tracks for offline listening, queued through the SDK's background work
  API so downloads survive leaving the screen
- Full transport controls — play/pause, ±15s skip, next/previous track, shuffle,
  repeat — via the SDK's detached audio player, so playback survives navigating
  away from the Now Playing screen or the phone locking
- Album art, pulled from the server's `getCoverArt`
- Local Room cache of the library so the last-synced view is usable offline

## How it's built

- **Kotlin + Jetpack Compose + Coroutines + MVVM**, per the Light SDK's own
  conventions — see `SETUP.md` for the SDK's own reference notes gathered while
  scaffolding this.
- **Networking**: [Ktor](https://ktor.io/) + OkHttp engine, talking directly to the
  Subsonic REST API (`src/.../data/Subsonic*.kt`) — there's no Navidrome-specific
  SDK, just the open Subsonic protocol, so this also works against other
  Subsonic-compatible servers.
- **Persistence**: Room (library cache, favorites, download index, play queue) +
  DataStore (server connection settings), both routed through the SDK's own
  sandboxed storage primitives rather than raw Android APIs.
- **Playback**: the SDK's `LightAudio` / detached-audio player — a single shared
  player instance for the tool's lifetime (see `PlaybackRepository` /
  `PlaybackRepositoryHolder`), so it keeps playing when you leave the Now Playing
  screen.

## Project layout

```
lighttool.toml                 tool manifest (id, permissions, capabilities)
build.gradle.kts               module build file (see SETUP.md — this repo is NOT
                                independently buildable, see below)
src/main/kotlin/com/lightwave/app/
  LightwaveEntryPoint.kt        optional SDK entry point (currently a no-op)
  LightwaveModels.kt            UI-facing domain models (Artist/Album/Track/...)
  HomeScreen.kt                 @InitialScreen — navigation hub
  PlayerScreen.kt                now playing / transport controls
  SearchScreen.kt, AlbumListScreen.kt, AlbumDetailScreen.kt,
  ArtistListScreen.kt, ArtistDetailScreen.kt, FavoritesScreen.kt,
  DownloadsScreen.kt, SettingsScreen.kt
                                 remaining screens (built following HomeScreen's
                                 and PlayerScreen's pattern)
  data/
    Subsonic{Client,Api,Dtos}.kt  Subsonic/Navidrome REST client
    ServerConfigRepository.kt     server URL/credentials (DataStore-backed)
    SubsonicApiHolder.kt          lazy API-client resolution (see file for why)
    Local{Entities,Daos}.kt,
    LightwaveDatabase.kt          Room cache
    LibraryRepository.kt          cache-then-network library access
    DownloadRepository.kt         download queue (SDK background work)
    PlaybackRepository.kt         wraps the SDK's audio player
    AppGraph.kt                   composition root
```

## Building this

This module is **not** independently buildable — see `SETUP.md` for why (short
version: the Light SDK isn't published as a Maven artifact yet; a tool is built as
a module inside a clone of `lightphone/light-sdk`) and the exact steps to build,
run against the SDK's emulator, and sideload onto a real device.
