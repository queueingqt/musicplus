# Building and running Music +

## Why this repo isn't buildable on its own

The Light Phone III SDK (`github.com/lightphone/light-sdk`) isn't published as a
Maven artifact — every module (`:tool`, `:examples:weather`, ...) is built as a
Gradle **project reference** inside the SDK's own multi-project build
(`implementation(project(":sdk:client"))`, etc.), sharing that repo's root Gradle
files, version catalog, and dev signing key. That's also how the SDK's own docs
describe `:tool`: "the scaffold dev environment in which you should write your
custom Light Phone tool." There's currently no way to pull the SDK in as a
dependency from a separate, independent repo.

Every real community fork found while researching this (`gauravmallya/light-apps`,
`tattaccato/light-sdk`, `zduvall/light-sdk`) is a literal fork of the whole
`light-sdk` repo, with the developer's tool added as/instead of the `tool/` module.
Music + is kept as its own tracked repo instead (so it has clean history and
lives on this NAS's Forgejo like everything else), which means attaching it to an
SDK checkout is a manual step rather than something `git clone` alone gives you.

## Steps

1. Clone the SDK:
   ```
   git clone https://github.com/lightphone/light-sdk.git
   ```
2. Replace its placeholder `tool/` module with this repo's contents (or add this as
   a new sibling module — if you do that instead, add `include(":lightwave")` next
   to the existing `include(":tool", ...)` line in the clone's `settings.gradle.kts`,
   and update this repo's `build.gradle.kts` `project(":sdk:...")` paths if the
   module ends up at a different depth than `tool/` was):
   ```
   rm -rf light-sdk/tool
   cp -r lightwave light-sdk/tool
   ```
3. Build:
   ```
   cd light-sdk
   ./gradlew :tool:assembleDebug
   ```
4. Run against the SDK's own emulator (no physical device needed) — see the SDK's
   `docs/system_app/README.md`. Recommended AVD per the SDK's own README: 1080×1240,
   3.92" display, API 34, no Google Play Services.
5. Sideload onto a real Light Phone III via the on-device Tool Manager, or the
   `:tool:uploadTool` Gradle task the SDK defines (`docs/sideloading/README.md`) —
   as of the SDK's own docs (2026-08-25), this pipeline "has not yet been rolled out
   to LightOS production builds," so device sideloading may not work yet regardless
   of what the build produces. The emulator path is the reliable one for now.

Debug/dev builds sign with the SDK's own shared dev keystore
(`sdk/keys/lightsdk-dev.jks`, alias `lightsdk-dev`, password `android` for both
store and key — not a secret, it's the SDK's public dev-only key, same one every
example module uses). Official distribution has no public path yet: per the SDK's
README (July 2026), Light plans to clone tools from a public git commit and
build/sign them server-side.

## Configuring a server

First run needs a Navidrome (or other Subsonic-API) server URL, username, and
password — enter these in Settings. `ServerConfigRepository` stores them via the
SDK's shared DataStore; see its file comment for a known gap (the password isn't
encrypted at rest yet).

## What's real vs. stubbed

The data layer (`data/`), `HomeScreen`, and `PlayerScreen` are fully wired against
the SDK's confirmed APIs. The remaining screens (`SearchScreen`, `AlbumListScreen`,
`AlbumDetailScreen`, `ArtistListScreen`, `ArtistDetailScreen`, `FavoritesScreen`,
`DownloadsScreen`, `SettingsScreen`) follow the same `LightScreen`/`LightViewModel`
pattern but are lighter — check each file's own comments for what's simplified.
Search `TODO` across `src/` for the complete list of known gaps, notably:

- Shuffle/repeat toggle state exists but isn't wired to actual queue reordering or
  end-of-track looping yet — `LightAudioPlayer`'s confirmed public surface has no
  track-completed callback to hook that up to (see `PlaybackRepository.kt`).
- Server password is stored unencrypted in DataStore (see `ServerConfigRepository.kt`).
- `LightProgressBar` (a real SDK component) isn't used yet — Now Playing shows
  position/duration as text instead, since this component's exact signature wasn't
  confirmed while scaffolding.
- Issue tracked on this repo: a Winamp-style spectrum analyzer for Now Playing,
  starting from the SDK's own `examples/audio-demo/.../SpectrumAnalyzer.kt`.
