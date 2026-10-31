# Backlog

What is known to be wrong, risky or missing. Nothing here is fixed; an item leaves this file by a pull request that
fixes it or a decision that it stays. Grouped by module; ⚠ marks what a user can see. File pointers are to the code as
it is today and will drift; the symbol names are the stable part.

Noticed during the refactoring of 2026-10-31 and left alone on purpose (REFACTORING.md, decision 12); re-checked
against the restructured code at its end: of 72 items, 13 were fixed by the restructuring and are gone, 10 shrank and
say so, the remaining 59 hold.

## UI (`composeApp/.../app/ui`)

1. ⚠ `event/EventDialogs.kt` (`actions.run` twice in Save): one Save can emit `UpdateEvent` + `SetEventAgenda`, so
   History shows two lines and Undo needs two taps.
2. ⚠ `components/Dialogs.kt` (`TextInputDialog` calls `onConfirm` then `onDismiss` unconditionally) +
   `RosterScreen.kt` (the add-member dialog) + `AttachmentsSection.kt` (the rename dialog): a refused `AddMember` or
   rename loses the typed text. A snackbar now says why, and a taken name is blocked before the dialog closes; other
   refusals (permissions, a change made meanwhile) still lose it.
3. `event/EventScreen.kt` (`tabs.associateWith { rememberLazyListState() }`): a composable called in a loop. Harmless
   today, as an artist's tab list is a prefix of the full one, but fragile if tabs are ever reordered or inserted.
4. `event/HistorySection.kt` passes `context.me?.userId.orEmpty()` where the band history passes `session.userId`;
   for an unlinked user the event history's pending undo line is attributed to user `""` (`HistoryUndo.kt`).
5. `components/Messages.kt` (`messageTexts`): every `RejectionReason` string is resolved on each composition of
   `MessageHost`, `DialogNotice` and `UndoSnackbars` (performance; `Capabilities` and settlements are now cached in
   `BandSession`, `EventViewModel` and `BandView`).
6. `EventsScreen.kt` (`eventSummaries` inside `remember`): every visible event is settled on the main thread on every
   band change; only the search index moved to a background dispatcher (`EventsViewModel.prepare`).
7. `RosterScreen.kt` (`InviteSection.tell`): hand-maps `HOLDS_MONEY` and `VIEWER_STILL_PLAYS` to texts and says
   "invite failed" for every other reason, instead of the central `labelRes()` (`components/Labels.kt`). The role
   rules themselves are shared now (`RoleChanges.refusal`).
8. `AttachmentsSection.kt`: `viewable` computed twice (the tap index from one snapshot, the viewer list from the
   next); a wrong index needs a sync landing between tap and recomposition, so low impact.
9. `components/WebInstallHint.kt` (`dismissedThisVisit`): process-global state that survives sign-out; cleared when
   the event list opens, so visible only between a sign-out and the next sign-in.
10. `JoinDialog.kt` (`code` remembered without `key(initialCode)`): a second join link arriving while the dialog is
    open changes `initialCode` but not `code`; the new link is ignored.
11. `CostViewModel.kt` (`takeOnlyPayer`, `canSave`): nothing prunes `payerId` when the payer list shrinks; a payer no
    longer offered stays selected and `AddCost` is sent for them (shown refused). Blank only if the member is gone.
12. `event/ParticipantsSection.kt` (`LaunchedEffect(op)`): `ChangeLineup` is dispatched one composition after the
    dialog closes, only to resolve its message; a fast Back or tab change cancels the effect and drops the change
    without a word.
13. `App.kt` (`LocalTopBarState provides TopBarState(...)` inline) + `components/TopBars.kt`: `TopBarState` has no
    `equals` and the local is static, so every sync-status tick makes a new instance and invalidates the whole
    `Navigation` content, not only the top bars.

## App core (`composeApp/.../app/{bands,auth,sync,runtime,attachments,devices,settings,storage}`)

1. ⚠ `bands/BandRepository.kt` (`dispatch`: `locals.getValue` then `as BandState.Ready`): `readLocal` sets
   `currentBandId` and `Unreadable` together, so a dispatch from a still-visible screen in that window throws;
   `Changes.dispatch` does not guard.
2. `intents/IntentInbox.kt` (`signedOut`): sign-out clears the carries and the `OpenEvent` intents with a band, but a
   pending `ShareFiles` stays and is offered to whoever signs in next, once a band is open (`IntentRouter`).
3. `auth/Account.kt` (`pictureSent`): never reset on sign-out; a second account in the same process never sends its
   photo.
4. `auth/Session.kt` (`validToken` holds `refreshLock` across `google.refresh()` and the network exchange) +
   `sync/ApiClient.kt` (`token(...)` on every request): every API request waits on it.
