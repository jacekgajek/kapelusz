# Backlog

What is known to be wrong, risky or missing, and was deliberately left alone during the refactoring (REFACTORING.md,
decision 12). Nothing here is fixed; an item leaves this file by a pull request that fixes it or a decision that it
stays. Grouped by module; ⚠ marks what a user can see. File pointers are to the code as it is after the refactoring
and may drift; the symbol names are the stable part.

## Hygiene

- `PRO.md` was tracked from f003fcc and reached `main` through #124; untracked and ignored in step 0.0 (its content
  remains in the repository history).
- `scripts/local-dev.sh:21-25` has the `ANDROID_SERIAL` guard commented out, so `installDebug` installs on every
  connected device.

## UI (`composeApp/.../app/ui`)

1. ⚠ `CostScreen.kt:153`: shared photos for a new cost are consumed inside `remember {}` and are not saveable; an
   Android rotation of the form loses them.
2. ⚠ `EventDialogs.kt:102-111`: one Save can emit `UpdateEvent` + `SetEventAgenda`, so History shows two lines and
   Undo needs two taps.
3. ⚠ `SettingsWiring.kt:61-63` + `FundSettings.kt:101-103`: rejections are discarded; a refused `SetTreasurer` silently
   flips the switch back.
4. ⚠ `RosterScreen.kt:277` + `Common.kt:433`: `TextInputDialog` always dismisses after confirm, so a refused `AddMember`
   loses the typed name.
5. `EventScreen.kt:184`: `tabs.associateWith { rememberLazyListState() }` calls a composable in a loop; when the tab
   list shrinks (role becomes Artist while open) the remaining tabs take the wrong scroll states.
6. `HistorySection.kt:42` passes `me?.userId.orEmpty()` where `HistoryScreen.kt:109` passes `state.userId`; for an
   unlinked user the pending undo line is attributed to user `""`.
7. `SettleScreen.kt:86-111`, `EventScreen.kt:176,250,270`, `HistoryScreen.kt:162`, `Common.kt:200`,
   `Navigation.kt:261`: settlement, `Can` and 43 rejection strings recomputed per recomposition (performance).
8. `EventsScreen.kt:161`: event summaries (settles every visible event) run on the main thread on every band change.
9. `RosterScreen.kt:462,605`: `MemberDialog.message` is never auto-cleared; stale messages stack.
10. `RosterScreen.kt:562-570`: client pre-checks duplicate server rules with their own copies of the rejection texts.
11. `AttachmentsSection.kt:162,218`: `viewable` computed twice; editing one opens the viewer on the wrong index.
12. `WebInstallHint.kt:43`: process-global mutable state survives sign-out/sign-in.
13. `JoinDialog.kt:84`: a second join link arriving while the dialog is open changes `initialCode` but not `code`.
14. `CostScreen.kt:165`: after the payer list shrinks to one *other* member, `paidBy` can point at someone not offered
    and the dropdown shows a blank label.
15. `BandFundScreen.kt:448-450`: after a partial failure among several payers, editing the amount before retrying can
    double-post the entries that succeeded.
16. `ParticipantsSection.kt:182-188`: `ChangeLineup` is dispatched from a `LaunchedEffect` one frame after the dialog
    closes, only to resolve its message; a fast Back could drop it.
17. `App.kt:175-189`: `TopBarState` is a new object on every sync-status tick, recomposing every tab's top bar.

## App core (`composeApp/.../app/{data,sync,auth}`)

1. ⚠ `BandRepository.kt:206`: `_state.value as BandState.Ready` unchecked; `readLocal` sets `currentBandId` and
   `Unreadable` together, so a dispatch from a still-visible screen in that window throws.
2. ⚠ `Account.kt:70-75`: sign-out clears only `pendingOpenEvent`; `sharedFiles`, `sharedHandover`, `sharedAttached`,
   `pendingEventSection` survive and can be delivered to the next account's band.
3. `Account.kt:96`: `pictureSent` never reset on sign-out; a second account in the same process never sends its photo.
4. `Session.kt:96-118` + `ApiClient.kt:189`: `refreshLock` is held across Google UI and the network exchange; every API
   request waits on it.
