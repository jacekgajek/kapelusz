# Kapelusz — Iteration 3

The third planning iteration, building on app 1.0.0 (ITERATION_2.md; references like "V2 §7.2" point there). It
starts with a full review of the code, done on 2026-10-22 by six parallel reviews (domain logic, server and security,
Android, UI, sync and offline, code quality), and turns its findings into fixes: **P1** (crashes, data loss, wrong
money) is milestone V3-M1, **P2** (should fix) V3-M2, **P3** (quality) V3-M3. Then the features chosen for this
iteration (§2). Every finding was checked against the code; file references are to the code as of 1.0.0.

Workflow as in iteration 2: one branch and PR per milestone, an independent review of each milestone, tests for every
fix, merge when CI is green, smoke test of the deploy.

## 1. Review findings and how to fix them

### 1.1 P1 — must fix (V3-M1)

**R1. Limits on everything that comes in.** One rule for several findings: the reducer (shared, so client and server
enforce the same) validates every input.
- *Problems:*
  - Amounts have no upper bound: one huge collected amount overflows `splitByWeight` (`SettlementCalculator`), and
    `settle()` throws for the whole band: every screen with that event crashes, and the server push fails.
    `Money.parse` also overflows silently.
  - Ids are free text: an event id with line breaks injects made-up entries (with links) into every subscriber's
    calendar (`CalendarFeed`, `UID:`); a guest or member id `band-fund` is taken for the band fund; a new member id
    can equal a guest id and inherit that guest's money.
  - Texts (names, descriptions, notes) and the number of costs are unbounded: a band's `state.json` can grow past the
    6 MB a Lambda response may have, and then nobody can load the band.
  - Undo-of-undo chains can nest without limit; decoding such a history entry overflows the stack.
- *Fix:*
  - `Money` inputs (holdings, costs, transfers, refunds, fund amounts and entries) at most 10^12 minor units;
    `Money.parse` rejects longer numbers; overflow-safe arithmetic (`multiplyExact`) in the split.
  - Ids `[A-Za-z0-9_-]{1,64}`, `band-fund` reserved, member ids unique against guest ids too.
  - Text limits (names 100, descriptions 500, notes 2000 characters); at most 2000 costs per band; the server refuses
    a push whose resulting band is above 3 MB (413, before writing).
  - At most 4 nested undos; the calendar feed escapes the UID as text anyway.
- *Test:* reducer tests for each limit (just below and above), the overflow scenario, a calendar test with a hostile
  id, a server test for the size cap.

