# Kapelusz — Iteration 5

Started 2026-10-23. Same loop as before: a PR per milestone (or per themed batch); branch, implement, review, fix,
push, merge, smoke test. **Before implementing a batch: report the plan and wait for approval.**

## 1. V5-M1 — Fixes from the whole-app review

On 2026-10-23, eight reviewers each read one part of the app: shared rules, shared money logic, server, client sync and
data, notifications, the event screen, the other screens and navigation, and platform/packaging/deploy. Their findings
were de-duplicated, one was checked and dropped (see §1.6), and decided items were left out (§1.6). File references are
to the code as of `events-list-fixes` (PR #38).

Severity: **H** = data loss, a band that can't be used, or wrong money; **M** = should fix; **L** = polish or rare edge
case.

### 1.1 Batch A — Data and sync

**A1 (H) Changes marked saved but never written.**
- Where: `composeApp/.../data/BandRepository.kt` (`flush`, about lines 406–414).
- Cause: `flush` reads the band (or the index), suspends in `store.write`, then removes the band from `dirty` (or
  clears `indexDirty`). A dispatch, rebase or `updateIndex` landing during that write marks the band dirty again, but
  it already was dirty, so the mark is cleared after the *old* copy was written.
- Scenarios:
  - Offline, save a cost with a photo: `AddAttachment` is dispatched while the cost's flush writes; that op exists only
    in memory. Kill the app → the op is lost, and on restart `uploadFor` sees the attachment neither pending nor on the
    server and deletes the file too.
  - `createBand` while an index write is in flight: the new band and `currentBandId` are never saved; after a restart
    the offline-created band is gone and never created on the server.
- Fix: after the write, clear the mark only if nothing changed meanwhile (`if (locals[bandId] === local) dirty -= bandId`,
  same for the index); or clear before writing and set again on failure.

**A2 (H) A band can grow too big to load: the size cap counts characters, not bytes.**
- Where: `server/.../BandHandlers.kt` (about lines 87–95, `encoded.length > maxBandChars`), `Api.kt` (`MAX_BAND_CHARS =
  3_000_000`); also `HistoryHandlers.kt` (`HISTORY_PAGE_BYTES` compared with `content.length`).
- Cause: `String.length` counts UTF-16 characters; a Lambda response is limited to 6 MB of bytes, and the JSON is escaped
  again in `Handler.respond`.
- Scenario: someone adds thousands of costs with long descriptions in a 3-byte script; every push passes the 3M-character
  cap, the band JSON reaches ~7.5 MB, and every state, push and raw response then fails: the band is unusable for all.
- Fix: measure UTF-8 bytes (`encodeToByteArray().size`) with headroom for escaping; same for the history page budget.

**A3 (M) Background problems are silent.**
- Where: `composeApp/.../sync/SyncEngine.kt` (`rejections`, `lostAccess`: `MutableSharedFlow` without replay, `tryEmit`).
- Scenario: an edit made offline is pushed later by the background worker (no UI); the server rejects it (e.g. the role
  changed); the op is removed from pending and the rejection is emitted to nobody: the change disappears silently. Same
  for "you were removed from band X": the band is deleted from the device without a word. More than 16 rejections in one
  round also overflow the buffer.
- Fix: keep rejections and lost bands in persisted state the UI shows and acknowledges.

**A4 (M) A band the server refuses to create retries forever and slows every band.**
- Where: `SyncEngine.kt` (`createBand` handling, about lines 202–224 and 272–276; `syncNow` returns false on ERROR).
- Scenario: a user in 20 bands creates a 21st (created locally first); the server answers 409 `too_many_bands` (or 400
  for limits) every round; status shows ERROR, its ops are never pushed, no message or discard is offered, and the loop
  backs off to 5 minutes for **all** bands.
- Fix: treat 4xx answers to create like a stuck push (shown, with discard); count a round as failed only when retrying
  can help. Optionally check the band limit before creating.

**A5 (M) Sign-out races.**
- A token renewal still running at sign-out can sign the old user back in: `Session.kt` `save` after `signOut` (which
  doesn't take `refreshLock`). Requests outside the sync loop (photo, history, invites, calendar, attachment URL) keep
  running after sign-out. Fix: take `refreshLock` in `signOut` and/or refuse `save` after a sign-out generation change.
- A background run in `syncAll()` when the user signs out reschedules the old user's gig and debt reminders from the
  bands still in memory, and may post transfer notifications (`AndroidAppGraph.afterSignOut` doesn't take
  `afterSyncLock`; `BackgroundSyncWorker` captures `userId` before `syncAll`). Old notifications stay in the tray. Fix:
  check the session user under the lock before rescheduling; run `afterSignOut` under the lock; `cancelAll()` on
  sign-out.
- The sign-out work runs in the Settings screen's coroutine scope; leaving the screen can cancel it partway. Fix: run it
  in the app scope.

**A6 (M) A background sync can force "sign in again" in the foreground.**
- Where: `SyncEngine.kt` (about lines 186, 197), `Session.kt` (about line 148), `AndroidGoogleAuth.refresh`.
- Cause: in the background `refresh()` returns null (no activity); `syncAll` publishes `NEEDS_SIGN_IN`, and the foreground
  then stops until the user signs in by hand; the background attempt also blocks the foreground's silent refresh for
  15 minutes.
- Fix: don't publish NEEDS_SIGN_IN from background runs (or retry once in the foreground); don't count a renewal skipped
  for lack of an activity.

**A7 (M) Anyone can upload into another member's not-yet-uploaded attachment.**
- Where: `server/.../AttachmentHandlers.kt` (`upload`, about lines 330–338): only role ≥ ARTIST and "file not there yet".
- Scenario: member A adds a receipt (upload pending); member B reads the id from the state and uploads first; B's file
  becomes A's receipt, A gets `ATTACHMENT_EXISTS`. Also works for event files only leads may add.
- Fix: record who added the attachment (or read it from the history entry) and allow upload only by them (or admins).

**A8 (M) Removing an attachment deletes its only local file before the server accepts the removal.**
- Where: `composeApp/.../data/Attachments.kt` (`remove` → `uploads.cancel`).
- Scenario: `AddAttachment` pushed, file not uploaded yet; the user removes it; the server rejects the removal (cost
  locked, role changed) → the attachment stays with its file gone everywhere.
- Fix: drop the eager cancel; `uploadFor` already drops uploads whose attachment is gone from the server.

**A9 (L) Smaller sync and data items.**
- After the first batch `pushBand` pushes ops that may not be on disk (the "stored before pushed" check runs once before
  the loop): with a full device, a later batch can be accepted, then the process dies and the next change reuses a
  sequence number and is silently dropped as a duplicate. Fix: check before every batch.
- Losing access to a band keeps a backup of the unsent ops but deletes their attachment files (`loseAccess` doesn't call
  `uploads.keepUnsent`).
- Stopping the loop mid-round leaves the status at SYNCING; a concurrent dispatch during `flush` flashes ERROR with
  backoff.

**A10 (L) Server items.**
- A push that hits the size cap re-encodes the whole band once per operation (`BandHandlers.kt` fallback): a big push on
  a band near the cap can exceed the 30 s timeout and retry forever. Fix: track sizes incrementally.
- An admin can push `LinkUser` and link someone's Google account to a member without them joining; invites aren't
  revoked on a link (only on unlink). Fix: `LinkUser` only from the server itself; revoke invites when a member gets
  linked.
- "Sign out on all devices" leaves the calendar feed link working. Fix: rotate the calendar secret too (or offer it).
- The 20-band limit can be exceeded by parallel creates/accepts (read, then a separate write). An archived member's user
  can't accept an invite for another member (ALREADY_MEMBER). Fix: enforce the limit in the conditional write; handle
  archived members explicitly.
- Minor: `revoke` doesn't uppercase the code; `preview` shows an invite whose issuer is no longer an admin; files
  uploaded after their attachment was removed (presigned URLs live 15 min) are never cleaned up.

### 1.2 Batch B — Rules and money logic

**B1 (H) Reordering the roster can unsettle settled and archived events by 0.01.**
- Where: `shared/.../domain/SettlementCalculator.kt` (`partyOrder`, `splitByWeight` tie-break by roster index) with
  `MoveMember` (`Reducer.kt`).
- Scenario: 100.00 split three ways = 33.34 / 33.33 / 33.33, the extra grosz to whoever is first on the roster; the
  event is settled and archived; an admin moves the third participant to the top → 33.33 / 33.33 / 33.34, two lines are
  0.01 off, the archived event shows Open, refund statuses flip, `Artists.holdsMoney` and `MemberShares.canApply` see it
  as unsettled.
- Fix: break ties by something stable and event-local (the event's participants order, or party id), not roster
  position.

**B2 (H) Viewer gaps: a viewer can end up playing, holding money, or holding the band fund.**
- Where: `Reducer.kt` `setMemberRole`, `linkUser`, `setTreasurer` (the viewer check only runs in `AddParticipant` /
  `CreateEvent`); `Undoing.problems`.
- Scenarios: an admin makes a participant holding 500 a Viewer → they can't record the hand-over (transfers need Member);
  `SetTreasurer(viewer)` is accepted and the fund holder can't record fund transfers; undoing "made a Member" after they
  were added to an event does the same.
- Fix: refuse Viewer while the member plays in a non-archived event (at least while holding money) or holds the fund;
  refuse viewers in `SetTreasurer`; optionally an undo check.

**B3 (L) Rules items.**
- Ownership can be handed to an archived admin, who is then locked out (`Reducer.kt` `transferOwnership`). Fix: refuse
  archived targets.
- A member editing their own cost that someone else just deleted gets "Requires role Lead" instead of "not found"
  (`Permissions.kt` `ownCost`, holdings, attachments); they also can't undo their own `DeleteCost`/`RemoveHolding`
  because ownership is judged on the current band. Fix: missing entity → lowest role, let the reducer say NOT_FOUND;
  judge undo ownership from `change.before`.
- `SettleSuggestions` builds transfer ids `"${opId}-$index"` without a duplicate check: a re-sent op id can create two
  transfers with the same id, and a later `RemoveTransfer` removes both. Fix: reject duplicates.

**B4 (L) Money logic items.**
- The band fund's "not handed over" total nets opposite debts across events (`BandFund.notHandedOver`, shown on the fund
  screen): +100 owed by one event and −100 to pay out in another show as 0 (row hidden); other values show nonsense like
  −900. Fix: two totals: owed to the fund, and to pay out of it.
- A refund already paid out in an event awaiting payment (AWAITING) still shows "Oczekuje" until the organiser pays,
  because only SETTLED events count as paid (`CostLedger` `CostStatuses`). Fix: accept, or count it paid when the owner's
  line has delta ≥ 0.
- `Places.parse`: another URL in the shared text can become the place name; host matching is case-sensitive; a Maps URL
  with raw non-ASCII characters is rejected. Fix: strip all URLs before taking the name; lowercase the host.

**B5 Doc fix.** `ITERATION_4.md` §1.2 still says "Undo cannot bring back … money held by an artist"; that guard was
removed on purpose (artist debts are settled by leads). Remove the line.

### 1.3 Batch C — Screens

**C1 (H) Android auto-update can restart the app while the user has only stepped away.** *Replaced (2026-10-23): remove
automatic installing, keep automatic downloading (see §1.7, C1′).*
- Where: `composeApp/src/androidMain/.../updates/Updater.kt` (about lines 110–114) with `UpdatePolicy.afterCheck` /
  `mayInstallAutomatically`.
- Cause: `checkAndDownload` (start-up and daily) installs right after the download whenever the app isn't visible, which
  is true from `onStop`; only `installWhenIdle` waits the intended 30 minutes.
- Scenario: a new build; the user opens the app (download starts), then takes a receipt photo or switches to Maps; the
  download finishes and the silent install kills the process: the photo and the half-entered cost are lost.
- Fix: remember when the app was last stopped and require 30 minutes out of sight; otherwise only show "Ready".

**C2 (H) Another band's screens come back after switching bands or losing access.**
- Saved tab back stacks survive a band switch: `Navigation.kt` `resetTo` / `select` (`saveState` / `restoreState`;
  `popUpTo(graph, inclusive)` doesn't drop saved stacks). In band A open Settle → an event; tap Events; switch to band B;
  tap Settle → band A's event opens in band B ("This event no longer exists"); same for My costs with a cost form. Also
  after joining, opening a notification for another band, deleting a band.
- Losing access keeps the back stack: `App.kt` (about lines 118–133): after NoBand, opening, creating or joining another
  band lands on the old band's screen (the graph is equal, so the stack is kept).
- Web: browser Back/Forward and bookmarked `#event/…` / `#cost/…` links replay routes in whatever band is current
  (routes carry no band id).
- Fix: clear the saved stacks for every tab in `resetTo` (or key the NavController on the band id); always reset after
  leaving NoBand; redirect to the event list when a route's id isn't in the current band (or put the band id in routes).

**C3 (H) The cost form loses fields on rotation; a lead's cost gets booked to the wrong payer.**
- Where: `composeApp/.../ui/screens/CostScreen.kt` (about lines 122–129): `paidBy`, `date`, `eventId`, `category`,
  `drafts` use `remember`; `amount`, `description` use `rememberSaveable`; the activity has no `configChanges`.
- Scenario: a lead adds a cost, picks "Paid by: Alice", an event and a photo, rotates → "Paid by" becomes the lead, the
  event and category clear, the date resets, the photo is gone; the amount stays; Save books it to the lead.
- Fix: `rememberSaveable` for these (ids as strings, date as ISO text); keep photo drafts across recreation (view model
  or saved state).

**C4 (M) Event screen: lost input and stale dialogs.**
- The Details editor closes and loses the text when the save is refused (`DetailsSection.kt`, `onSave(value);
  onDismiss()`); no counter or cap at `Limits.MAX_NOTES` (2000). Fix: close only on success; add a counter.
- Dialog state lives in `remember` inside LazyColumn items (Transfers: hand-over, part, remove; Collected: edit, add;
  Refunds: edit; Totals: fund amount; Details editor): a sync that changes item heights can scroll the item out and
  dispose the dialog with what was typed; rotation closes them all. Fix: hoist dialog state to `EventScreen` with
  `rememberSaveable`.
- Add guest, Share and Part close even when the op is refused (`ParticipantsSection.kt`, `TransfersSection.kt`,
  `Common.kt` `PartialSettleDialog`); add guest has no name length cap (100). Fix: close only when every op succeeded.
- The collected-entry edit dialog keeps working on an entry that's gone (e.g. an awaited fee received on another phone)
  (`CollectedSection.kt`); the refund dialog uses the cost captured when it opened, so its maximum is stale
  (`RefundsSection.kt`). Fix: re-read by id; close when gone.
- Edit details can resend an already-accepted `UpdateEvent` when `SetEventAgenda` was refused (`EventDialogs.kt`). Fix:
  diff against the new state after a success.

**C5 (M) An admin can archive themselves with one tap and lock themselves out.**
- Where: `RosterScreen.kt` (Archive shown for `!isOwner`, including yourself, no confirmation).
- Fix: hide Archive for yourself, or confirm like the own-role change.

**C6 (M) Fund holder picker offers Artists and Viewers; refusals in settings are swallowed.**
- Where: `Navigation.kt` (about lines 190–212): `treasurerOptions` lists every non-archived member;
  `onTreasurer = { graph.dispatch(SetTreasurer(id)); Unit }` drops the rejection. RenameBand, SetAgendaTemplate and
  DisableBandFund likewise.
- Scenario: turn the fund on and pick an Artist → the switch flips back off with no message.
- Fix: filter Artists (and Viewers) out; show rejections in Settings (e.g. a snackbar via `Actions`).

**C7 (L) Screens items.**
- Refuse is one tap with no confirmation, next to Include (`RefundsSection.kt`); refusing is final for leads. Fix:
  confirm.
- Guest remove is an 18 dp icon; the chip itself announces as actionable but does nothing; share rows have no role or
  edit button (`ParticipantsSection.kt`). Fix: `IconButton` / minimum touch size; an edit button on share rows.
- An Artist's "Do otrzymania" includes refunds of costs from other gigs that aren't listed on that event
  (`ArtistSections.kt`). Fix: also list costs refunded at this event.
- An Artist who owes money sees "you hand over 50 zł" in the event list (`EventsScreen.kt`, `MyNumberText`). Fix: the
  artist wording ("To hand back (a lead settles it)") or hide it.
- A deleted cost (or one from another band) shows "This event no longer exists" (`CostScreen.kt`). Fix: a cost string.
- A bookmarked/reloaded `#settings?page=FUND` (artist) or `AGENDA` (non-admin) leaves a blank page: `onBack()` does
  nothing without a previous entry (`SettingsScreen.kt`). Fix: reset to Settings or Events.
- Opening a band from the switcher while offline does nothing visible when another band is open (`App.kt`, `openError`
  only on the zero screen). Fix: a snackbar or dialog.

### 1.4 Batch D — Notifications and web

**D1 (M) Transfers downloaded by a "sync soon" run are never notified.**
- Where: `composeApp/src/androidMain/.../notify/BackgroundSyncWorker.kt` (`notifyTransfers = !local && !SOON`, and
  `afterSync`).
- Cause: a SOON run syncs, then writes `seen = all transfer keys`, so transfers it just downloaded become "seen" without a
  notification. SOON runs start on every debt/fee reminder and on `onStop` with unsent work.
- Scenario: Dave records "200 zł to you" at 14:00; the user's debt reminder at 18:00 starts a soon-sync that downloads
  and marks it seen; nobody is told.
- Fix: mark as seen only what was on the device before the sync; notify the rest.

**D2 (M) Late debt and fee reminders vanish; gig reminders can come very late with stale wording.**
- Where: `ReminderScheduler.kt` (`schedule`: `setWindow` below Android 12 isn't allowed while idle), `dueDebtReminder`
  (reuses the 2 h lateness filter), `show()` (no lateness check for gig reminders).
- Scenarios: a debt reminder due at 18:00 fires at 21:00 after Doze → dropped, and replanning won't bring it back; a gig
  reminder "Tomorrow: Club gig" can appear on the gig day or after it.
- Fix: `setAndAllowWhileIdle` / `setExactAndAllowWhileIdle` below 12; accept debt/fee lateness up to the next reminder;
  for gig reminders drop them after the event date and rebuild the text when shown.

**D3 (M) On Android 13+ the notification permission is only asked through the gig-reminder prompt.**
- Where: `AppDialogs.kt` (`ReminderPrompt`), defaults in `SettingsRepository.kt` (debt reminders on, gig reminders off).
- Scenario: "Not now", or no upcoming gig of one's own (a lead or treasurer who organises) → debt and fee reminders are
  scheduled but never shown; the receiver also marks them shown before checking the permission.
- Fix: ask when the first debt reminder is planned, or word the prompt to cover debts; don't leave default-on reminders
  silently dead.

**D4 (M) Web storage can hang or fail until a reload.**
- Where: `composeApp/src/wasmJsMain/.../data/IndexedDbStore.kt`.
- Causes: `idbPut`/`idbDelete` listen only to `oncomplete`/`onerror`; a storage-full error at commit fires only `abort`,
  so `write()` never returns (and any lock held around it, e.g. the settings write lock, stays held). The open connection
  is kept for the page's life; when iPhone Safari drops it after the app was in the background, every later transaction
  throws until a reload.
- Fix: `onabort` → reject; clear the cached connection on `onclose`/`onversionchange`, and retry once with a fresh one.

**D5 (M) Web "Sign in again" can stop responding.**
- Where: `composeApp/src/wasmJsMain/.../auth/WebGoogleAuth.kt` (one `pending` callback; `refresh()` overwrites the sign-in
  button's handler and clears it on its 15 s timeout without restoring it).
- Scenario: in the "sign in again" dialog, a background sync calls `refresh()`; after 15 s, clicking the Google button
  delivers a credential nobody receives.
- Fix: keep the button handler and refresh waiters separately, or restore the previous handler.

**D6 (M) On a weak signal the web app can stay on the old version.**
- Where: `composeApp/src/wasmJsMain/resources/sw.js` (`networkFirst`, about lines 64–79).
- Cause: when the 5 s timeout wins, the late network answer's `event.waitUntil(...)` throws (the event is finished);
  caching the new `composeApp.js` and ~16 MB of wasm isn't kept alive, so it never completes; every start times out again
  and the "new version" reload reloads the old one.
- Fix: call `event.waitUntil(network.then(cache…).catch(…))` synchronously before `respondWith` returns.

**D7 (L) Notifications and web items.**
- Gig reminders aren't re-checked when they fire (removed from the gig, moved, archived), and their text is fixed when
  scheduled; no `LOCALE_CHANGED` handling.
- Service worker: a network error status (5xx, 403, captive portal) within 5 s beats the good cached copy. Fix: use the
  network answer only when `response.ok`.
- A relaunch from Recents (after a reboot) repeats the task's original join link, shared place or notification
  (`MainActivity.kt`). Fix: skip when `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`.
- Sharing an invite on the web fails silently when `navigator.share` rejects with something other than AbortError
  (`Share.wasmJs.kt`; `RosterScreen` ignores FAILED). Fix: fall back to the clipboard; show the failure.
- The Android "get the app" banner caches a failed check for the whole process (`AppSuggestion.android.kt`). Fix: cache
  only successes, with a time limit.
- Some document providers return no MIME type, so a valid PDF or JPEG is rejected (`Files.android.kt`). Fix: fall back to
  the file name extension. `FileStore.kt` leaves `*.tmp` files when a write fails.

### 1.5 Batch E — Deploy

**E1 (L)** `scripts/deploy.sh` builds server, web and Android in one Gradle call with the default daemons, which ran CI
out of memory before (exit 143); a smoke-check failure doesn't roll back (it runs after the alias switch and uploads).
Fix: reuse CI's build settings (two Gradle calls, in-process Kotlin, `-Xmx5g`); consider smoke-checking the new Lambda
version before switching the alias.

### 1.7 Scope by effort (decided 2026-10-23)

None of these is critical in practice: most are edge cases that rarely happen. Each was judged by **effort** and **how
much it costs the code** (more state, plumbing, special cases).

- **Tier 1 — a few lines, code as clean:** A2, A5c (sign-out in the app scope), A8, A9a (check storage before every
  batch), B3a (no archived owner), B3c (no duplicate settle ids), B5, the manifest `configChanges` line (covers C3 and
  the rotation part of C4: turning the phone, dark mode, language, split screen and foldables no longer rebuild the
  screen), C5, C6a, C7 (confirm Refuse; bigger guest remove; Artist "to hand back" wording in the list; "Cost not found"
  text; Part closes only on success), D2a (idle-allowed alarms below Android 12), D7 (service worker uses only OK network
  answers; ignore relaunch from Recents; update banner caches only successes; temp file cleanup), A10d (uppercase in
  invite revoke).
- **Tier 2 — one contained change:** A1, A5b, A6, A9b, B2, B4a, B4c, C1′ (remove auto-install, keep auto-download),
  C2a+b, C4a, C4c, C4d, C7 (Artist payout lists costs refunded at that event; blank settings page from a bookmark;
  feedback when opening a band offline fails), D1, D2b, D3, D4, D6, D7 (share falls back to the clipboard; file type from
  the extension), A10b (calendar link renewed on "sign out on all devices"), E1a.
- **Tier 3 — new state or plumbing:** A4, A5a, A7, A10a, A10c, B3b, C2c, C6b, C4e, D2c, D5, E1b.
- **Tier 4 — invasive:** A3, C4b; and B1 (small code, but changing the rounding tie-break moves the leftover grosz in
  **existing** settled events, which would show 0.01 off after the deploy; reordering the roster is rare).

**Doing now:** all of Tier 1, and from Tier 2: A1, A5b, A6, B2, C1′, C2a+b, C4a, C4c, C4d, D1, D2b, D3, D4.
**Not now:** the rest of Tier 2, Tier 3 and Tier 4 (open for later).

Two PRs:
1. **Core** (shared rules, server, sync and data, Android notifications): A1, A2, A5b, A5c, A6, A8, A9a, A10d, B2, B3a,
   B3c, B5, D1, D2a, D2b.
2. **App** (screens, platform, web): the manifest line, C1′, C2a+b, C4a, C4c, C4d, C5, C6a, C7 (Tier 1 items), D3, D4,
   D7 (Tier 1 items).

### 1.6 Checked and dropped, or decided

- **Dropped:** A10's "an admin can push `LinkUser`": `Permissions.check` already refuses `LinkUser` from any user
  (INVITE_ONLY).
- **Dropped:** "undo of a delete brings back attachments whose files are gone": undo is already refused when it would
  restore removed attachments (`BandChange.restoresAttachments` → `NOT_UNDOABLE`).
- **Decided, left as is (2026-10-23):**
  - An Artist can be told to pay back after their share is lowered (leads settle it).
  - Making someone an Artist after they were handed or overpaid money (role changes handle it well enough).
  - The hand-over limit "no more than the sender holds" stays in the dialog only; the server enforces only the
    Artist cap.

## 2. V5-M2 — Undo from the snackbar (grilled 2026-10-23)

Some removals apply at once and offer **Undo** in a snackbar instead of asking first in a dialog.

### 2.1 Mechanism: hold, then send (like Gmail's "undo send")
- The change applies on screen at once, but is **held**: not synced, and nothing queued after it is synced either (later
  changes may build on it). `LocalBand.holds`: op id → until when.
- **Undo withdraws it** from the queue and the screen is rebuilt without it; nothing reaches the server or the history.
  No `Undo` operation, no server change. (Considered first: sending at once and undoing with a real `Undo`, which needs
  the history entry's name from the push answer and has races with a push in flight or a lost answer; dropped.)
- The hold ends when its snackbar goes (10 s, the ✕, pushed out by a 4th, the screen left or the app stopped): the
  change then syncs. A hold also has an expiry (15 s), saved with the queue, so a change held when the app was killed
  is sent on the next start; past it, Undo says "already sent; undo it from the history".
- Changes made during the hold that only made sense because of it (e.g. remove a holding, then take its holder out of
  the event) are refused with the usual message after an Undo; "Settle" is computed when applied, so it is recalculated
  on the restored state. Checked case by case: nothing can be left inconsistent.

### 2.2 Snackbars
- 10 s; stacked, at most 3, each with its own time and a ✕; a 4th pushes out the oldest (which then syncs).
- Per screen (the event and cost screens): leaving the screen ends the holds.

### 2.3 Which actions
- **Undo snackbar:** removing a guest (tapping the chip); removing an attachment on the event and cost screens;
  "Refuse" of a cost.
- **Keep the dialog — money:** transfers and settling, deleting a cost, removing a holding, a fund entry, a refund;
  Paid / Welched. Also deleting an event (big, often takes money with it) and attachments inside the holding dialog
  (a snackbar would be hidden behind the dialog).
- **Unchanged:** member chips in an event (picking a line-up is common; tapping again is the undo).

## 3. Event screen, sharing and wording (grilled 2026-10-24)

1. **Copy the amount:** a copy button left of each suggested transfer's amount in "To settle", for everyone who sees
   it; copies the amount alone as typed into a bank app (`2304,45` in Polish, `2304.45` in English, always two
   decimals, no currency) and says "Copied 2304,45". Not on recorded transfers.
2. **The header moves into the Gig tab:** nothing between the title and the tabs. The Gig tab starts with "date ·
   place" and Navigate on its right, then the set times; then details, files, line-up. The status becomes a
   full-width row on top of the Money tab (no extra note for archived events: everything is greyed out). The Artist
   view likewise.
3. **Avatars on the events list** keep their size and overlap more to fit beside the text; below about a third of each
   showing, the first ones and "+N" (the true total; no cap of 8 any more).
4. **Share info** replaces the top bar's share icon (which shared the settlement, as the Money tab's button still
   does): a full-width button at the end of the Gig tab, for everyone, drafts too. Name; Date, Place, and Map when the
   event has a stored pin; Plan, a time per line; the notes as they are; Line-up on one line; the app's address last;
   parts apart by a blank line, missing ones left out. The settlement's footer is now the address alone too.
5. **Wording:** money is "received" or "planned", not "collected" or "from the organiser", everywhere in both languages
   (section, buttons, "Received by", the shared settlement, history, errors, role descriptions): "Add received money",
   "Add a planned payment", Welched asks "Didn't pay?". "Organiser" stays only as the name of who holds a planned
   payment.

## 4. Event pages, settling with Undo, attachments viewer (grilled 2026-10-24)

1. **Settling with Undo:** Settle, Confirm (keep for the fund), Settle all and Part → OK record at once with the Undo
   snackbar (§2), e.g. "Recorded: Ania → Bartek 250,00 zł" / "Settled everything".
2. **Images as thumbnails:** square ~96 dp thumbnails in a wrapping grid, without names; PDFs stay rows. Each file is
   downloaded once and kept on the device (disk cache by attachment id, ~100 MB, offline too; on the web only for the
   session); one still uploading shows from the local copy.
3. **Full-screen viewer:** pinch to zoom, pan, double tap; swipe between all attachments of the same cost, received
   money or event ("2 / 4 · name"); top bar: close, and on the right copy (the actual image; PNG on the web, where the
   browser allows), rename and remove for whoever may edit (remove with Undo where the snackbar can show). On Android a
   PDF opens in it too (pages rendered by the system, scrolling vertically, zoomable; copy copies the visible page) with
   "Open in app" handing it to the phone's PDF reader; on the web PDFs open in the browser as before. Zoomed in, a
   picture or PDF let go while moving glides on and slows down, and a sideways swipe that starts at the side already
   in view goes to the next (or previous) attachment without zooming out first (added 2026-10-24).
4. **Three pages: Gig | Money | Settlement.** Material tabs (text, underline; no checkmark, no borders), fixed at the
   top; swipe between pages; each keeps its scroll position. Money: status, totals, received money, refunds.
   Settlement: status, problems, who gets what, To settle, recorded transfers, Share settlement, history. The debt
   reminder opens Settlement at To settle. Artists keep two pages (Gig, Money), with the same tabs and swiping.
5. **Participants as rows** (as artists see them): avatar and name, a grey line with the share when not the default
   100%, edit (the share dialog) and remove (at once, with Undo; a participant holding money still cannot be removed)
   for whoever manages participants. Members in roster order, then guests. Members not taking part stay tappable
   chips in one wrapping row, with "Add guest". Replaces "Adjust shares".
6. **To settle, together:** Settle all, Hand over money and Share settlement one under another; Hand over records
   with Undo (the dialog stays for who, to whom, how much); "Recorded transfers" folds, closed on each visit, with
   their count.
7. **Several files at once:** the file picker (Android, web) takes several; each becomes an attachment, as many as
   there is room for ("Added 3 of 5 files: there is room for 5 here"); one too large or of another type is skipped and
   said, the others still attached. The camera takes one.
8. **Costs' files on the event:** each cost in the Money page's refunds (included, waiting, a member's own) shows its
   files under it, read-only: thumbnails and PDFs, opened in the viewer, to check a receipt before including it.
9. **"When and where" edited in place:** the date, place and times block on the Gig page has a title and its own edit
   button, like the sections under it, opening the event's dialog (name, date, place, pin, times); the top bar's edit
   button is gone (its menu stays). Missing place or times are offered to editors ("+ Add place", "+ Add times").

## 5. Milestones and task checklist

### V5-M1 — Fixes from the whole-app review (scope: §1.7)
- [x] PR 1 — Core: A1, A2, A5b, A5c, A6 (status part; the renewal throttle stays), A8, A9a, A10d, B2, B3a, B3c, B5,
  D1, D2a, D2b; plus the awaited fee's "Paid" / "Welched" buttons (see ITERATION_4 §2.2)
- [x] PR 2 — App: manifest configChanges, C1′, C2a+b, C4a, C4c, C4d, C5, C6a, C7 (Tier 1), D3, D4, D7 (Tier 1)
- Later, not scheduled: the rest of Tier 2, Tier 3, Tier 4

### Performance and guest fixes (between milestones)
- [x] History in one request for three months; per-request TIMING log; 32 S3 connections kept for reuse (PR #41)
- [x] History in DynamoDB, a record per operation; history names a removed guest; Add guest with the share editor;
  guest removal with an Undo snackbar (a stop-gap re-adding the guest; replaced in V5-M2) (PR #42)

### V5-M2 — Undo from the snackbar (§2)
- [x] Grilled (§2)
- [x] Implement: holds in the queue and sync, stacked Undo snackbars, guest / attachment / refuse (PR #43)

### Fixes and features after V5-M2
- [x] A member sees their own waiting and refused costs in an event's refunds (PR #44)
- [x] Event screen, sharing and wording (§3) (PR #45)
- [x] Event pages, settling with Undo, attachments viewer (§4) (PR #46, #47)

### V5-M3 — Settling together (§7)
- [x] Grilled (§7)
- [x] Implement: pairs in the shared model and settlement, invitations, member dialog for everyone, events-list card (PRs #48, #49, #50)

### V5-M4 — BLIK numbers (§8)

- [x] Grill (2026-10-24)
- [x] Implement: the number on the member, Settings row, BLIK icon and sheet on transfers, hand-over

### V5-M5 — Place suggestions in Location (§9)

- [x] Implement: server endpoint calling Google Places API (New), suggestions under Location, Terraform variable for the key
- [x] Set up: Places API (New) and Geocoding API enabled, key restricted to them, `kapelusz_google_places_api_key` applied
- [x] Implement §9.1: Location is the place itself (full address on pick, Maps links read by the server, no stored link)

### V5-M6 — Event links (§10)

- [x] Grill (2026-10-24)
- [x] Implement: `/e/<eventId>` in shared texts, the calendar and Copy link; opened on Android (App Link) and the web

### Later

- [ ] Consider one "Leader" role instead of Admin and Lead: in practice a band's leader is its admin (2026-10-24)

## 6. Decision log

| Date | Decision |
|---|---|
| 2026-10-23 | V5-M1 is the whole-app review's findings, in themed batches; each batch's plan is reported and approved before implementing. |
| 2026-10-23 | Findings are mostly rare edge cases: scope by effort and code cost (§1.7). Doing Tier 1 and the valuable Tier 2 items in two PRs; the rest later. C1 replaced by removing auto-install (auto-download stays). B1 skipped (would shift existing settled events by 0.01). A10 LinkUser finding dropped (already refused). |
| 2026-10-23 | History moves to DynamoDB (one record per operation); the old S3 history is dropped (one user so far). Current band state stays one S3 file; event sourcing considered and declined (rule changes would rewrite replays; snapshots needed anyway). |
| 2026-10-23 | Removals should apply at once with an Undo snackbar rather than a confirmation dialog; done properly (a real `Undo`) in V5-M2; guest removal gets a stop-gap now. |
| 2026-10-23 | V5-M2 undo works by holding a change back from syncing while its snackbar shows (withdraw on Undo), not by sending and undoing; money removals and deleting an event keep their dialogs; member chips unchanged. |
| 2026-10-24 | Settling together (§7): pairs only, stored once per band and applied when settling; transfers always recorded between real people (no pair id); only settlement and its transfers change, everything else stays per person. |
| 2026-10-24 | BLIK numbers (§8): one phone number per member and band, set only by its member; shown only to who settles the transfer, hidden in the app only (every device still gets the band's data). |
| 2026-10-24 | Pull requests' CI runs the tests only; the optimised release builds (R8, wasm-opt, about 5 of 9 minutes) run on main after the merge, before the deploy, which waits for them. A failure only they show now breaks main instead of a pull request (was ITERATION_3 R11: before a merge). |
| 2026-10-24 | Event links (§10) carry only the event id: ids are unique across bands, so the app finds the band itself and the link reveals nothing about it. |
| 2026-10-24 | Place suggestions (§9) go through our server: the Google key never ships in the app or the web page, and only leads (who edit events) use it. |
| 2026-10-24 | Artists don't settle together after all: their payout with the pair's numbers was confusing ("still to receive" twice their share). Becoming an artist ends a pair. |

## 7. V5-M3 — Settling together (grilled 2026-10-24)

"Wspólne rozliczenie" / "Settle together": two members settle as one party, e.g. a couple sharing a wallet.

### 7.1 Model
- Pairs only; a member is in at most one pair or invitation at a time. Stored on the members (`settlesWith`, both
  naming each other; `invitedToSettle` on the inviter), so undo and history see it with the member.
- Applied when an event is settled: in an event where both take part they are one party; where only one plays, they
  settle alone. When a pair ends, the events it settled together keep it (`Event.settledTogether`), so they stay
  settled; only events not settled yet are split again.
- **Only Settlement and its transfers change.** What each collected, holds ("You hold"), spent, is refunded and earns
  stays their own, as do the fund, shares and participants.

### 7.2 Invitations and who may do what
- Each for themselves only (nobody, admins included, pairs others): members and up invite, answer and end a pair.
- Both need an account and must be members or up (not viewers, not artists: dropped 2026-10-24, an artist's payout
  with a pair's numbers was confusing), neither archived, in a pair or with an invitation waiting either way.
- The pair ends by itself (and an invitation is cancelled) when either is archived, unlinked, or made a viewer or an
  artist. Moving between member, lead, admin and owner keeps it. The confirmation of that change says so, with the
  number of events not settled yet.
- History lists every step; none can be undone (from History or a snackbar). Undoing the archive or role change that
  ended a pair brings it back; undoing an older change keeps a pair formed since.

### 7.3 Settlement and transfers
- The two lines are summed into one party ("Bob and Alice"), in the place of the partner first in roster order;
  suggestions are between parties. Tapping the pair's row in the summary shows each partner's payout and holdings.
- A pair's transfer is recorded between real people (`RecordTransfer.together`, `SettleSuggestions`), against the
  state it is applied to:
  - paid by a pair: out of what each holds, the bigger holder first, so nobody's "You hold" goes below zero; one tap
    may record two transfers (Undo takes back both); an artist never pays; Settle all splits each transfer against
    what the ones before it left;
  - received by a pair: the one owed more gets it;
  - between the band fund and the treasurer's pair: always the treasurer, who holds the fund (the pair is matched with
    the fund first, as the treasurer alone would be).
- Either partner settles, confirms or settles part of the pair's transfers, in events where both play; the Settle tab shows them to both, and
  debt reminders remind both. "You hold" stays one's own: only what the pair still has to pass on counts.
- The shared settlement text names the pair in "Still to pay"; recorded transfers name the real people.

### 7.4 Screens
- Tapping a member on the roster opens their dialog for everyone who sees the roster: admins as before, with a linked
  member's role behind a "Role: …" row (the radio buttons stay in view when creating an invite); leads see the default
  share and settling together; members only settling together. Nothing is shown on the roster rows.
- The section: "Settle together with me" (with what it means), "Waiting for … · Cancel invitation", Accept / Decline,
  "Settles together with …", or "You settle together with … · Stop settling together" (with a confirmation naming
  how many events not settled yet are split again).
- An invitation waiting for you is a card on top of the events list until answered.

## 8. V5-M4 — BLIK numbers (grilled 2026-10-24)

Bands settle mostly in cash, sometimes by BLIK to a phone number; the number is a tap away instead of asked for.

- **What:** one BLIK phone number per member, per band (on `Member.blikPhone`). Any phone number: 7 to 15 digits
  with an optional `+` (`00` read as `+`); spaces, dashes, dots and brackets dropped. A Polish one (9 digits, or +48
  and 9) is shown `600 123 456` and copied without +48; any other as stored.
- **Who sets it:** only the member, for themselves (`SetBlikPhone`), from artists up (artists are paid too; viewers
  never). In Settings, in the band's part: "Your BLIK number", a dialog with Save, Remove and Cancel.
- **Who sees it:** only who pays that transfer (that member, either partner of a paying pair, or the fund's holder
  for the fund), and may settle it: a phone icon beside the transfer on an event's To settle and on the Settle screen
  opens a sheet with the number(s) and a copy button each; the copy-amount button stays as it was. In "Hand over
  money" the chosen recipient's number shows under the dropdowns when you hand over your own money. Not to the
  recipient, who read the icon as owing (changed 2026-10-24), nor to a lead recording for others.
- **Settle says which way (2026-10-24):** the button on a suggestion (and in its Part dialog) is "Paid" for who pays
  it, "Received" for who gets it, "Settle" for a lead recording others'; it records the same. Both sides keep it: the
  receiver confirms cash in hand, and a forgetful payer doesn't leave it open.
- **Pair or fund as recipient:** the fund's holder for the fund; for a pair, both partners with a number, the one the
  transfer is recorded to (owed more) first, the other marked "settles together".
- **Not in:** Share settlement texts, the history (the server doesn't record the operation), undo (not undoable;
  undoing other changes leaves it).
- **Unlinking** the member's account removes the number (it was that person's); archiving keeps it.
- **Hidden in the app only:** every device still receives the band's data with all numbers, as with names.

## 9. V5-M5 — Place suggestions in Location (2026-10-24)

- **In the event dialogs** (new event, "When and where"): typing 3 or more characters in Location shows, after a
  ~500 ms pause, up to 5 places from Google under the field (name, and the address in grey), with "Powered by Google".
  Picking one puts in its name and full address (§9.1). Free text still works; offline, or with place search off, the
  field is plain text.
- **Through our server:** `GET /bands/{id}/places?q=…&lang=…`, for leads up of that band (who edit events); 3 to 100
  characters; the app's language. The server calls Places API (New) Autocomplete with only the fields shown. No key
  (`GOOGLE_PLACES_API_KEY` unset) or no answer: 503 `places_unavailable`, and the app shows no suggestions.
- **The key:** restricted to Places API (New) in the Cloud console (an IP restriction does not fit Lambda), set through
  the Terraform variable `kapelusz_google_places_api_key`; Geocoding API is allowed on it too (§9.1). It takes effect with the next deploy (each publishes a
  Lambda version with the configuration of that moment).
- **The band data:** only the location text (§9.1 removed the stored Maps link).
- **The field (2026-10-24):** one line of text that wraps when long (a full address shows whole; Enter adds no line),
  with a clear button while it has text.

### 9.1 Location is the place itself (2026-10-24)

No separate Maps link any more: Location says where, and Navigate (also in reminders, the calendar feed and the
shared info) opens Google Maps directions to that text, which takes an address or a pin's coordinates alike.

- **A picked suggestion:** its name at once, then its name and full address from Place Details (same session as the
  search, so the search itself is free): "Ołówek, Legnicka 36, 53-673 Wrocław" (no country; no name twice when an
  address was picked). Typed over meanwhile: left as typed.
- **Paste from Maps, and Share from the Maps app:** the server reads the link, `POST /bands/{id}/places/resolve`
  (leads up). A short link (`maps.app.goo.gl`) is followed to the full one, only through Google's hosts (also past
  the EU consent page); the full link gives the place's name and exact pin (`!3d…!4d…`, else the map's `@` centre).
  - A named place: its name and the address at its pin (Geocoding API, in the app's language), e.g. "Klub Stodoła,
    Stefana Batorego 10, 02-591 Warszawa"; no address there: the name and coordinates.
  - A dropped pin: its coordinates, `52.229722, 21.012194` (6 decimals).
  - A searched text (`?q=`): that text.
  - Unreadable (offline, another kind of link): the name Maps shared, if any; else "Couldn't read the link".
- **Removed:** `Event.mapUrl` and the operations' `mapUrl` (old data and old apps' operations still read: unknown
  fields are ignored); the "Linked to the map" row. Existing events lose their stored pins; Navigate goes to their
  location text.

## 10. V5-M6 — Event links (grilled 2026-10-24)

- **The link:** `https://kapelusz.jacekgajek.com/e/<eventId>`. Event ids are UUIDs, unique across bands: no band in
  the link; the app looks for the event in the user's bands.
- **Where:** the last line of Share info and Share settlement (was the app's address); the calendar feed's
  description ends with "Band · <event link>" (its URL stays the directions, §9.1); "Copy link" in the event's ⋮
  menu, for everyone ("Link copied").
- **Opening it:** on Android with the app (an App Link: the manifest claims `/e/`, the domain is verified through
  `assetlinks.json`) the app opens directly; elsewhere (iPhone, desktop, Android without the app or with an app from
  before) the web app opens it. Either way:
  - in one of the user's bands: that band (switched to if needed) and the event, as a reminder opens it;
  - signed out: after signing in; not on the device (a gig just created, a band not on this device yet): "Opening
    the event…" while all the user's bands are brought up to date from the server, then looked in again; the server
    out of reach: "Couldn't open the event: no connection";
  - not in the user's bands, an artist's gig they don't play, or deleted (not told apart): "You don't have access to
    this event. Ask your band leader to invite you." ("band leader": what bands call their admin);
  - the address goes back to the app's start, so a reload doesn't open it again (as join links).
