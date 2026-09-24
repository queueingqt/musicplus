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
Music + is kept as its own tracked repo instead (so it has its own clean
history), which means attaching it to an SDK checkout is a manual step rather
than something `git clone` alone gives you.

## Steps

1. Clone the SDK:
   ```
   git clone https://github.com/lightphone/light-sdk.git
   ```
2. Replace its placeholder `tool/` module with this repo's contents (or add this as
   a new sibling module — if you do that instead, add `include(":musicplus")` next
   to the existing `include(":tool", ...)` line in the clone's `settings.gradle.kts`,
   and update this repo's `build.gradle.kts` `project(":sdk:...")` paths if the
   module ends up at a different depth than `tool/` was):
   ```
   rm -rf light-sdk/tool
   cp -r musicplus light-sdk/tool
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
example module uses). Release builds sign with a real per-app key instead — see
"Cutting a release" below. Official distribution has no public path yet: per the
SDK's README (July 2026), Light plans to clone tools from a public git commit and
build/sign them server-side.

## Cutting a release

`scripts/release.sh <version>` (e.g. `scripts/release.sh 0.2.0`) bumps
`lighttool.toml`'s version, builds a release APK signed with a real,
per-app release key (not the shared SDK dev key), tags the commit, and
publishes a GitHub Release with the APK attached.

Before building, the script copies this repo's `src/` **and** `lighttool.toml`
into the `light-sdk` checkout: the SDK build takes the app's id, version and
permissions from the checkout's own `tool/lighttool.toml`, so a stale copy there
makes the APK report the wrong version (v0.5.0 and v0.5.1 both shipped as
versionCode 1 / 0.1.0 for that reason). After the build it reads the version back
out of the APK with `aapt2` (from the Android SDK's build-tools, found via
`ANDROID_HOME`) and stops before tagging or pushing if it doesn't match.

The release keystore itself lives outside this repo
(`~/.android/keystores/musicplus-release.jks`) and its password comes from
macOS Keychain (`security find-generic-password -a musicplus-release -s
musicplus-release-keystore-password`) — never committed. A plain
`:tool:assembleRelease` without those credentials set falls back to the
shared SDK dev key, so the build still works for anyone without the real
release identity.

## Configuring a server

First run needs a Navidrome (or other Subsonic-API) server URL, username, and
password — enter these in Settings. `ServerConfigRepository` stores them via
the SDK's shared DataStore, encrypted with an Android Keystore-backed AES/GCM
cipher (see `EncryptedPrefsCipher.kt`) — DataStore itself has no built-in
at-rest encryption, so this repo adds its own.

## What's real vs. stubbed

Every screen (`data/`, `HomeScreen`, `PlayerScreen`, and the rest) is fully wired
against the SDK's confirmed APIs — this hasn't been a scaffold-with-gaps for a
while now. Known, deliberate gaps (all SDK-level limitations, not missing app
code — check the tracked-issues list for the current, authoritative set):

- No hardware volume key control, no Bluetooth device picker, no lock-screen
  now-playing integration, no Data Saver Mode detection — all confirmed
  platform limitations, not app bugs.
- **Haptics run on a temporary workaround (issue #21, kept open).** The SDK only
  enables tap haptics once LightOS's SDK server hands over the user's Haptic
  Feedback setting, and this app can't reach that server (its `serverPackage` is the
  emulator's, and LightOS 582 refuses dev-signed callers). `HapticsWorkaround.kt`
  asks LightOS itself, follows its answer when it gets one, and defaults to on when
  it doesn't. Until official Light SDK support lands, a user who has turned Haptic
  Feedback off in LightOS still gets haptics here. Delete the file, and the
  one-line call in `MusicPlusTheme.kt`, once it does. Grep for
  `WORKAROUND(lightsdk-haptics)`.
- `LightProgressBar` (a real SDK component) isn't used yet — Now Playing shows
  position/duration as text instead.
- No way to open a browser or trigger a package install from a tool — the SDK
  build plugin blocks both at compile time, and the relevant permission isn't
  in the allowlist. Relevant to the update checker (Settings shows a version +
  link as plain text, not a tappable one) and to distribution generally.