**R2. Members cannot put refunds into settled or archived events.**
- *Problem:* `AddCost` / `UpdateCost` with "incurred for" an event (member level for one's own cost) pre-include a
  refund (`includeOwnCosts`), normally a lead's decision (`SetRefund`). It works on settled and archived events, which
  reopen with new debts.
- *Fix:* the refund is pre-included only when the event has no recorded transfers and is not archived; otherwise the
  cost just remembers the event, and a lead includes it. The same rule for changing a cost's event or amount (and the
  automatic adjustment goes to the cost's new event, not the old one).
- *Also:* "Incurred for" offers only events the cost's owner ("Paid by") takes part in, and the reducer refuses
  any other event for a cost (a cost can only be refunded at an event its owner plays anyway). Changing "Paid by"
  narrows the list again; a cost already linked to an event its owner left keeps showing that event until changed.
- *Test:* a member's cost for a settled event stays pending there; a lead's still works as before; a cost for an
  event its owner is not in is refused, and the picker lists only the owner's events.

**R3. Double taps and crashes in the UI.**
- *Problems:*
  - Two quick taps on Back pop past the first screen: a blank screen with no tabs (web: stuck until reload).
  - Save on Add cost and Create on New band can be tapped again while the screen fades out: duplicate cost or band.
  - The "Add collected money" dialog takes the first allowed person every time it is drawn; if a sync removes the
    user from the event meanwhile, the app crashes (`CollectedSection`, also `TransfersSection` hand-over).
  - The app goes back to the event list on a language change and on Android rotation (a sign-in counter re-triggers
    `resetTo(EventsRoute)`), contrary to its own comment.
- *Fix:* one guard for navigation actions (only from a resumed screen, never popping the last entry); submit buttons
  disabled after the first success; dialogs close themselves when their choices become empty; each sign-in handled
  once (an event, not a counter).
- *Test:* UI state tests where possible; by hand on the emulator (double taps, rotation, language).

**R4. Sync must never lose a change.**
- *Problems:*
  - A band stored on the device but missing from the in-memory index (an unreadable index, or a crash between two
    writes) is downloaded fresh over the stored file: its unsent changes are lost.
  - A failed write (disk full) escapes a coroutine and crashes the Android app; the change is forgotten.
  - An attachment is published (the operation queued) before its file is stored: killed in between, everyone sees
    "not uploaded" forever.
  - A change made just before leaving the app on Android waits for the 4-hour background sync.
- *Fix:* before downloading a band, load the stored copy if there is one; back up an unreadable index and recover
  bands from their files; `flush` catches failures, keeps the band marked as unsaved and retries, and the app scope
  gets an exception handler; store the attachment file first, then queue the operation; on leaving the app with
  unsent changes, a one-time background sync (network required, expedited).
- *Test:* repository tests for each scenario (unreadable index, failing store, attachment order).

**R5. Deploys that silently break the site.**
- *Problems:* a local `deploy.sh` (without `ANDROID_CERT_SHA256`) deletes `/.well-known/assetlinks.json`, so Android
  stops opening invite links in the app; an unset GitHub variable builds the app with an empty server address.
- *Fix:* the sync keeps `.well-known/` unless it writes it; blank values count as missing (and the deploy fails on a
  missing API address); a post-deploy smoke check (the API answers 401, the page and `version.json` load) that fails
  the job.
- *Test:* `deploy.sh` with a stubbed `aws` (as for the version guard).

### 1.2 P2 — should fix (V3-M2)

**R6. Server: outsiders and heavy users.**
- *Problems:* undo lookups run before the membership check (anyone with a Google account can make the Lambda read up
  to 500 files per request); no limit on bands per user or attachment storage; one shared throttle for all callers;
  sessions renew forever and cannot be revoked (the signing key sits in the data bucket); invite codes can end up in
  error logs; a push racing a band deletion answers 500; production CORS allows `http://localhost:8080`; the
  attachment file name is not unique across costs.
- *Fix:* membership first, at most 20 undos per push; at most 20 bands per user and a storage budget per band;
  reserved concurrency on the Lambda as a cost cap (WAF per-IP limits optional); sessions with an absolute lifetime
  (90 days) and a per-user "signed out everywhere" time; mask invite paths in logs; a missing band during a write is
  "band gone"; localhost only outside production; the cost id in attachment keys.
- *Test:* server tests for each.

**R7. Android: one screen, punctual reminders, tidy updates.**
- *Problems:* a share from Maps or a link from another app can start a second copy of the main screen; when it closes,
  silent Google renewal loses its screen and the user may be asked to sign in again. A reminder Android delivers late
  (allowed up to an hour) is cancelled by the next re-planning and never shown. The "update ready" notification stays
  after the update is installed or declined. The updater reads settings off the main thread (races the UI); the APK
  copy runs on the wrong dispatcher. Notification texts mix languages when the app's language differs from the
  phone's. The sign-in and update screens sit under the system bars on Android 15+.
- *Fix:* one instance (`singleTask` or a trampoline for share/view intents) and the sign-in screen tracked by
  lifecycle; re-planning keeps due-but-unshown reminders (shown ones recorded by the receiver); cancel the update
  notification when it no longer applies; settings on the main thread, file copies on IO; the app's language for
  every notification text; system-bar insets on all screens.
- *Test:* reminder rules tests (due-but-unshown kept); the rest on the emulator.

**R8. UI: edits, numbers, wording.**
- *Problems:* saving a form sends the fields as they were when it opened, overwriting what someone else changed
  meanwhile; "Part" in To settle can record a transfer between the wrong people after a sync reorders the list; in
  English "1,000" is read as 1.00; "Create invite" can run twice and show the replaced code; edits are lost on
  rotation; "Edit details" closes even when refused; history "Load more" can land in another month and a failed one
  hides the list; Polish: a wrong plural (`delete_band_members`, 2–4) and masculine-only texts; error texts in the
  browser's instead of the app's language on the web.
- *Fix:* forms send only what the user changed (compared with the values at opening); rows keyed by their parties;
  amounts parsed per language (thousand separators); busy flags on invite actions; `rememberSaveable` for forms;
  dialogs stay open on refusal; "Load more" tied to its month; the Polish texts fixed; strings resolved in
  composition.
- *Test:* parsing tests per language; UI state tests where possible.

**R9. Sync: removed bands, sign-ins, sequence numbers.**
- *Problems:* a band the user was removed from (or that was deleted) and that is not open keeps feeding reminders until
  a restart; two simultaneous "unauthorised" answers can drop a fresh session and force a sign-in; a device whose
  sequence number goes backwards (power loss after a push; two web tabs) has its next changes dropped as duplicates;
  the sync shows "offline" for unreadable data and bugs; one failed IndexedDB open breaks web storage until reload; a
  push the server always refuses (e.g. too many devices) blocks the band's queue without a message.
- *Fix:* bands no longer listed are checked and dropped; a refused token is only discarded if it is still the current
  one; files synced to disk before replacing them, the web tab hand-over waits for the old tab; errors classified
  (offline / server / data); the IndexedDB cache cleared on failure; non-retryable refusals shown with an option to
  export or discard.
- *Test:* sync engine tests for each.

**R10. Money rules: the band fund.**
- *Problems:* an event paid only from the fund (negative fund amount, refunds, no collected money) stays a draft and
  cannot be settled automatically; fund entries and fund transfers can still be changed after the fund is switched
  off; archived events accept every operation.
- *Fix:* an event is a draft only when nothing at all is in it; fund changes need a treasurer (or must leave the fund
  in a state it could be switched off in); archived events are read-only, except restoring and deleting them (done so in
  V3-M2).
- *Test:* settlement and reducer tests.

**R11. CI.**
- *Problems:* CI builds the development web app, while deploy ships the optimised production build (its failures only
  show after a merge); actions with deploy rights are pinned to movable tags; the keystore is decoded twice.
- *Fix:* the production web build in CI; actions pinned to commit hashes; one keystore step.

*Moved to V3-M3 while doing V3-M2 (they need screens of their own):* a per-user "signed out everywhere" (R6; the
90-day limit on sessions is done), and showing a push the server always refuses, with export or discard of the stuck
changes (R9).

### 1.3 P3 — quality (V3-M3)

**R12. Refactorings.**
- Split `server/.../Api.kt` (777 lines) into a router and handlers (bands, history, invites, calendar, attachments,
  users) with one ETag-retry helper (the loop is copied 9 times) and one place for storage keys.
- One client helper for errors and cancellation (`runCatching` around suspend calls swallows cancellation in several
  places); every server error code in the shared `ApiErrors`.
- Split `SettingsScreen.kt` (781 lines) per section and `App.kt` (navigation, dialogs); move use cases out of
  `AppGraph`; one history controller shared by the band and event history.
- A shared, lenient `HistoryEntry` decoder instead of the hand-written one in `ApiClient.history`.

**R13. Small fixes.**
- Accessibility: labels for agenda template fields, the guest remove button as a proper button with the name, names
  not read twice in Band members, removal buttons naming what they remove.
- Unused strings (`band`, `event_notes`, `more_details`, `rename`, `sign_in_google`), stale comments and references,
  a README with build, run and test instructions.
- Lists capped in width on tablets; a progress indicator instead of a blank screen while loading; the language
  choice never crashes on an unknown stored value.
- Build: material3 version in `[versions]`, the expect/actual Beta warning flag, comments for unusual deploy flags,
  `InMemoryBlobStore` out of the Lambda jar, the notification icon via `R` instead of a lookup by name.

**R14. Tests for the paths added in iteration 2.** The history byte budget and the dropped oversize changes, undo
resolution with malformed or missing entries, permissions of `Undo` and attachments, the reminder scheduler and the
updater, and the web storage (wasm tests are switched off today).

## 2. Features (V3-M4, landing together)

Chosen on 2026-10-22; details to be settled (grilling) before building.

### 2.1 Share a settlement as text
- **Content: the full overview.** A header (event name and date), then income, refunded costs and the band fund, each
  person's share and payout, then the transfers in two blocks: **Done** (recorded transfers) and **Still to pay** (the
  open suggestions). A fully settled event says "All settled". Example:
  ```
  Club gig · Sat 12 Sep
  Income 1 200 zł · costs refunded 150 zł · band fund 100 zł
  Shares: Jacek 475 zł (+ 150 zł refunds), Bob 475 zł
  Done:
  Carol → band fund: 50 zł
  Still to pay:
  Bob → Jacek: 100 zł
  Kapelusz · kapelusz.jacekgajek.com
  ```