5. `bands/EventLinkOpener.kt` (`sync.syncAll()`) + `sync/SyncEngine.kt` (`backgroundShown`): the foreground
   event-link flow hides `NEEDS_SIGN_IN`, returns false, and `IntentRouter` shows "offline" instead of "sign in
   again"; the loop is not blocked either, as `syncAll` sends no `RoundDone`.
6. `sync/SyncEngine.kt` (`bandChanged`: a bare `scope.launch` with no `Job`): `stop()` cancels only `roundJob`, so a
   message arriving just before `onBackground` runs a round with the Android app off screen.
7. `androidMain/.../sync/SyncMessaging.android.kt` (`onNewToken`) + `devices/DeviceRegistration.kt`: in a process the
   service just created, the session and index are empty, so nothing registers until the next app load.
8. `bands/PlacesLocator.kt` (`KEY = "kapelusz-places"`): the only persisted key without a user id; a second account
   inherits the first one's cache (coordinates by place text, so low impact).
9. `bands/BandStore.kt` (`markDirty`, `markIndexDirty`): every change launches a `flush()` coroutine; a burst queues N,
   serialised by `writeLock` and cheap no-ops after the first.
10. `androidApp/.../MainActivity.kt` + `runtime/AppRuntime.kt` (`hasUnsentWork`): sign-out never clears the loaded
    bands or the uploads, so a background sync can be scheduled for a signed-out user (it exits early).
11. `attachments/AttachmentUploads.kt` (`store.read(fileKey)` then `put`): the whole base64 file is held in memory for
    the upload; the pickers cap the size, the upload path itself does not.
12. `bands/BandStore.kt` (`flush`, `writeChanged`): the user is read once per flush and the band objects live; a flush
    suspended in `write()` can carry on after `load(newUser)` and, for a band id both users have, write the new user's
    band under the old user's key. Same family: `remove`, `BandRepository.loseAccess` and `startOver` run outside
    `writeLock`, so a write in flight can re-create a removed file.

## Shared

1. ⚠ `settlement/SettleTogether.kt` (`split`: the leftover goes to the first non-artist, index 0 when both are
   artists): `validateArtistsPaid` (`engine/TransferReducer.kt`) then refuses the whole transfer; a pair of two
   artists can never settle a surplus (`canPair` allows two artists).
2. ⚠ `settlement/SettleTogether.kt` (`tidy`: keeps an invitation only if the invitee has exactly one): two concurrent
   invitations to the same person cancel both silently.
3. `settlement/SettleTogether.kt` (`keepSettled`): does not stamp `updatedAt`; the generic stamp in `engine/Reducer.kt`
   covers only the op's own event, and `Undo` never stamps.
4. `engine/Reducer.kt` (the `updatedAt` stamp for every op with an `eventId`, no change check) + `engine/Undo.kt`
   (compares the whole event): a no-op operation (e.g. the same weight again, `LineupReducer`) blocks a later undo of an
   earlier change on that event with `CHANGED_SINCE`.
5. `engine/RosterReducer.kt`: "not yourself; leave instead" reported as `MEMBER_HAS_HISTORY`; `engine/PairReducer.kt`:
   a missing pair or invitation reported with code `CONFLICT` and reason `NOT_FOUND`, while a true not-found has code
   `NOT_FOUND`.
6. `engine/TransferReducer.kt` (`settleSuggestions`): the event is settled once up front and once per suggestion;
   `settlement/SettlementCalculator.kt`: `parties` computed twice (`mergePairs` inside `settle` and the lazy
   `Settlement.parties`). Performance only.
7. `settlement/SettlementCalculator.kt` (`restShares`): with percentages at exactly 100 % including the fund's, rounding
   leftovers go to participants only; the fund never gets its leftover grosze (probably intended; worth a comment).
8. `engine/Limits.kt`: relies on `!==` list identity; a reducer branch that returns the same list skips the limit
   checks, one that rebuilds an equal list runs them. Documented only by a comment.
9. `engine/Limits.kt` (`ID`) and `engine/AttachmentReducer.kt`: the same id pattern written twice; they now check
   different ids and both reject with `INVALID_INPUT`, so mostly cosmetic.
10. `rules/Viewers.kt` (`mayBecome` looks at unsettled events only) + `rules/Permissions.kt` (own `AddHolding` needs
    `MEMBER`): a viewer who took part in a settled event is refused by role, consistent but a hole in "viewers take no
    part"; `validateNotViewer` runs only when participants are added.
11. `engine/Engine.kt` (`REMEMBERED_REJECTIONS = 20`): more than 20 rejected ops in one lost response come back as
    plain duplicates with no reason.

## Server

1. ⚠ `invites/Invites.kt` (the invite blob is written, then `updateCodes`): if `updateCodes` exhausts its retries the
   invite stays valid for 72 h but is missing from the band's code list, so `list`, `revokeFor` and `delete` never
   see it.
