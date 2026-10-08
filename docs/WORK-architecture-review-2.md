# Work doc: architecture review 2 (2026-10-08)

The second whole-tool review. The first (issues #57-#79) is merged and is not repeated here. The rendered report was a
temporary HTML file; everything it said that matters is in the issues and below.

Three parts, to be done in this order:

1. **Perform all 12 suggested fixes** (issues #81-#92).
2. **Check and do all the "defects turned up on the way"** (12 items). Each is either fixed by one of the 12, or is a
   standalone fix, and the "from reading" ones are verified before they are fixed.
3. **Work through "Smaller and speculative" together** (5 items, broken down below). These are decisions for the user.
   Nothing in part 3 is done without a conversation.

## Working agreement

Carried over from the first review (see `arch-review-execution` in project memory):

- **Branch** `refactor/architecture-review-2` off `main`. **One commit per issue**, issue number in the subject
  (`... (#81)`). A defect fixed on its own gets its own commit with the defect number in the subject. **Nothing is pushed.**
- **Comment each issue with its fix hash** (Forgejo route in `forgejo-issue-route`). **Closing is left to the user.**
- **Test on the Light Phone III, not an emulator.** Do not start the emulator or Docker.
- **JVM tests** go in `tool/src/test/kotlin` (Light's builder reads only `tool/src/main`). New modules are pure so their
  tests need no device. Run them in a scratch clone of the SDK, not in `~/github/light-sdk`, which must stay clean:
  clone `https://github.com/lightphone/light-sdk` into the scratchpad, copy `tool/` over its `tool/`, then
  `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :tool:testDebugUnitTest`. JDK 26 fails (jlink); use 21.
- **Before each commit that touches `tool/src/main`**, also run `:tool:assembleRelease -DlightSdk.unsigned=true` in that
  scratch clone. It is what Light's server runs, and it enforces the SDK's banned-code patterns.
- **Behaviour changes need a phone check** (adb recipe in `musicplus-device-debug-loop`). Pure moves need only tests.
- **Review before commit**: anything delegated is reviewed in the seat before it is committed.

## Part 1: the 12 fixes (plus #93, added later)

Order matters because some issues build on others. Tick a box only when the commit exists, the tests pass, and (where it
changes behaviour) the phone check is done. Record the commit hash.

| Done | Order | Issue | Fix | Depends on / pairs with | Defects fixed by it |
|---|---|---|---|---|---|
| [x] | 1 | #81 | Downloads module | none | D3, D4 |
| [x] | 2 | #83 | Write queue: offline vs refused | introduces the failure classification #91 reuses | D7 |
| [x] | 3 | #86 | Advance module (what plays next) | none; relates to #78 | D1 |
| [x] | 4 | #87 | One "set favorite" behaviour | none | D2 |
| [x] | 5 | #82 | Server standing module | none | |
| [x] | 6 | #84 | Per-collection mirror | #82 helps (the star rule) | |
| [x] | 7 | #85 | FetchGate as an instance | #81 first (its first caller) | D4 |
| [x] | 8 | #91 | One request layer, injectable engine | #83 for the refused/unreachable type | D5 |
| [x] | 9 | #90 | Stores for downloads and lyrics | #81 first (the downloads half follows it) | |
| [~] | 10 | #89 | Settings declared once, read model | #82 helps | |
| [x] | 11 | #88 | Action-menu state holder | none | D9 |
| [x] | 12 | #92 | Split queue-edit rules from PlayQueue | #86 first | |
| [x] | 13 | #93 | Debug logging only in test/dev builds (added 2026-10-08) | do before #89, which may delete the setting | |

Per issue, the loop is:

1. Read the issue and re-read the files it names. Line numbers drift; the symbols are the anchor.
2. Write the tests for the behaviour first where a seam allows it (they are the acceptance criteria in the issue).
3. Make the change, deleting what it replaces. Replace, do not layer: old tests that only test a deleted shim go too.
4. Run the JVM tests and the unsigned release build in the scratch SDK clone.
5. Phone check if behaviour changed. Commit with the issue number. Comment the hash on the issue.
6. Log the outcome under "Progress log" at the bottom, including anything that did not go as the issue said.

If an issue turns out wrong once you are in the code (the seam is somewhere else, or the module was not worth it), stop,
write what was found on the issue, and take it to the user. Do not quietly do a different thing.

## Part 2: defects turned up on the way

"verified" = re-read in the code during the review. "from reading" = traced by a subagent, not run. **For every "from
reading" and "unconfirmed" item, the first step is to reproduce it** (a failing JVM test where a seam exists, otherwise a
phone check) and write the result here before fixing. If it does not reproduce, say so and drop it.

For defects fixed by an issue in part 1, the issue's tests must include the defect's case. For standalone defects, fix
them early (small, safe, and they shrink the diff of the bigger issues): do the standalone ones before issue #81.

| Done | # | Where | Defect | Status before | Fixed by | First step |
|---|---|---|---|---|---|---|
| [x] | D1 | `PlaybackRepository.kt:145,159-163` | Error retry replays the queue snapshot taken before the 750 ms delay, overwriting a jump or edit made meanwhile. The retry also blocks `collect`, so a second error is missed until it ends. | verified | #86, **or standalone first** (re-read the snapshot after the delay) | Test with `FakePlayback`: error, jump during the delay, assert the jump survives |
| [x] | D2 | `PlayerScreen.kt:189`, `TrackActions.kt:42` | Favoriting the playing track from a list menu leaves Now Playing's star stale (`updateTrackFavorite` has one caller). | verified | #87 | Test: favorite the playing track via the menu path, assert the queue snapshot |
| [x] | D3 | `MediaIntegrity.kt:67` | A capped-quality download is saved as `.mp3`; when the track's suffix is also `mp3` the transcoded file is compared with the original size, mismatches, and is deleted and re-queued. Runs once per install (`VERSION = 1`). | verified | #81 | Test: capped download of an mp3 track passes the check. Also decide: does this need a `VERSION` bump to repair files already wrongly discarded? (They were re-queued, so probably not; check on the phone.) |
| [x] | D4 | `DownloadRepository.kt:232` | Every `IOException` counts as congestion and halves the FetchGate limit, including `server answered 404` and `cut short` from `streamToFile`. | verified | #81 then #85 | Test: a 404 does not call `onCongestion` |
| [x] | D5 | `SubsonicClient.kt:205-209`, `CoverArtStore.kt:135-137` | `getBytes` never checks the status or the Subsonic error envelope (Jellyfin's does), and the store caches whatever came back as art, forever. | verified | #91, **or standalone first** (status check in `getBytes`, validate before writing) | Test with a stub that answers 200 + an error body; **also scan the phone's existing cover-art cache for non-image files** and clear any found |
| [x] | D6 | `VersionCheckRepository.kt:41-47`, `AppGraph.kt:208-214` | A failed check (offline at launch) returns Unit and `setLastVersionCheckAtMs(now)` runs anyway, suppressing the check for 24 h. `runCatching` also swallows `CancellationException`. | from reading | standalone | Test: a failing fetch leaves `lastCheckedAt` untouched; make `checkForUpdate` return success/failure |
| [x] | D7 | `LocalFirstWrites.kt:139-147,255-290` | A permanently refused write (e.g. delete of an offline-created playlist: `deletePlaylist(<server>:pending:uuid)`) is queued and fails every drain, blocking all later edits for that server. | from reading | #83 | Test: the poison-head scenario with a fake server that throws a refusal; **then check the phone's real queue** (`PendingMutation` table, via adb run-as) for rows already stuck |
| [x] | D8 | `LazyCollapsibleSection.kt:34-44`, `ArtistDetailScreen.kt:74-77` | `loaded = true` and `_loading = true` are set before `fetch()` with no try/finally, so a throwing fetch leaves "Loading…" forever and the exception escapes `viewModelScope.launch`. Top-songs reads `artist.value?.name` at expand time; if the artist has not emitted it returns empty, and because `loaded` is already true it never retries. | from reading | standalone | Test the section with a throwing fetch; phone: expand Top Songs immediately on opening an artist |
| [x] | D9 | `ActionsMenuScreen.kt:248-257` | `busy = true` with no try/finally; a throwing `Perform` leaves every row dead until back, and the throw has no handler. A `liveUpdates` emission that replaces the row during the action drops the result (`it === item`). | from reading | #88 | Reproduce with a throwing action on the phone or in the holder's test once it exists |
| [x] | D10 | `ServerEditScreen.kt:262-270`, `data/ServerLifecycle.kt:93-105` | After the menu's delete nothing navigates back; the screen stays with Save active, and `save(existingId, ...)` would call `addOrUpdate` and re-add the deleted server. | from reading, unverified at runtime | standalone | Phone: delete a server from its edit screen, then press Save. If it re-adds, navigate back after remove and guard `save` |
| [x] | D11 | `TrackListDownload.kt:114` | `downloadEntireArtist` uses `observeAlbumsByArtist(artistId).first()` without `refreshArtistDetail` first; the caller (`ArtistListScreen`) does not refresh, unlike `ArtistDetailScreen.onScreenShow`. May silently do nothing. | unconfirmed | standalone | Phone: long-press an artist in the list that was never opened, choose download, see whether anything queues |
| [x] | D12 | `MaskedTextInputEditor.kt:86`, `MaskedTextEditScreen.kt`, `TextEditScreen.kt`, `PlayerScreen.kt:169` | Stale comments: the masked editor is described as "only used by SettingsScreen" but `ServerEditScreen.kt:233` uses it; `PlayerScreen.kt:169` says "see [tap]" which is actually `DownloadStates.kt`'s `DownloadRepository.tap`. | verified | standalone | Fix the comments |

Standalone fixes: all done (see the progress log).
Their results go in the progress log.

### Also noticed (not in the rendered report; triage with the defects)

Dead code and small things the library pass reported. Confirm each by grep before deleting.

- `SubsonicApi.downloadUrl` (`data/SubsonicApi.kt:257`) has no caller.
- `getIdsFor` on all four DAOs, and `ArtistDao.getById` / `AlbumDao.getById`, are never called.
- Unused import `WriteOutcome` in `LibraryRepository.kt:8` and `PlaylistRepository.kt:7`.
- `DownloadStatus.DOWNLOADING` is never written (also noted in #81; decide there).
- `JellyfinApi.getAlbum` (`:155`) sorts by `IndexNumber` only, no disc number, so multi-disc albums may interleave.
  **Unconfirmed**: check against the real Jellyfin test server (`jellyfin-test-server` memory).
- `JellyfinApi.getSimilarArtists` (`:313`) always returns empty and the capability is not modelled, so callers cannot tell
  "none" from "unsupported".
- `CapabilityRegistry.watch` probes Jellyfin servers although `JellyfinApi.probeCapabilities` is hard-coded (a no-op with
  `ServerWork` overhead).
- `SyncQueueRepository.move` reads the local order and then writes with no lock between; two quick taps could read the same
  order. Not verified that callers serialise this.
- `MusicPlusScaffold.kt:226-236` and `PlayerScreen.kt:277-287` repeat the same play/pause/loading icon and label `when`.
- `playback.state.screenState(viewModelScope, playback.currentSnapshot())` is repeated in `PlayerScreen.kt:128`,
  `LyricsScreen.kt:60`, `QueueScreen.kt:38`, `AlbumArtScreen.kt:33`.
- The `albumArtId` combine-and-seed block is copied between `PlayerScreenViewModel` and `AlbumArtScreenViewModel`.
- `toggleTrackListDownload` (`TrackListDownload.kt:48-56`) reads three flows with three separate `first()` calls (low).

## Part 3: Smaller and speculative, to work through together

Five items. **None is started without the user.** For each: what it is, the evidence, the options, a recommendation, and
the question to settle. Fill in "Decision" when decided, then it becomes a small task (or is dropped).

### S1. Pickers: Quality and SleepTimer repeat the same rule (Worth exploring)

- **What.** Both screens show preset rows, a "Custom (N unit)" row that exists only when the current value is not a preset,
  and a Custom route through `NumericEntryScreen` then `goBack`.
- **Evidence.** `QualityPickerScreen.kt:74-88`, `SleepTimerPickerScreen.kt:124-145`. The result type is bent around "null
  means cancelled" (`QualitySelection` wraps the nullable Int, `QualityPickerScreen.kt:25`). Back is hand-wired as
  `LightBarButton.LightIcon(icon = BACK, onClick = { goBack() })` in 25 files. Four ViewModels exist only because
  `LightScreen` requires one (`ActionsMenuScreenViewModel`, `VersionAvailableScreenViewModel`,
  `QualityPickerScreenViewModel`, `SaveToScreenViewModel`). `ConfirmModal` and `NoteModal` hand-roll the same
  `CompletableDeferred` dismiss plumbing.
- **Also here (untested logic in click lambdas).** The "Clear all local data" message builder
  (`SettingsScreen.kt:209-247`, about 35 lines of count and plural logic reading `AppLibraryCache`) and
  `serverRemovalItems` (`ServerSettingsScreen.kt:247-279`).
- **Options.** (a) A "choose a preset or a custom number" module used by both. (b) Only extract the message builders to pure
  functions with tests (the cheapest real win). (c) Leave, the screens are deliberately simple.
- **Recommendation.** (b) now, (a) only if a third picker appears.
- **Question.** Is the 25-file back-button wiring worth a helper, or is explicit better here?
- **Decision:** _open_

### S2. Light SDK gap adapters and list-row chrome (Speculative, one concrete part)

- **What.** Things the app does because the SDK lacks them: `HapticsWorkaround.kt` (temporary, has a 4-step removal recipe
  in its header, kept open as #21), `MaskedTextInputEditor.kt` (423 lines, a fork of an SDK editor; `onHaptic = {}` so no
  per-key haptics; reimplements two SDK-internal pieces), `TintedPainter`/`seekButton` faking a disabled bar button
  (`PlayerScreen.kt:70-92`), `PlaylistListTopBar` re-implementing `LightTopBar` for a second right button
  (`PlaylistListScreen.kt:150-215`), `ScrollbarGutter.kt` copying a private SDK constant, `NoteModal` as a toast.
- **Evidence.** Each passes the deletion test (delete it and callers fall back to the SDK API with the failure it hides).
  Grouping them is navigability, not depth. Haptic gating is decided in three places: the provider,
  `lightCombinedClickable`, and the masked editor (which ignores it).
- **The one concrete part.** The list-row padding
  `padding(top, bottom, start = 1f, end = SCROLLBAR_GUTTER_GRID_UNITS)` is hand-written in 9 places
  (`ActionsMenuScreen.kt:308`, `ArtistDetailScreen.kt:212,259`, `ArtistListScreen.kt:124`, `PlaylistListScreen.kt:143`,
  `QueueScreen.kt:189`, `PlaylistPickerScreen.kt:175`, `ServerSettingsScreen.kt:221,288`, `TrackRow.kt:63`), and a private
  `listRowPadding` already exists at `ListRows.kt:31`.
- **Options.** (a) Make `listRowPadding` shared and use it in the 9 places. (b) Also gather the workarounds in one place,
  each with its removal condition. (c) Do nothing beyond (a).
- **Recommendation.** (a) only. (b) is tidiness; the existing per-workaround seams are fine.
- **Question.** Any workaround here you already know Light is about to fix in the SDK (so it should not be touched)?
- **Decision:** _open_

### S3. Screens that survive navigation, handled ad hoc (Speculative)

- **What.** `Content()` is disposed while another screen is on top, and each screen rediscovered that and worked around it
  differently (all found on a device, per the comments).
- **Evidence.** `ScrollPosition.kt` documents the root cause. `SearchScreen.kt:81,112-134` (`hasAutoOpenedEditor`,
  `leavingBlank`), `PlaylistDetailScreen.kt:50-67,115-118` (`reorderMode`, `deleted`, a `leaving` snapshot),
  `CollectionActions.kt:46-52` (takes the ViewModel's `scope`), `PlaylistListScreen.kt:48-62` and
  `PlaylistPickerScreen.kt:40-45` (ViewModel-scoped creates because a composable-scope launch from a result callback
  "silently never ran"), the `remember(this)` Actions objects. `ListScreenViewModel` is the only shared base and only holds
  scroll position.
- **Options.** (a) A screen-survival base: round-trip state holders, a "leave when next shown" flag, result-callback
  launching on the ViewModel scope by default. (b) Leave, each workaround is small and local.
- **Recommendation.** (b). The behaviour belongs to the SDK, which the reviewer did not read; a shared module here would be
  an adapter over behaviour known only from comments. Revisit if a fourth screen needs the same workaround.
- **Question.** Do you want me to read the SDK's navigation first, so the decision rests on code and not comments?
- **Decision:** _open_

### S4. SyncQueueRepository is mostly one-line forwards (Speculative)

- **What.** About ten methods are `writes.write(id, PendingMutation.X(...))`. `LocalFirstWrites` is the deep module. The
  only real logic is `createPlaylist` (placeholder id) and `move` (read the local order, build a full reorder).
- **Evidence.** `data/SyncQueueRepository.kt:20-95`. Deleting it would push the `PendingMutation` constructors out to about
  25 screens and ViewModels: mostly relocation. The job-trigger tags (`-reconnect`, `-favorites`) belong to its own job but
  are built in `AppGraph.kt:315,329`.
- **Options.** (a) Keep it as a vocabulary adapter, move the trigger-tag knowledge next to the job, test `move` and
  `createPlaylist`. (b) Delete it. (c) Leave.
- **Recommendation.** (a). Note #83 changes what sits underneath, so decide after #83 lands.
- **Question.** None needed before #83; revisit then.
- **Decision:** _deferred until #83_

### S5. Small singletons with hidden dependencies (Speculative)

- **What.** `VersionCheckRepository`, `AppLogger`, `TrackAvailability` (and `CrashReporter`, now removed).
- **Evidence.** `VersionCheckRepository` and `CrashReporter` hold the same `queueingqt/musicplus` constants.
  `TrackAvailability.init(cache)` is a global that must be called before use. `CrashReporter` reads the log path
  `logs/app.log` by convention instead of asking `AppLogger` (`CrashReporter.kt:65-68`). `isNewer` is a pure helper with no
  test. The 24-hour-throttle bug in this area is D6.
- **Settled 2026-10-08.** `CrashReporter` and its "Report Crashes" toggle were removed (code, the `GITHUB_CRASH_TOKEN`
  build field, the AppGraph wiring, the Settings row). Local debug logging (`AppLogger`) stays on its stored setting,
  default on, with no UI to change it. The setting itself (`debugLoggingEnabled`) and its mirror remain, so #89 should
  decide whether to keep a setting nobody can change. The LICENSE file and the once-per-launch GitHub release check
  (disclose it in the README) are still open for the Light submission.
- **Options now.** (a) Move the shared `queueingqt/musicplus` constant out of `VersionCheckRepository`, and give `isNewer`
  a test. (b) Leave.
- **Recommendation.** (a), after D6 (the throttle bug in `checkForUpdate`).
- **Decision:** _CrashReporter removed; the rest open_

## Progress log

(append below: date, issue or defect, commit hash, what was verified on the phone, anything that differed from the plan)

- 2026-10-08: **D1 done** `cac0af9`. Retry now launched, reads the queue after the delay, and only applies if the same song is
  still current at the same index (`ErrorRecovery.retryStillApplies`, 1 test). Not checked on the phone. #86 may still absorb
  the watcher glue.
- 2026-10-08: **D5 done** `cae546a`. `SubsonicClient.getBytes` now throws on a non-success status and on a JSON/XML/text body
  (Subsonic's 200 error envelope); `CoverArtStore` keeps only bytes that start like an image and drops a non-image already on
  disk, then asks again, so bad art already cached on the phone heals itself (2 new tests; test helper now makes JPEG-signed
  bytes). Not checked on the phone. #91 will still fold `getBytes` into the shared request layer.
- 2026-10-08: **#93 filed** (debug logging only in test/dev builds), from the user's request after `CrashReporter` was removed.

- 2026-10-08: issues #81-#92 filed; this doc written. No fix started. The Light-tool submission restructure
  (`tool/` layout, SDK 0.2.0 compile fixes) is already on `main` (`da11073`, `13adb46`), unpushed.
- 2026-10-08: **D6 done** `dcf5ebf`. `checkForUpdate` returns whether GitHub answered and takes an injectable fetch; AppGraph stamps
  `lastVersionCheckAt` only on an answer (2 tests). Not checked on the phone.
- 2026-10-08: **D8 done** `33f3ba2`. `LazyCollapsibleSection` stops loading and resets on a failed fetch, so the next expand
  retries; Top songs waits up to 5 s for the artist, and fails (retryable) rather than returning empty (2 tests). Not checked
  on the phone.
- 2026-10-08: **D12 done** `02e98b6`. Comments only.
- 2026-10-08: **D10 done** `17688b9`. Confirmed by reading: `save(existingId, ...)` calls `addOrUpdate`, so Save after a
  delete re-adds the server, and nothing left the screen. The screen now records `removed`, leaves on its next show (same shape
  as `PlaylistDetailScreen`'s `leaving`), and `save` is a no-op once removed. **Not tested on the phone**: a throwaway server
  cannot be saved (the login check needs a real server) and deleting the user's real server is not a test. Worth a manual check
  next time a server is added.
- 2026-10-08: **D11 closed, no change.** Pulled the phone's database (read-only): 2,339 albums are mirrored by the album-list
  refresh, and 1,238 of 1,312 artists have albums cached without ever being opened, so `observeAlbumsByArtist` is populated. The
  74 artists with none are artists with no albums of their own (not checked individually). The unrefreshed-artist case does not
  arise in practice.
- All 298 JVM tests pass after the standalone fixes; the unsigned release build passes.
- 2026-10-08: **#81 done** `54ca384`. `DownloadRepository` owns status transitions, the path rule, scheduling and the job's work
  (`run` -> Done/Retry/Failed) behind `DownloadScheduler`, `DownloadSources`, `DownloadGate` and a clock; `downloadTrack` (now
  `DownloadJob.kt`) is a thin adapter; callers no longer pass a `SealedLightContext`. 11 new tests (309 total). **D3 done**
  (an mp3 source is never size-compared; only an own-format file is). **D4 half done**: `ServerRefusedException` keeps a 404 from
  counting as congestion; playback fetches still never back off, which is #85. `DOWNLOADING` kept as a documented display state.
  Phone: installed the debug build (0.7.2/12 over 0.7.1), downloaded and removed "(515)": row, file and counts as expected,
  baseline restored (224 files, 224 COMPLETE, queue untouched). Not tried on the phone: a capped-quality download, the failure path.
  Note: `lighttool.toml` now says `serverPackage = "com.lightos"`; the app ran fine as the debug build with it.
- 2026-10-08: **#83 mostly done** `bc19531`. Classification (`ServerRefusal`, `WriteOutcome.REFUSED`), placeholder awareness, orphan-row healing
  and `FavoriteKind` are in, with 8 new tests (317 total). **D7 done.** Phone: queue was empty; star/unstar of "(515)" round-trips
  (starred 1 then 0, queue empty both times, starred total back to 22). **Deferred: Phone Only as a first-class home in the write
  path.** It spans `PlaylistRepository`, `RoomLocalCopy`, `PlaylistHomes` and `ServerCleanupDao` and depends on #82 (server standing),
  so it is done after #82; the #83 box stays `[~]` until then. No attempt cap: an outage must keep retrying.
- 2026-10-08: **#86 done** `be57eb0`. `Advance` (one next/previous rule, wrap to the first playable) used by track end, skip and error;
  `PlayerErrorHandler` replaces PlaybackRepository's two watchers and skipUnavailable; 11 new tests (328 total). Phone: playback
  still starts and pauses; the error path was not provoked there (no downloaded song in the queue to fault-inject). **Not done:**
  removing PlaybackRepository's one-line delegations (touches every screen; left for after #92).
- 2026-10-08: **#87 done, D2 fixed** `3871c09`. `Favorites.set` writes local-first and patches the playing queue's copy of a song;
  `SyncQueueRepository` delegates to it and the player screen no longer patches by itself; 3 new tests (331 total). Phone: favoriting
  the playing song from a Search-result menu filled Now Playing's star at once; un-starred after (starred 22, queue empty). **Open on
  purpose:** whether Track should keep carrying live facts (favorite, download status, path, server label) and data/ importing the
  UI-package Track.
- 2026-10-08: **#82 done** `dbb01e9`. `ServerStanding` (+ `ServerStandingNow`) states usable / can / keepsFavorites / canHoldPlaylists / canScrobble /
  nameOf once; the scattered copies ask it; `Capabilities` removed; Jellyfin's scrobble capability follows its scrobbler; the registry
  checks what it last saved. 7 new tests (338 total). Phone: Favorites ("Phone Only" label), Playlists (server names) and the
  new-playlist entry behave; nothing created. **Left for #89:** the screens' own reads of the warmed globals, `announce`'s hand-set
  mirrors. **Unrelated finding:** the Jellyfin test server entry has returned 401 on every refresh since 2026-10-06 (expired login).
  **Phone Only in the write path (the deferred half of #83) is now unblocked.**
- 2026-10-08: **#84 done** `2da7cb5`. `mirrorCollection` + `MirrorSpec`/`Starred`: the live guard, star keeping and merge are written once;
  artists/albums/songs/playlists are a spec each; `DownloadRepository.byId()` and `PendingMutationDao.localStars()` replace the repeated
  join and star rule. 7 new tests (345 total). Phone: a full refresh on the real server unchanged (47/1269/2277/11876, nothing
  written or removed). Detail refreshes and refreshFavorites left as they are.
- 2026-10-08: **#85 done, D4 fully done** `ead0a02`. `FetchGate` is an instance (`isWifiNow`, `nowMs`, scope); it classifies congestion for both lanes and
  answers "on Wi-Fi?" for fetch-ahead, stream quality, retry and integrity; `attach()` is gone; `FetchGateDownloadGate(gate)`,
  `StreamCache(dir, gate)` and `PlaybackRepository(gate=...)` take it. 9 new tests (353 total). Phone: starts at 6 on Wi-Fi, a real
  download ran through it and was removed (224 files). **Left:** rank has three suppliers (one authority, two fallbacks), rank lambdas
  are still called under the gate's lock, the 3 s probe/hold adaptation is not under test.
- 2026-10-08: **#91 done** `c7ea8b4`. `ServerHttp` (send / reachability / 502-504 / close) replaces six copies in the two clients; the engine is injectable;
  clients and apis are closeable and closed on remove/edit and after one-off login checks. 6 new contract tests running the same
  scenarios on both clients against a local server (359 total). Phone: full refresh unchanged, art renders. **Left:** the two
  exception types are still two (they share `ServerRefusal`), and `isUnreachable()` still has a "Timeout" string fallback.
- 2026-10-08: **#90 done** `cc150dc`. `KeptFiles` (deleteForServer / clear) implemented by the stream cache, cover art, a new `LyricsStore` and the downloads;
  `PhoneLeftovers` and `LocalDataRepository` iterate them; directory names are constants; scoped-id SQL is named constants. 3 new tests
  (362 total). Also fixed two racy assertions in `FetchGateTest` (they had failed 4 of 6 runs; 8 clean after). Phone: lyrics work through
  the store. **Incident:** while checking, a blind tap on the mini-player landed on a Songs row and replaced the restored queue with
  "(B)"; I rebuilt it by playing "Human: The Double Album" from its last track, and the saved queue is identical (18 songs, same
  order, "By Your Grace" current), but its position is 0:11 instead of 0:08.
- 2026-10-08: **#93 done** `ec5512a`. `AppLogger` is a no-op unless `BuildConfig.DEBUG`; verified in the release APK's dex (0 of the log strings vs
  present in debug); the `debugLoggingEnabled` setting, its mirror, `AppDebugPrefs` and `setEnabled` are deleted (so #89 no longer has
  it to decide); the migration's `android.util.Log` calls use `AppLogger`. 2 new tests (364 total). Phone: the debug build still logs; a
  release build was not installed (it would replace the debuggable build). **Still open for the Light submission:** disclose the
  once-per-launch GitHub release check in the README, and the missing LICENSE.
- 2026-10-08: **#89 partly done** `2706b4c`. `Setting<T>`/`Settings` declare the six displayed settings once (key, default, warm copy); the `*Prefs`
  objects and per-setting mirror lines are gone; `AppLifecycle` owns the update-check throttle, the Wi-Fi-only download retry, the
  one-time media repair and the reconnect trigger, with 17 new tests (381 total). Phone: values and the launch fan-out verified.
  **Not done, needs a decision:** the UI read model that replaces screens' direct reads of global WarmedFlows and the 22
  `AppGraph.from` calls (and `HomeScreen`'s discarded warm-up call); and `announce`'s hand-set mirrors. Box stays `[~]`.
- 2026-10-08: **#88 partly done, D9 fixed** `c31bc2d`. `MenuState` (rows, close latch, one-action latch, live updates matched by key) is extracted from
  `ActionsMenuScreen.Content()` with 9 tests (390 total); a throwing action no longer leaves the menu dead; `confirm()` replaces five
  hand-written confirmation dialogs. Phone: favorite and download rows flip in place, removal restores the baseline. **Not done:** artist /
  server menus still hand-listed (no `ArtistActions`), download behaviour still in four files, and the Clear-all-data message
  builder (S1).
- 2026-10-08: **#92 done** `c3c3fde`. `QueueEdits` (pure functions: append, clear, removeAt, removeServer, move, shuffle, unshuffle) split out of `PlayQueue`, which
  keeps hand-over and transport; `PlayQueueTest` unchanged and green; 9 new tests (399 total). Phone: shuffle on then off with the
  restored 18-song queue returned the saved order exactly.
- 2026-10-08: **#83 completed** `04e123a`. The Phone Only half: `LocalCopy.createPhonePlaylist` (adopt + setSongIds) is the one way a phone playlist is
  made; the DAO-level duplicate in `PlaylistRepository` is a delegate. 2 new tests (401 total). Phone: created and deleted a throwaway
  Phone Only playlist, no leftovers. `copyToPhone`, `uniquePhoneName`, `PlaylistHomes` and the SQL recount stay where they are.
- 2026-10-08: **#88 completed** `2e885c7`. `ArtistActions` and `ServerActions` replace the hand-listed artist and server menus; `TrackListDownload.kt`
  merged into `DownloadStates.kt` with the song row moved there and the list row's icon/label made pure. 10 new tests (412 total). Phone:
  artist menu, favorite round trip, download dialog (cancelled), favorites menu and the server menu looked at; no server row tapped. The
  player's download control is already `DownloadRepository.tap` and the artist screen's star is a top-bar button on the shared favorite
  path, so they stay.