- **Language:** the sender's app language, with money and dates formatted in it. The footer names the app in that
  language (Kapelusz / Pass the Hat) with the address.
- **Where and who:** a **Share** icon in the event's top bar and a **Share settlement** button under the Settlement
  panel; for everyone in the band (viewers too); hidden while the event is a draft.
- **How:** the system share sheet on Android; Web Share where the browser has it, otherwise copied to the clipboard.

### 2.2 Reminder about open debts
- **Who:** only the one who still has to pay ("Still to pay" lines): "You still owe Jacek 100 zł from Club gig
  (Sat 12 Sep)". Money owed to the band fund reminds the payer; money the fund owes reminds its holder (treasurer).
- **When:** first **two weeks after the gig**, then **once a week**, **at most 4 times**; at the time of day set for
  gig reminders; one notification per event (one line per person owed). It stops as soon as the transfer is
  recorded (or the event changes so nothing is owed).
- **Setting:** "Reminders about open debts" in Settings → Notifications and calendar, **on by default**; it uses the
  notification permission of gig reminders.
- **Action:** tapping opens the event at To settle, where the transfer is recorded as usual; no "I've paid" button on
  the notification (no accidental records, partial payments possible).
- Android only, scheduled on the phone like gig reminders (no push service); late delivery up to an hour is fine.

