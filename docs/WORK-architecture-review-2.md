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

## Part 1: the 12 fixes

Order matters because some issues build on others. Tick a box only when the commit exists, the tests pass, and (where it
changes behaviour) the phone check is done. Record the commit hash.

| Done | Order | Issue | Fix | Depends on / pairs with | Defects fixed by it |
|---|---|---|---|---|---|
| [ ] | 1 | #81 | Downloads module | none | D3, D4 |
| [ ] | 2 | #83 | Write queue: offline vs refused | introduces the failure classification #91 reuses | D7 |
| [ ] | 3 | #86 | Advance module (what plays next) | none; relates to #78 | D1 |
| [ ] | 4 | #87 | One "set favorite" behaviour | none | D2 |
| [ ] | 5 | #82 | Server standing module | none | |
| [ ] | 6 | #84 | Per-collection mirror | #82 helps (the star rule) | |
| [ ] | 7 | #85 | FetchGate as an instance | #81 first (its first caller) | D4 |
| [ ] | 8 | #91 | One request layer, injectable engine | #83 for the refused/unreachable type | D5 |
| [ ] | 9 | #90 | Stores for downloads and lyrics | #81 first (the downloads half follows it) | |
| [ ] | 10 | #89 | Settings declared once, read model | #82 helps | |
| [ ] | 11 | #88 | Action-menu state holder | none | D9 |
| [ ] | 12 | #92 | Split queue-edit rules from PlayQueue | #86 first | |

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
| [ ] | D1 | `PlaybackRepository.kt:145,159-163` | Error retry replays the queue snapshot taken before the 750 ms delay, overwriting a jump or edit made meanwhile. The retry also blocks `collect`, so a second error is missed until it ends. | verified | #86, **or standalone first** (re-read the snapshot after the delay) | Test with `FakePlayback`: error, jump during the delay, assert the jump survives |
| [ ] | D2 | `PlayerScreen.kt:189`, `TrackActions.kt:42` | Favoriting the playing track from a list menu leaves Now Playing's star stale (`updateTrackFavorite` has one caller). | verified | #87 | Test: favorite the playing track via the menu path, assert the queue snapshot |
| [ ] | D3 | `MediaIntegrity.kt:67` | A capped-quality download is saved as `.mp3`; when the track's suffix is also `mp3` the transcoded file is compared with the original size, mismatches, and is deleted and re-queued. Runs once per install (`VERSION = 1`). | verified | #81 | Test: capped download of an mp3 track passes the check. Also decide: does this need a `VERSION` bump to repair files already wrongly discarded? (They were re-queued, so probably not; check on the phone.) |
| [ ] | D4 | `DownloadRepository.kt:232` | Every `IOException` counts as congestion and halves the FetchGate limit, including `server answered 404` and `cut short` from `streamToFile`. | verified | #81 then #85 | Test: a 404 does not call `onCongestion` |
| [ ] | D5 | `SubsonicClient.kt:205-209`, `CoverArtStore.kt:135-137` | `getBytes` never checks the status or the Subsonic error envelope (Jellyfin's does), and the store caches whatever came back as art, forever. | verified | #91, **or standalone first** (status check in `getBytes`, validate before writing) | Test with a stub that answers 200 + an error body; **also scan the phone's existing cover-art cache for non-image files** and clear any found |
| [ ] | D6 | `VersionCheckRepository.kt:41-47`, `AppGraph.kt:208-214` | A failed check (offline at launch) returns Unit and `setLastVersionCheckAtMs(now)` runs anyway, suppressing the check for 24 h. `runCatching` also swallows `CancellationException`. | from reading | standalone | Test: a failing fetch leaves `lastCheckedAt` untouched; make `checkForUpdate` return success/failure |
| [ ] | D7 | `LocalFirstWrites.kt:139-147,255-290` | A permanently refused write (e.g. delete of an offline-created playlist: `deletePlaylist(<server>:pending:uuid)`) is queued and fails every drain, blocking all later edits for that server. | from reading | #83 | Test: the poison-head scenario with a fake server that throws a refusal; **then check the phone's real queue** (`PendingMutation` table, via adb run-as) for rows already stuck |
| [ ] | D8 | `LazyCollapsibleSection.kt:34-44`, `ArtistDetailScreen.kt:74-77` | `loaded = true` and `_loading = true` are set before `fetch()` with no try/finally, so a throwing fetch leaves "Loading…" forever and the exception escapes `viewModelScope.launch`. Top-songs reads `artist.value?.name` at expand time; if the artist has not emitted it returns empty, and because `loaded` is already true it never retries. | from reading | standalone | Test the section with a throwing fetch; phone: expand Top Songs immediately on opening an artist |
| [ ] | D9 | `ActionsMenuScreen.kt:248-257` | `busy = true` with no try/finally; a throwing `Perform` leaves every row dead until back, and the throw has no handler. A `liveUpdates` emission that replaces the row during the action drops the result (`it === item`). | from reading | #88 | Reproduce with a throwing action on the phone or in the holder's test once it exists |
| [ ] | D10 | `ServerEditScreen.kt:262-270`, `data/ServerLifecycle.kt:93-105` | After the menu's delete nothing navigates back; the screen stays with Save active, and `save(existingId, ...)` would call `addOrUpdate` and re-add the deleted server. | from reading, unverified at runtime | standalone | Phone: delete a server from its edit screen, then press Save. If it re-adds, navigate back after remove and guard `save` |
| [ ] | D11 | `TrackListDownload.kt:114` | `downloadEntireArtist` uses `observeAlbumsByArtist(artistId).first()` without `refreshArtistDetail` first; the caller (`ArtistListScreen`) does not refresh, unlike `ArtistDetailScreen.onScreenShow`. May silently do nothing. | unconfirmed | standalone | Phone: long-press an artist in the list that was never opened, choose download, see whether anything queues |
| [ ] | D12 | `MaskedTextInputEditor.kt:86`, `MaskedTextEditScreen.kt`, `TextEditScreen.kt`, `PlayerScreen.kt:169` | Stale comments: the masked editor is described as "only used by SettingsScreen" but `ServerEditScreen.kt:233` uses it; `PlayerScreen.kt:169` says "see [tap]" which is actually `DownloadStates.kt`'s `DownloadRepository.tap`. | verified | standalone | Fix the comments |

Standalone fixes, in the order to do them before #81: D1, D5, D6, D8, D12, then the phone checks for D10 and D11.
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

- 2026-10-08: issues #81-#92 filed; this doc written. No fix started. The Light-tool submission restructure
  (`tool/` layout, SDK 0.2.0 compile fixes) is already on `main` (`da11073`, `13adb46`), unpushed.