2. `bands/BandAccess.kt` (`load()` defaults to `deleteIfDue = true` and calls `whenDue`): any read, including the
   anonymous `/e/{id}` (`links/LinkPages.kt`) and calendar fetches, can execute a full band deletion in-request; the
   `deleteIfDue = false` convention (`EventPointers`, `BandDeletions`) is the only thing preventing a self-deadlock on
   the `writers` pool.
3. `bands/BandChanged.kt` (`history.append` in `runCatching`): a failed history append is logged and dropped with no
   later repair; a DynamoDB outage leaves permanent gaps undo cannot see.
4. `bands/BandHandlers.kt` (`push`): the band is loaded twice per push (two S3 reads); `Band.create` is built twice in
   `create`.
5. `history/HistoryHandlers.kt` (`history`): the pages are submitted to `reader` before `loadForMember` runs, and only
   cancelled on a failed check; `reader` is a JVM-wide pool of 3 shared by all requests.
6. `lambda/Handler.kt`: hard-coded production bucket and table fallbacks; a misconfigured environment silently talks
   to production (the environment is now read only there and in `LocalServer.main`).
7. `Api.kt` (`appSupported`): `appVersion=0` is always accepted, so the minimum-version gate is advisory (documented as
   deliberate; fine for forcing updates, not for retiring a format).
8. `auth/Sessions.kt` (`SessionRevocations.cache`): grows without bound, never evicted; the same class serves the local
   server.
9. `places/PlacesHandlers.kt` (`locations`): `LocalDate.now(UTC)`, no clock injected at all; untestable with a fixed
   clock.
10. `links/EventPointers.kt` (`inParallel`): blocks on each future with no timeout, on the shared `writers` pool.
11. `shared/.../api/ApiModels.kt` (`ApiErrors.BAND_TOO_LARGE`): in the contract but never sent; a too-large push is
    refused per operation with `LIMIT_REACHED`.
12. `localServer/.../LocalServerTest.kt` sends `?v=0` while the parameter is `appVersion`; passes only because the local
    server has no minimum, so the version gate is not exercised.
13. `devices/DeviceHandlers.kt` (`register`: `inBand` from `bandIds.isNotEmpty()`, no existence check): a stale band
    id left by a failed `removeBandFromUser` keeps registrations accepted for a user who left their last band (the
    record is otherwise cleaned eagerly now).
14. `bands/BandChanged.kt` (`pointers.pointTo`/`forget` not in `runCatching`): their writes are wrapped inside
    `EventPointers`, so a failure no longer fails the push; a failed pointer is only logged and never repaired, so
    that event's link card stays generic.
15. `bands/BandChanged.kt` (the sync message before `removeBandFromUser`, intentional): a device that reacts at once
    can still ask `GET /me` before the band is gone from the user's list; `UserHandlers.me` filters out bands whose
    member is archived or missing, so the window shows nothing today.

## Platform, build, tests

1. `androidApp/build.gradle.kts` vs `composeApp/build.gradle.kts` (`setting()`): a Maps key set only in
   `local.properties` yields `HAS_MAPS_KEY = true` with an empty manifest placeholder (a blank map); the default version
   code is 1 in one and 0 in the other.
2. `androidMain/.../platform/AndroidLocale.kt` (`provideAndroidLocale`): `Locale.setDefault` and `updateConfiguration`
   run in the composable body on every recomposition; `defaultLocale` is captured once, so a later system-language
   change is ignored until restart.
3. `androidMain/.../platform/AndroidSharing.kt` (`rememberShare`): always reports `SHARED` and the chooser
   `startActivity` is unguarded; `ShareResult.COPIED`/`FAILED` are never returned here.
4. `androidMain/.../platform/PlayUpdates.kt` (`LocalContext.current as? Activity`): null inside a `Dialog`'s context
   wrapper; whether a caller (`AppBanner`, `UpdateRequiredScreen`) ever sits in one is unconfirmed.
5. `server/.../FcmSenderTest.kt`: the slow-answer test still spends about 2 s of real time waiting out the sender's
   deadline; the app tests run on virtual time now.
6. `composeApp/build.gradle.kts` (`testTask.enabled = chrome != null`): the browser tests, now including the screen
   smoke tests, skip silently without Chrome; CI relies on the runner image having one, with no setup step.
7. `platform/PlayStore.kt` (`PLAY_PUBLIC = false`): flipped by hand at the production release; the README is silent
   about it.
8. `runtime/AppRuntime.kt` (the `CoroutineExceptionHandler`) and `bands/BandStore.kt` (`println` on a failed write):
   the only logging in the app is `println`; in a release build on Android nobody sees unhandled coroutine failures
   or storage write failures.