### 2.3 Web app installable on iPhones
- **Installable:** a web app manifest (name per language, the hat icon, honey-gold theme colour) and the iOS
  home-screen tags, so "Add to Home Screen" gives an app icon that opens in its own window.
- **Starts offline:** a service worker keeps the app's files on the device; changes sync once online, like on
  Android. A new deploy is picked up on the next start, and the existing "new version available" notice offers a
  reload.
- **Hint on iPhones:** in Safari on iPhone and iPad only (not when already opened from the home screen): "Add
  Kapelusz to your Home Screen: tap Share, then Add to Home Screen."
- **Both install hints (iPhone and the existing Android app hint) show every time the event list opens;** "Not now"
  hides them only until the next visit (today the Android hint stays hidden after the first dismissal until signing
  out and in). Each disappears for good once installed: the web app opened from the home screen, or the Android app
  installed where the browser can tell (installed related apps).

## 3. Later

### History performance

Measured in CloudWatch on 2026-10-22 (Lambda: 1024 MB, arm64, Java 21, SnapStart):

| | Before (build 17) | After parallel reads (build 20) |
|---|---|---|
| Typical request (median) | 72 ms | 50 ms |
| 90% of requests under | 129 ms | 97 ms |
| History requests | 2.9–4.2 s | 1.4–2.8 s |
| Cold starts | ≈270–600 ms, rare | ≈260–280 ms, rare |

The history log is one S3 file per accepted push, and a history page reads up to 100 of them. Reading them in
parallel (build 20) helped less than expected: Java's built-in HTTP client keeps at most 5 connections for reuse, so
most parallel reads open a new TLS connection, and the handshakes compete for the Lambda's fraction of a CPU.