5. `EventLinkOpener.kt:26` + `SyncEngine.kt:264,285`: the foreground event-link flow uses `syncAll`, whose background
   presentation hides `NEEDS_SIGN_IN`; the user sees "offline" instead of "sign in again".
6. `SyncEngine.kt:154-173`: `bandChanged` launches a round that `stop()` does not cancel; it can run with the Android
   app in the background.
7. `SyncMessaging.android.kt:60-62`: `onNewToken` in a cold process registers nothing until the next `onStart`.
8. `PlacesLocator.kt:64`: the only persisted key without a user id; a second account inherits the first one's cache.
9. `BandRepository.kt:434,439`: every change launches a new `flush()` coroutine; a burst queues N flushes.
10. `MainActivity.kt:21`: `hasUnsentWork()` reads `bands.locals`, not cleared on sign-out; a background sync can be
    scheduled for a signed-out user (exits early).
11. `AttachmentUploads.kt:100`: the whole base64 file is held in memory for the upload; no size guard on this path.
12. `AppGraph.kt:38,50,56`: forward references inside lambdas; safe only while nothing invokes them during construction.
13. `BandStore.flush` (3.4) captures the user once but reads the repository's objects live: a `load()` of another user
   while a write is suspended could write the new user's data under the old keys (pre-existing shape). Same family:
   `loseAccess`/`startOver` remove a file outside `writeLock`, so an in-flight write can re-create it. Fix: check the
   user per write and run `remove` under the lock, once the sync state machine (3.5) settles.

## Shared

1. ⚠ `SettleTogether.kt:142-143`: with two artists paired, the leftover goes to an artist and `validateArtistsPaid`
   refuses the whole transfer; a pair of two artists can never settle a surplus (`canPair` allows two artists).
2. ⚠ `SettleTogether.kt:52-54`: `tidy` drops an invitation when the invitee has any other pending one, so two
   concurrent invitations to the same person cancel both silently.
3. `SettleTogether.kt:73-76`: `keepSettled` does not stamp `updatedAt`, unlike every other event mutation.
4. `Reducer.kt:286-289` + `Undo.kt:115`: a no-op operation (e.g. same weight again) stamps `updatedAt` and thereby
   blocks a later undo of an earlier change on that event.
5. `Reducer.kt:323`: "not yourself; leave instead" reported as `MEMBER_HAS_HISTORY`; `Reducer.kt:122,294`: missing
   pair reported with code `CONFLICT` and reason `NOT_FOUND`.
6. `Reducer.kt:606-661`: `settleSuggestions` settles the same event three times per suggestion; `recordTogether`
   twice; `SettlementCalculator.kt:90,187`: `parties` computed twice (performance).
7. `CostLedger.kt:30-35`: `paid/unpaid/status` each settle the whole band (O(costs × events × settle)).
8. `SettlementCalculator.kt:139,155`: with percentages at exactly 100 % including the fund's, rounding leftovers go to
   participants only; the fund never gets its leftover grosze (probably intended; worth stating).
9. `Limits.kt:52-59`: relies on `!==` list identity; a future early-return no-op would skip limit checks.
10. `Limits.kt:38` vs `Reducer.kt:264`: the attachment-id regex twice with different rejection reasons.
11. `Permissions.kt:137`: a viewer who was a participant before becoming a viewer is refused `AddHolding` by role,
    consistent but a hole in "viewers take no part" (`Viewers.mayBecome` checks only unsettled events).
12. `Engine.kt:75`: more than 20 rejected ops in one lost response come back as plain duplicates with no reason.
13. `Names.kt:13-14` vs app `EventSearch.kt:56-75`: two divergent accent-folding tables (sort and search disagree on
    `ß`, `ā`, `ø`, `ő`).

## Server

1. ⚠ `InviteHandlers.kt:48-53`: the invite is written before the band's code list; if `updateCodes` exhausts its
   retries the invite stays valid for 72 h but is invisible to `list`, `revokeFor` and `deleteNow`.
