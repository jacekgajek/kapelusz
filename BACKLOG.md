# Backlog

What is known to be wrong, risky or missing. Nothing here is fixed; an item leaves this file by a pull request that
fixes it or a decision that it stays. Grouped by module, ordered by priority within a group; ⚠ marks what a user can
see. File pointers are to the code as it is today and will drift; the symbol names are the stable part.

Priorities: **P1** data lost or money wrong · **P2** a failure a user meets · **P3** a silent misbehaviour or a risk
to reliability · **P4** performance · **P5** cosmetic, hygiene, tests. After the dot, the complexity of fixing it,
counting how hard the surrounding code is to read: **L** one place and obvious · **M** a few files, a rule to decide, or
code that takes reading · **H** concurrency, a cross-cutting invariant, a new operation, or dense code. 🌊 marks an
obscure sync edge case: it needs a sync or a second device to land at the wrong moment (DECISIONS.md 13).

Noticed during the refactoring of 2026-10-31 and left alone on purpose (REFACTORING.md, decision 12). Re-checked on
2026-11-05 after the post-refactoring series (PRs #190–#194): all 59 hold; one moved with the event editor's form, four
descriptions are corrected below. Decision 13 (concurrent edits are not engineered for) takes the sync-race items out
of P1–P2 unless money is wrong.

## UI (`composeApp/.../app/ui`)

1. **P2·M** ⚠ `event/ParticipantsSection.kt` (`LaunchedEffect(op)`): `ChangeLineup` is dispatched one composition after
   the dialog closes, only to resolve its message; a fast Back or tab change cancels the effect and drops the change
   without a word.
2. **P2·M** ⚠ `components/Dialogs.kt` (`TextInputDialog` calls `onConfirm` then `onDismiss` unconditionally) +
   `RosterScreen.kt` (the add-member dialog) + `AttachmentsSection.kt` (the rename dialog): a refused `AddMember` or
   rename loses the typed text. A snackbar says why, and a taken name is blocked before the dialog closes; other
   refusals (permissions, a change made meanwhile) still lose it.
3. **P2·L** ⚠ 🌊 `CostViewModel.kt` (`takeOnlyPayer`, `canSave`; `pruneEvent` prunes only the event): nothing prunes
   `payerId` when the payer list shrinks; a payer no longer offered stays selected and `AddCost` is sent for them
   (shown refused). Blank only if the member is gone.
4. **P3·L** `event/HistorySection.kt` passes `context.me?.userId.orEmpty()` where the band history passes
   `session.userId` (`Navigation.kt`); for an unlinked user the event history's pending undo line is attributed to
   user `""` (`screens/HistoryUndo.kt`, `pendingUndoLine`).
5. **P3·L** `JoinDialog.kt` (`code` remembered without `key(initialCode)`; opened from `intents/IntentRouter.kt`): a
   second join link arriving while the dialog is open changes `initialCode` but not `code`; the new link is ignored.
6. **P4·M** `EventsScreen.kt` (`summaries` inside `remember`): every visible event is settled on the main thread on
   every band change; only the search index moved to a background dispatcher (`EventsViewModel.prepare`).
7. **P4·M** `App.kt` (`LocalTopBarState provides TopBarState(...)` inline) + `components/TopBars.kt`: `TopBarState` has
   no `equals` and the local is static, so every sync-status tick makes a new instance and invalidates the whole
   `Navigation` content, not only the top bars.
8. **P4·L** `components/Messages.kt` (`messageTexts`: the `remember` key is a map rebuilt each composition, so it never
   hits): every `RejectionReason` string is resolved on each composition of `MessageHost`, `DialogNotice` and
   `UndoSnackbars`.
9. **P4·H** ⚠ `event/EventDetailsForm.kt` (`operations()`: `UpdateEvent` then `SetEventAgenda`): one Save can still
   send two operations, so History shows two lines and Undo needs two taps. (Moved from `EventDialogs.kt` with the
   form; a refused update no longer lets the agenda through.)
10. **P5·L** `RosterScreen.kt` (`InviteSection.tell`): hand-maps `HOLDS_MONEY` and `VIEWER_STILL_PLAYS` to texts and
    says "invite failed" for every other reason, instead of the central `labelRes()` (`components/Labels.kt`). The
    role rules themselves are shared (`RoleChanges.refusal`).
11. **P5·L** `event/EventScreen.kt` (`tabs.associateWith { rememberLazyListState() }`): a composable called in a loop.
    Harmless today, as an artist's tab list is a prefix of the full one, but fragile if tabs are ever reordered or
    inserted.
12. **P5·L** 🌊 `AttachmentsSection.kt`: `viewable` computed twice (the tap index from one snapshot, the viewer list from
    the next); a wrong index needs a sync landing between tap and recomposition, so low impact.
13. **P5·L** `components/WebInstallHint.kt` (`dismissedThisVisit`): process-global state that survives sign-out;
    cleared when the event list opens, so visible only between a sign-out and the next sign-in.

## App core (`composeApp/.../app/{bands,auth,sync,runtime,attachments,devices,settings,storage,intents}`)

1. **P2·M** ⚠ `bands/BandRepository.kt` (`dispatch`: `locals.getValue` then `as BandState.Ready`): `readLocal` sets
   `currentBandId` and `Unreadable` together, so a dispatch from a still-visible screen in that window throws;
   `Changes.dispatch` does not guard.
2. **P3·H** `bands/BandStore.kt` (`flush`, `writeChanged`; a race on one device between two accounts sharing a
   band): the user is read once per flush and the band objects live; a flush suspended in `write()` can carry on
   after `load(newUser)` and, for a band id both users have, write the new user's band under the old user's key. At
   stake: the old user's unsent changes to that band; the server's copy is untouched. Same family: `remove`,
   `BandRepository.loseAccess` and `startOver` run outside `writeLock`, so a write in flight can re-create a removed
   file.
3. **P3·M** `attachments/AttachmentUploads.kt` (`store.read(fileKey)` then `put`): the whole base64 file is held in
   memory for the upload. No byte-size cap was found anywhere (only `MAX_ATTACHMENTS` per cost), so a large file is
   bounded by nothing but the picker.
4. **P3·L** `intents/IntentInbox.kt` (`signedOut`): sign-out clears the carries and the `OpenEvent` intents with a
   band, but a pending `ShareFiles` stays and is offered to whoever signs in next, once a band is open
   (`IntentRouter`).
5. **P3·M** `bands/EventLinkOpener.kt` (`sync.syncAll()`) + `sync/SyncEngine.kt` (`backgroundShown`): the foreground
   event-link flow hides `NEEDS_SIGN_IN`, returns false, and `IntentRouter` shows "offline" instead of "sign in
   again"; the loop is not blocked either, as `syncAll` sends `RoundDone` only for `UPDATE_REQUIRED`.
6. **P3·H** 🌊 `sync/SyncEngine.kt` (`bandChanged`: a bare `scope.launch` with no `Job`): `stop()` cancels only
   `roundJob`, so a message arriving just before `onBackground` runs a round with the Android app off screen.
7. **P3·H** `androidMain/.../sync/SyncMessaging.android.kt` (`onNewToken` → `AndroidAppRuntime.get`) +
   `devices/DeviceRegistration.kt` (gated on a user and bands): in a process the service just created, the session
   and index are empty, so nothing registers until the next app load.
8. **P4·M** `auth/Session.kt` (`validToken` holds `refreshLock` across `google.refresh()` and the network exchange) +
   `sync/ApiClient.kt` (`token(...)` on every request): every API request waits on it.
9. **P4·L** `bands/BandStore.kt` (`markDirty`, `markIndexDirty`): every change launches a `flush()` coroutine; a burst
   queues N, serialised by `writeLock` and cheap no-ops after the first.
10. **P5·L** `auth/Account.kt` (`pictureSent`): never reset on sign-out; a second account in the same process never
    sends its photo.
11. **P5·L** `bands/PlacesLocator.kt` (`KEY = "kapelusz-places"`): a persisted key without a user id that holds band
    data (`settings` and `session` have none either, by design); a second account inherits the first one's cache
    (coordinates by place text, so low impact).
12. **P5·M** `androidApp/.../MainActivity.kt` + `runtime/AppRuntime.kt` (`hasUnsentWork`): sign-out never clears the
    loaded bands or the uploads, so a background sync can be scheduled for a signed-out user (it exits early).

## Shared

1. **P2·H** ⚠ `settlement/SettleTogether.kt` (`split`: the leftover goes to the first non-artist, index 0 when both are
   artists): `validateArtistsPaid` (`engine/TransferReducer.kt`) then refuses the whole transfer; a pair of two
   artists can never settle a surplus (`canPair` allows two artists).
2. **P3·M** ⚠ 🌊 `settlement/SettleTogether.kt` (`tidy`, `keepsInvite`: an invitation stays only if the invitee has exactly
   one): two concurrent invitations to the same person cancel both silently.
3. **P3·M** `engine/Reducer.kt` (the `updatedAt` stamp for every op with an `eventId`, no change check) +
   `engine/Undo.kt` (compares the whole event): a no-op operation (e.g. the same weight again, `LineupReducer`) blocks
   a later undo of an earlier change on that event with `CHANGED_SINCE`.
4. **P3·L** `settlement/SettleTogether.kt` (`keepSettled`): does not stamp `updatedAt`; the generic stamp in
   `engine/Reducer.kt` covers only the op's own event, and `Undo` never stamps.
5. **P3·L** 🌊 `engine/Engine.kt` (`REMEMBERED_REJECTIONS = 20`, per client): more than 20 rejected ops in one lost
   response come back as plain duplicates with no reason.
6. **P4·M** `engine/TransferReducer.kt` (`settleSuggestions`): the event is settled once up front and once per
   suggestion; `settlement/SettlementCalculator.kt`: `parties` computed twice (`mergePairs` inside `settle` and the
   lazy `Settlement.parties`).
7. **P5·L** `engine/RosterReducer.kt`: "not yourself; leave the band instead" reported as `MEMBER_HAS_HISTORY`;
   `engine/PairReducer.kt`: a missing pair or invitation reported with code `CONFLICT` and reason `NOT_FOUND`, while a
   true not-found has code `NOT_FOUND`.
8. **P5·L** `settlement/SettlementCalculator.kt` (`restShares`, `byPercent`): with percentages at exactly 100 %
   including the fund's, rounding leftovers go to participants only; the fund never gets its leftover grosze
   (probably intended; worth a comment).
9. **P5·M** `engine/Limits.kt`: relies on `!==` list identity; a reducer branch that returns the same list skips the
   limit checks, one that rebuilds an equal list runs them. Documented only by a comment.
10. **P5·L** `engine/Limits.kt` (`ID`) and `engine/AttachmentReducer.kt`: the same id pattern written twice; they check
    different ids and both reject with `INVALID_INPUT`, so mostly cosmetic.
11. **P5·M** `rules/Viewers.kt` (`mayBecome` looks at unsettled events only) + `rules/Permissions.kt` (own `AddHolding`
    needs `MEMBER`): a viewer who took part in a settled event is refused by role, consistent but a hole in "viewers
    take no part"; `validateNotViewer` runs only when participants are added or an event is created.

## Server

1. **P2·M** ⚠ `invites/Invites.kt` (the invite blob is written, then `updateCodes`): if `updateCodes` exhausts its
   retries the invite stays valid for 72 h but is missing from the band's code list, so `list`, `revokeFor` and
   `delete` never see it.
2. **P2·H** `bands/BandChanged.kt` (`history.append` in `runCatching`): a failed history append is logged and dropped
   with no later repair; a DynamoDB outage leaves permanent gaps undo cannot see.
3. **P2·L** `lambda/Handler.kt`: hard-coded production bucket and table fallbacks (`DATA_BUCKET`, `HISTORY_TABLE`,
   `GEOCODE_TABLE`); a misconfigured environment silently talks to production (the environment is read only there
   and in `LocalServer.main`).
4. **P3·M** `bands/BandAccess.kt` (`load()` defaults to `deleteIfDue = true` and calls `whenDue`): any read, including
   the anonymous `/e/{id}` (`links/LinkPages.kt`) and calendar fetches, can execute a full band deletion in-request;
   the `deleteIfDue = false` convention (`EventPointers`, `BandDeletions`) is the only thing preventing a
   self-deadlock on the `writers` pool.
5. **P3·L** `links/EventPointers.kt` (`inParallel`): blocks on each future with no timeout, on the shared `writers`
   pool.
6. **P3·M** `bands/BandChanged.kt` (`pointers.pointTo`/`forget` not in `runCatching`): their writes are wrapped inside
   `EventPointers`, so a failure no longer fails the push; a failed pointer is only logged and never repaired, so
   that event's link card stays generic.
7. **P4·L** `history/HistoryHandlers.kt` (`history`): the pages are submitted to `reader` before `loadForMember` runs,
   and only cancelled on a failed check; `reader` is a JVM-wide pool of 3 shared by all requests.
8. **P4·L** `bands/BandHandlers.kt` (`push`): the band is loaded twice per push (two S3 reads); `Band.create` is built
   twice in `create`.
9. **P4·L** `auth/Sessions.kt` (`SessionRevocations.cache`): grows without bound, never evicted; the same class serves
   the local server.
10. **P5·L** `Api.kt` (`appSupported`): `appVersion=0` is always accepted, so the minimum-version gate is advisory
    (documented as deliberate; fine for forcing updates, not for retiring a format).
11. **P5·L** `places/PlacesHandlers.kt` (`locations`): `LocalDate.now(UTC)`, no clock injected at all; untestable with
    a fixed clock.
12. **P5·L** `shared/.../api/ApiModels.kt` (`ApiErrors.BAND_TOO_LARGE`): in the contract but never sent; a too-large
    push is refused per operation with `LIMIT_REACHED`.
13. **P5·L** `localServer/.../LocalServerTest.kt` sends `?v=0` while the parameter is `appVersion`; passes only because
    the local server has no minimum, so the version gate is not exercised.
14. **P5·L** `devices/DeviceHandlers.kt` (`register`: `inBand` from `bandIds.isNotEmpty()`, no existence check): a
    stale band id left by a failed `removeBandFromUser` keeps registrations accepted for a user who left their last
    band (the record is otherwise cleaned eagerly).
15. **P5·L** 🌊 `bands/BandChanged.kt` (the sync message before `removeBandFromUser`, intentional): a device that reacts
    at once can still ask `GET /me` before the band is gone from the user's list; `UserHandlers.me` filters out bands
    whose member is archived or missing, so the window shows nothing today.

## Platform, build, tests

1. **P3·L** `composeApp/build.gradle.kts` (`testTask.enabled = chrome != null`): the browser tests, including the
   screen smoke tests, skip silently without Chrome; CI relies on the runner image having one, with no setup step.
2. **P3·M** `runtime/AppRuntime.kt` (the `CoroutineExceptionHandler`) and `bands/BandStore.kt` (`println` on a failed
   write): the only logging in the app is `println`; in a release build on Android nobody sees unhandled coroutine
   failures or storage write failures.
3. **P3·M** `androidMain/.../platform/AndroidLocale.kt` (`provideAndroidLocale`): `Locale.setDefault` and
   `updateConfiguration` run in the composable body on every recomposition; `defaultLocale` is captured once, so a
   later system-language change is ignored until restart.
4. **P3·L** `androidMain/.../platform/AndroidSharing.kt` (`rememberShare`): always reports `SHARED` and the chooser
   `startActivity` is unguarded; `ShareResult.COPIED`/`FAILED` are never returned here.
5. **P3·L** `androidApp/build.gradle.kts` vs `composeApp/build.gradle.kts` (`setting()`): a Maps key set only in
   `local.properties` yields `HAS_MAPS_KEY = true` with an empty manifest placeholder (a blank map); the default
   version code is 1 in one and 0 in the other.
6. **P5·L** `androidMain/.../platform/PlayUpdates.kt` (`LocalContext.current as? Activity`): null inside a `Dialog`'s
   context wrapper; its callers (`AppBanner`, `UpdateRequiredScreen`) sit in none today.
7. **P5·L** `platform/PlayStore.kt` (`PLAY_PUBLIC = false`): flipped by hand at the production release; the README is
   silent about it.
8. **P5·L** `server/.../FcmSenderTest.kt`: the slow-answer test still spends about 2 s of real time waiting out the
   sender's deadline; the app tests run on virtual time.