Options, cheapest first:
1. **Pooled HTTP client for S3** (e.g. the SDK's Apache client) that keeps connections open between requests, and
   apply the page's size budget from the sizes the listing already returns, so a page is fetched at once. Expected:
   about 200–400 ms per history request on a warm Lambda. Cost: one server library (≈1 MB).
2. **History log in DynamoDB** (band state stays in S3): one query per page, about 50–100 ms regardless of size.
   Needs a table in Terraform, a migration of the existing log, and splitting entries above DynamoDB's 400 KB item
   limit (large offline batches with their recorded changes). On-demand pricing; at this scale within the free tier.
3. **More Lambda memory** (2048 MB): more CPU for the handshakes at about twice the cost per millisecond. Treats the
   symptom.

Not recommended: a relational database (e.g. Postgres on RDS): a fixed monthly cost even when idle, and connection
handling from Lambda is awkward.

### Availability

Members still cannot add themselves to an event, but once a lead creates it they can answer "I can attend" or "I
cannot attend". The lead sees the answers in the participants list and accepts (adds the member) or refuses them.
Adding a member who answered "I cannot attend" asks the lead to confirm first.

Other ideas: payment details (account number, BLIK phone) with copy buttons in To settle; a yearly summary per
member with CSV/PDF export (for tax returns); duplicating an event; agreed fee and deposit tracking.

## 4. Milestones and task checklist

### V3-M1 — Hardening P1: crashes, data loss, wrong money
- [x] V3-M1.1 Input limits and validation (R1)
- [x] V3-M1.2 Refunds of members' costs only into open events; "Incurred for" limited to the owner's events (R2)
- [x] V3-M1.3 Navigation and submit guards, dialogs that cannot crash, back stack kept on rotation and language change (R3)
- [x] V3-M1.4 Sync never loses a change (R4)
- [x] V3-M1.5 Deploy safeguards and post-deploy smoke check (R5)

### V3-M2 — Hardening P2
- [x] V3-M2.1 Server: membership first, quotas, sessions, logs, CORS, attachment keys (R6)
- [x] V3-M2.2 Android: one instance, late reminders kept, update notification, threads, notification language, insets (R7)
- [x] V3-M2.3 UI: edits send only changes, keyed rows, number parsing, busy flags, saved form state, Polish texts (R8)
- [x] V3-M2.4 Sync: removed bands, token races, sequence numbers, error classes, web storage, stuck queues (R9)
- [x] V3-M2.5 Band fund rules and archived events (R10)
- [x] V3-M2.6 CI: production web build, pinned actions (R11)

### V3-M3 — Quality
- [x] V3-M3.1 Refactorings (R12)
- [x] V3-M3.2 Accessibility, unused resources, docs, build hygiene (R13)
- [x] V3-M3.3 Tests for iteration-2 paths (R14)
- [x] V3-M3.4 "Signed out everywhere" (R6) and stuck pushes shown with export or discard (R9)

### V3-M4 — New features (one release)
- [ ] V3-M4.1 Share a settlement as text (§2.1)
- [ ] V3-M4.2 Reminder about open debts (§2.2)
- [ ] V3-M4.3 Web app installable on iPhones, install hints on every visit (§2.3)

## 5. Decision log
| Date | Decision |
|---|---|
| 2026-10-22 | Iteration 3 starts with hardening: the full review's P1 and P2 findings become V3-M1 and V3-M2, quality work V3-M3, before the new features (share settlement as text, open-debt reminders, installable web app on iPhones). |
| 2026-10-22 | Features settled (grilling): the shared settlement is the full overview with Done / Still to pay, for everyone, with a footer; open-debt reminders only to the payer, two weeks after the gig then weekly, at most 4, on by default, tap opens To settle; the web app installs and starts offline, with an iPhone hint, and both install hints come back on every visit to the event list until installed. |
| 2026-10-22 | The three features form one milestone, V3-M4, so they ship together. |