2. `BandAccess.kt:18-23` + `LinkPages.kt:57`: any read, including anonymous `/e/{id}` and calendar fetches, can execute
   a full band deletion in-request; the `deleteIfDue=false` convention is the only thing preventing a self-deadlock
   from the `writers` pool.
3. `BandHandlers.kt:123-124`: a failed history append is logged and dropped with no later repair; a DynamoDB outage
   leaves permanent gaps undo cannot see.
4. `BandHandlers.kt:78,83`: the band is loaded twice per push (two S3 reads even without undos); `:43,46`:
   `Band.create` built twice.
5. `UserHandlers.kt:66-73`: `rememberPicture` repeats every band update on each conflict retry.
6. `HistoryHandlers.kt:48-56`: history pages are fetched before the membership check; `reader` is a JVM-wide pool of 3.
7. `Handler.kt:146,151`: hard-coded production bucket/table fallbacks; a misconfigured environment silently talks to
   production.
8. `Api.kt:133-137`: `appVersion=0` is always accepted, so the minimum-version gate is advisory (fine for forcing
   updates, not for retiring a format).
9. `Sessions.kt:104`: the revocation cache grows without bound (unbounded in `localServer`).
10. `places/PlacesHandlers.kt` (`locations`): `LocalDate.now()` instead of the injected clock; untestable with fixed clocks.
11. `LinkPages.kt:92-96`: `inParallel` blocks on each future with no timeout.
12. `ApiModels.kt:50`: `ApiErrors.BAND_TOO_LARGE` is in the contract but never sent.
13. `LocalServerTest.kt:134` sends `?v=0` while the parameter is `appVersion`; passes only because local has no minimum.
14. `devices/DeviceRegistry.kt` (`prune`): lazily pruned band ids can keep accepting registrations for a user who left their last band.
15. `BandChanged.kt:46-47`: `pointers.pointTo`/`forget` are the only consequences not in `runCatching`; a DynamoDB
    failure there answers the push with 500 although the band is saved, so the device pushes the batch again (all
    duplicates).
16. `BandChanged.kt:59-65`: the sync message goes to an unlinked user's devices before `removeBandFromUser`; a device
    that reacts at once can still see the band in `GET /me` (it learns `band_not_found` from the band itself anyway).

## Platform, build, tests

1. `androidApp/build.gradle.kts:20-21` vs `composeApp/build.gradle.kts:18-20`: a Maps key in `local.properties` yields
   `HAS_MAPS_KEY = true` with an empty manifest placeholder (blank map); default version code 1 vs 0.
2. `AppLocale.android.kt:19-27`: `Locale.setDefault` and `updateConfiguration` run in the composable body on every
   recomposition; a later system-language change is ignored until restart.
3. `Media.android.kt:306` reads `AndroidPlatform.context` (null before the graph exists) while `PdfPages` uses
   `LocalContext` in the same file.
4. `Share.android.kt:16-18`: `rememberShare` always reports `SHARED`; the chooser `startActivity` is unguarded.
5. `StoreUpdates.android.kt:32`: `LocalContext.current as? Activity` is null inside a `Dialog`.
6. `SyncEngineTest.kt:705-707,739-758`: wall-clock polling and sleeps with a documented CI flake;
   `FcmSenderTest.kt:33`: a 5 s sleep in every run.
7. `composeApp/build.gradle.kts:85-90`: wasm browser tests silently skip without Chrome; CI has no explicit setup.
8. `KeyValueStore.kt:10`: `InMemoryStore` (a test double) ships in the app.
9. `platform/Updates.kt:26`: `PLAY_PUBLIC = false` must be flipped by hand at the production release; README is silent.
10. `InstallHints.wasmJs.kt:28-31` and `ApkLeftovers.kt`: migration code that runs forever; both are backward-compat
    code the no-backward-compat rule says not to carry.
11. `AppGraph.kt:35`, `BandRepository.kt:493`: `println` logging; in a release build on Android nobody sees unhandled
    coroutine failures or storage write failures.
