# Music +

A full-featured music player for the [Light Phone III](https://www.thelightphone.com/),
built on Light's own [Light SDK](https://github.com/lightphone/light-sdk), streaming
from a self-hosted [Navidrome](https://www.navidrome.org/) server (or any other
Subsonic-API-compatible server — Gonic, Airsonic, etc.).

<p float="left">
  <img src="screenshots/now-playing.png" width="200" alt="Now Playing screen, showing album art, transport controls, and shuffle/repeat/favorite/lyrics toggles" />
  <img src="screenshots/queue.png" width="200" alt="Full queue screen, with the current track marked and reorder/remove controls on upcoming tracks" />
  <img src="screenshots/lyrics.png" width="200" alt="Synced lyrics screen, with the currently-sung line highlighted" />
  <img src="screenshots/albums.png" width="200" alt="Albums list, with favorite stars and a persistent mini-player" />
</p>

## Features

### Library

- Browse by **Albums**, **Artists**, or a flat **Songs** list (every track in the
  library, not just ones already pulled in via a visited album/playlist/search)
- **Search** across artists, albums, and tracks
- **Favorites** — star/unstar artists, albums, and tracks independently (an album
  favorite doesn't imply its tracks are favorited, or vice versa), synced with the
  server and queued for retry if the write fails offline
- **Playlists** — create, rename, delete, and reorder tracks within a playlist, all
  synced with the server
- Album art, cached locally after first fetch
- A local Room cache of the library, favorites, and download index so the
  last-synced view stays usable offline

<p float="left">
  <img src="screenshots/home.png" width="200" alt="Main menu: Albums, Artists, Songs, Playlists, Queue, Favorites, Settings" />
  <img src="screenshots/album-detail.png" width="200" alt="Album detail screen with track list and a favorite star on the title" />
</p>

### Playback

- Full transport controls — play/pause, ±15s skip, next/previous track — via the
  SDK's detached audio player, so playback survives navigating away from Now
  Playing or the phone locking
- **Shuffle** (reshuffles only the upcoming portion of the queue, restoring the
  original order exactly when turned back off) and **repeat** (off / repeat queue /
  repeat one track) — shuffle and repeat-one are mutually exclusive, since
  repeating a single track forever makes an upcoming shuffled order meaningless;
  repeat-queue and shuffle can run together
- A visible loading indicator between tapping a track and playback actually
  starting, instead of the screen appearing to do nothing
- **The full queue survives an app restart** — song order, current track,
  position, and shuffle/repeat mode are all restored, without forcing a network
  fetch until you actually press play
- **QueueScreen** — the entire queue (not just "up next"), with per-row reorder
  (▲/▼) and remove, a "clear queue" action that leaves whatever's currently
  playing alone, and tap-to-jump: tapping any row plays it immediately
- **Lyrics** — synced (line-by-line, current line highlighted as it plays) or
  plain text, fetched from the server on demand
- Works against a plain `http://` server, not just `https://` — Android blocks
  cleartext streaming outright, so tracks are downloaded then played from a local
  cache instead of streamed directly when the configured server isn't HTTPS
- Download tracks for offline listening, queued through the SDK's background work
  API so downloads survive leaving the screen

### Everywhere else

- A persistent mini-player (track title/art, play/pause, skip) on every screen
  except Now Playing itself, tap-through to Now Playing or the full queue
- A long-press action menu — favorite, add to queue, download — reachable from
  track rows, the album list, an artist's own album list, and the album detail
  screen
- Scroll position is preserved when navigating into a list item and back
- Server connection (URL, username, password) is configurable in Settings, with
  support for saving more than one server profile
- Preferences: show/hide album artwork, haptic feedback, debug logging (writes a
  local crash/error log independent of Logcat, pullable via adb for field
  debugging without a live session)

## How it's built

- **Kotlin + Jetpack Compose + Coroutines + MVVM**, per the Light SDK's own
  conventions — see `SETUP.md` for the SDK's own reference notes gathered while
  building this.
- **Networking**: [Ktor](https://ktor.io/) with the **CIO** engine (not OkHttp) —
  many self-hosted Subsonic servers run plain `http://` on a LAN/tailnet, and
  OkHttp's Android platform integration enforces Android's default
  cleartext-traffic block with no override available here; CIO's pure-Kotlin
  engine isn't subject to that check. Talks directly to the open Subsonic REST
  API (`src/.../data/Subsonic*.kt`), so this also works against any other
  Subsonic-compatible server, not just Navidrome.
- **Persistence**: Room (library cache, favorites, download index, play queue,
  offline sync queue for failed writes) + DataStore (server connection settings,
  app preferences, playback resume state), both routed through the SDK's own
  sandboxed storage primitives rather than raw Android APIs.
- **Playback**: the SDK's `LightAudio` / detached-audio player — a single shared
  player instance for the tool's lifetime (see `PlaybackRepository` /
  `PlaybackRepositoryHolder`), so it keeps playing when you leave the Now Playing
  screen.
- **Offline-first writes**: a favorite toggle or playlist edit that fails (no
  server configured, or a transient network error) queues locally and replays
  automatically once the server's reachable again, backed by both an immediate
  reconnect listener and a periodic background job.

## Project layout

```
lighttool.toml                 tool manifest (id, permissions, capabilities)
build.gradle.kts               module build file (see SETUP.md — this repo is NOT
                                independently buildable, see below)
src/main/kotlin/com/musicplus/app/
  MusicPlusEntryPoint.kt        SDK entry point
  MusicPlusModels.kt            UI-facing domain models (Artist/Album/Track/...)
  MusicPlusScaffold.kt          shared top bar / mini-player / bottom bar shell
  HomeScreen.kt                 @InitialScreen — navigation hub
  PlayerScreen.kt                now playing / transport controls
  QueueScreen.kt                 full queue, reorder/remove/jump
  LyricsScreen.kt                synced/plain lyrics
  SearchScreen.kt, AlbumListScreen.kt, AlbumDetailScreen.kt,
  ArtistListScreen.kt, ArtistDetailScreen.kt, SongsListScreen.kt,
  FavoritesScreen.kt, PlaylistListScreen.kt, PlaylistDetailScreen.kt,
  PlaylistPickerScreen.kt        remaining library/browse screens
  ActionsMenuScreen.kt           shared long-press action menu
  SettingsScreen.kt, ServerSettingsScreen.kt, ServerEditScreen.kt,
  PreferencesScreen.kt           server config / app settings
  data/
    Subsonic{Client,Api,Dtos}.kt  Subsonic/Navidrome REST client
    ServerConfigRepository.kt     server URL/credentials (DataStore-backed)
    SubsonicApiHolder.kt          lazy API-client resolution (see file for why)
    Local{Entities,Daos}.kt,
    MusicPlusDatabase.kt          Room cache
    LibraryRepository.kt          cache-then-network library access
    PlaylistRepository.kt         playlist CRUD
    DownloadRepository.kt         download queue (SDK background work)
    PlaybackRepository.kt         wraps the SDK's audio player
    PlaybackStateRepository.kt    persisted resume state (queue index/position/mode)
    LyricsRepository.kt           synced/plain lyrics fetch
    SyncQueueRepository.kt        offline write queue + retry
    AppGraph.kt                   composition root
```

## Building this

This module is **not** independently buildable — see `SETUP.md` for why (short
version: the Light SDK isn't published as a Maven artifact yet; a tool is built as
a module inside a clone of `lightphone/light-sdk`) and the exact steps to build,
run against the SDK's emulator, and sideload onto a real device.
