# Kapelusz — Iteration 1

A simple, CRM-style app for settling money between band members after concerts.
It replaces the band's settlement spreadsheet; the cases from it live on as golden tests (§2.6).

This document describes iteration 1 **as built** (milestones M0–M5 and the follow-ups in §7). It started as the refined
plan; the decision log (§8) records where and why the result differs from the plan. Work beyond iteration 1 is planned
in `ITERATION_2.md`.

Targets: **Android** and **Web (Compose/Wasm)**. The web app is fully featured and doubles as the iPhone/desktop client.
UI languages: **Polish and English**, following the device language with a manual override in Settings. Code, identifiers and docs are in English.

---

## 1. Glossary

| Term | Meaning |
|---|---|
| **User** | A Google account signed in to the app. Identified by Google `sub`. |
| **Band** | A group with its own roster, events, costs and band fund. A user may belong to several bands. |
| **Member** | An entry on a band's roster (e.g. "Alice"). Linked to **at most one** user. Within a band, user ↔ member is 1:1. Unlinked members are allowed: a lead enters their data for them. |
| **Guest** | An ad-hoc person who exists inside one event only (e.g. "Helper 2"). Has no login. |
| **Participant** | A member or guest taking part in an event. Has a **share weight**. |
| **Holding** | Money a participant **collected** for an event (hat, door, ticket service…). |
| **Cost** | Money a member spent on behalf of the band. It belongs to the **member**, not to an event. |
| **Refund** | Part (or all) of a cost repaid out of a specific event's income. |
| **Transfer** | Money handed from one party to another (participant ↔ participant, or ↔ band fund). |
| **Band fund** | Shared band money ("Kasa zespołowa"), physically held by the **treasurer**. Optional: switched on by choosing a treasurer. |
| **Treasurer** | A band **setting**: which member holds the band fund (shown in the app as "Band fund held by"). It is **not a role**; it only lets that member act for the band fund in transfers (§3). |

---

## 2. Settlement logic (domain core)

All money is stored as `Long` minor units (grosze). Never use floating point. There is **one currency per band**, chosen when the band is created (default PLN).

### 2.1 Event inputs
- **Participants:** members and/or guests, each with an integer **share weight** in percent (default `100`).
  - `150` = a 1.5× share.
  - `0` = takes part but gets no share, e.g. a helper who only collected money.
- **Holdings:** a list of *(participant, amount, source, note?)*.
  - Source is one of `TICKETS_ONLINE | DOOR | HAT | MERCH | CONTRACT | SPONSORSHIP | OWN_CONTRIBUTION | OTHER`.
  - **Event income = sum of holdings.** There is no separate income table.
- **Band fund amount** (`fundAmount`, may be negative):
  - Positive: taken off the top and handed to the treasurer.
  - Negative: the fund covers part of the event, paid out by the treasurer.
  - Available only while the band fund is on (a treasurer is chosen). The app asks for a direction (to / from the fund) and a positive amount, since phone amount keyboards often have no minus key.
- **Refunds:** a list of *(costId, amount)*. Only costs of **participants of this event** may be refunded here.

### 2.2 Computation
```
refundsTotal = Σ refunds.amount
net          = income − fundAmount − refundsTotal
share(p)     = net × weight(p) / Σ weights          (exact rational)
payout(p)    = floor(share(p)) + leftover(p) + Σ refunds of p's costs in this event
```
- **Rounding:** each share is rounded down. The leftover grosze (`net − Σ floor(share)`) are handed out one at a time, largest fractional remainder first, ties broken by roster order (guests last). This guarantees `Σ payout = income − fundAmount`.
- **Σ weights = 0 with net > 0:** settlement is blocked ("nobody has a share").
- **net < 0 (loss):** settlement is **blocked**. A banner shows "Loss: X zł — fix before settling" with three fixes:
  1. **Defer** some refunds (fully or partly). The deferred part stays pending on the cost.
  2. Add income, e.g. a lead adds an `OWN_CONTRIBUTION` holding.
  3. Use a negative band-fund amount (cover from the fund).

### 2.3 Balances, transfers, status
- **Target** for each party:
  - Participant: `payout(p)`.
  - Band fund (held by the treasurer): `fundAmount`.
- **Current balance** = collected − sent + received, counting only **settled** (recorded) transfers.
- **Suggested transfers:** greedy matching of the largest surplus to the largest deficit until everyone hits their target. This gives ≤ n−1 transfers. They are recomputed live from current balances and never stored.
  - When the treasurer holds the surplus personally, the fund transfer shows as "Alice: keep 100 zł for band fund [Confirm]".
- **Manual transfers:** any party may hand money to any other party (e.g. Dave gives all hat money to Alice so that Alice does the distribution). Suggestions recompute afterwards.
- **Settle** on a suggested transfer records it as a transfer. **Part…** records only part of it (e.g. the cash someone has on them, at most the suggested amount); the remainder stays suggested. **Settle** next to a member records all of that member's pending suggested transfers. **Settle All** records every suggested transfer of the event.
- **Status**, always derived and never set by hand:

  | Status | When |
  |---|---|
  | `DRAFT` | no holdings yet |
  | `LOSS` | net < 0 |
  | `OPEN` | some party's balance ≠ target |
  | `SETTLED` | every balance == target (exact, integer grosze) |

  Archiving is a separate `archived` flag (hidden from the list by default), not a status, so an archived event still shows whether it was settled.

- Editing a settled event (late cost, changed share…) reopens it as `OPEN` and produces compensating suggestions. Recorded transfers are **never rewritten**.

### 2.4 Costs and amortization
- **Cost fields:** member, amount (required), date (default today), optional "incurred for" event, optional category (`TRAVEL | ACCOMMODATION | EQUIPMENT | FOOD | PROMOTION | OTHER`, PROMOTION added in 1.27.0), optional description, up to **5 attachments** (images and/or PDFs).
- **Outstanding** = amount − Σ refunds assigned across all events and the fund. This prevents refunding a cost twice.
- **Paid** = Σ refunds in **settled** events + fund refunds. The owner sees a status based on this: `PENDING` (nothing assigned), `REFUND_PLANNED` (assigned, not paid yet), `PARTLY_REFUNDED`, or `REFUNDED`. If a settled event reopens, its refunds count as planned again.
- Refunds are always 100% of what you choose to refund. There are no refund percentages; to refund less, enter the refundable amount.
- **Defaults** when a lead opens an event's Refunds section:
  - Costs **incurred for this event** are pre-included in full.
  - Outstanding costs of **this event's participants** from earlier, plus costs not tied to any event, are listed as "Pending from earlier", oldest first, with one-tap *Include*.
  - Any included refund can be lowered (partial) or removed (deferred).
- A member's own cost becomes **locked** for that member once any part is refunded. A lead can still edit it, but never below the refunded amount.
- The band fund can refund a pending cost directly via a fund entry linked to the cost (lead+).

### 2.5 Band fund
- **Balance** = Σ settled fund transfers from events + Σ manual fund entries.
- **"Not yet handed over"** = Σ event `fundAmount` − Σ settled fund transfers.
- The balance **may go negative** (the treasurer covered the difference); the UI shows it in red. Nothing blocks it.
- **Manual entries:** description + signed amount (e.g. "Mic −400", "Refund Dave's train −322" linked to the cost). Lead+ only.
- **On/off:** the band fund is off until an admin switches it on in Settings and chooses who holds it. While it is off, the Band fund tab is hidden and events cannot set money aside.
- **Turning it off** (`DisableBandFund`, admin+) is allowed only when the balance is 0 **and every event has handed over its fund amount**. This is checked per event, so one event owing 100 and another expecting 100 do not cancel out. Past transfers do not prevent it. Settled events stay settled; history and entries are kept, and choosing a holder turns the fund on again.

### 2.6 Golden tests
Each case covers a **different behaviour**. Real cases come from the band's earlier settlement spreadsheets (anonymized); the synthetic ones cover edge cases the spreadsheets don't have.

| # | Behaviour under test | Based on | Input | Expected |
|---|---|---|---|---|
| G1 | Even split + refunds of two members' costs; a roster member not taking part | Oakfield Jazz Club 2026-09-13 | DOOR 1000.00 held by Dave; costs Carol 30.00, Dave 322.00 (incurred for this event); participants Alice, Bob, Carol, Dave, Frank @100 (Eve absent) | payouts Alice 129.60, Bob 129.60, Carol 159.60, Dave 451.60, Frank 129.60; 4 suggested transfers from Dave |
| G2 | Guest @0 who collected money; several holders; absent member | Millbrook Harvest Fair | DOOR 2000.00 held 500 each by Alice, Bob, Carol, guest Helper 2; participants Alice, Bob, Carol, Eve @100, Helper 2 @0 | payouts 500.00 ×4, Helper 2 0.00; single suggestion Helper 2 → Eve 500.00 |
| G3 | Multiple income sources with notes; income fully consumed by one member's refund → SETTLED with no transfers | Carried over | CONTRACT 1870.00 + SPONSORSHIP 200.00 ("sponsorship via Grace"), both held by Carol; Carol refund 2070.00; 5 members @100 | net 0; Carol payout 2070.00, others 0.00; status SETTLED immediately |
| G4 | **Amortization:** one cost repaid in parts across events | Carried over + Southgate Park + Grand Hotel gala | Carol cost 4970.00; refunds 2070.00 (G3), 1000.00 (CONTRACT 1000.00), 1900.00 (MERCH 400.00 + CONTRACT 1500.00) | after event 1: PARTLY_REFUNDED, outstanding 2900.00; after 2: 1900.00; after 3: REFUNDED, all three events SETTLED |
| G5 | Whole income to band fund, collected by a non-treasurer | Four Lanterns pub | HAT 710.00 held by Dave ("hat at Four Lanterns"); fundAmount 710.00; treasurer Carol | net 0, payouts 0; suggestion Dave → Band fund (Carol) 710.00 |
| G6 | Treasurer collected the fund money themselves | Elmwood village fete (adapted) | CONTRACT 813.00 held by Carol; fundAmount 813.00; treasurer Carol | suggestion "Carol: keep 813.00 for band fund"; after both G5 and G6 settle, fund balance 1523.00 |
| G7 | Single holder owes several members; a member sitting out; OPEN until settled | 2023-09 Riverside Arts Night | CONTRACT 1200.00 held by Carol; participants Carol, Grace, Dave, Ivan @100 (Heidi absent) | payouts 300.00 each; suggestions Carol → Grace/Dave/Ivan 300.00; OPEN → Settle All → SETTLED |
| G8 | Rounding of leftover grosze | synthetic | net 100.00, 3 participants @100 | 33.34, 33.33, 33.33 (extra grosz to first in roster order) |
| G9 | Unequal weights | synthetic | net 1000.00, A @150, B @100, C @100 | 428.57, 285.72, 285.71 (leftover grosz: B and C tie on remainder, B wins by roster order) |
| G10 | Loss blocks settlement; deferral fixes it | synthetic | income 100.00, refund 150.00 | LOSS, Settle disabled; defer 50.00 → net 0, settleable, cost outstanding 50.00 |
| G11 | Manual hand-over between holders, then recompute | G2 + transfer | in G2, Carol hands 500.00 to Alice | suggestions recomputed; still ends SETTLED with Σ balances = Σ payouts |
| G12 | Late edit reopens a settled event | G1 + edit | after Settle All, add Bob cost 50.00 refunded here | status OPEN; compensating suggestions only; recorded transfers unchanged |

---

## 3. Roles and permissions

Roles form **one cumulative axis**. Every user in a band is linked to a member and has exactly one role.

| Action | Viewer | Member | Lead | Admin | Owner |
|---|---|---|---|---|---|
| See all events, finances, band fund, history | ✔ | ✔ | ✔ | ✔ | ✔ |
| Add/edit/delete **own** costs + attachments (locked once partly refunded; files can still be added, ITERATION_7 §2d) | – | ✔ | ✔ | ✔ | ✔ |
| Record **own** holdings in events they take part in | – | ✔ | ✔ | ✔ | ✔ |
| Record/settle transfers **they send or receive**, "Hand over money" | – | ✔ | ✔ | ✔ | ✔ |
| Create/edit/archive/delete events, participants, guests, shares, refunds, fund amount, fund entries | – | – | ✔ | ✔ | ✔ |
| Edit anyone's costs/holdings, Settle / Settle All on any event | – | – | ✔ | ✔ | ✔ |
| Edit roster, roles, band name, band fund on/off and holder, invites, unlink users | – | – | – | ✔ | ✔ |
| Delete band, transfer ownership | – | – | – | – | ✔ |

- The **treasurer** counts as the band fund's party: a treasurer with the Member role may record and settle transfers to or from the band fund, like their own, and (since ITERATION_7 §2d) keep its plain entries.
- There is exactly **one owner**: the band's creator. The owner is always an admin, so there is always at least one admin. Ownership transfer goes to an admin, and the former owner stays an admin.
- An admin cannot demote the owner. The last-admin rule is satisfied automatically.
- Permissions are enforced **on the server** (shared code) and mirrored in the UI: actions you can't perform are hidden.
- The event list has an **"Only my events"** filter. It is remembered per user and off by default.

---

## 4. Screens and UX

### 4.1 Navigation
Narrow screens (phones) use a bottom nav; wide screens (≥ 600 dp, e.g. the desktop web) use a side rail. Every member
sees the same tabs; actions their role cannot perform are hidden.

1. **Events** (home)
   - A **"Continue: <last edited event>"** card at the top, and a **"+ New event"** button (lead+).
   - Each row shows: name, date, location, participant avatars, status chip, and **your** number ("you get 129,60 zł" / "you hand over 451,60 zł").
   - Filters: "Only my events", show archived. An empty list says whether the filters hide everything or there are no events yet.
2. **My costs**
   - Your costs, filtered **Pending** (anything not fully refunded: pending, planned, partly refunded; the default) / **Refunded** / **All**.
   - A big **"+ Add cost"** button (member+) that opens a fast form: amount first, everything else optional, attachments (§4.5).
   - Leads see a **"Paid by"** dropdown (default: themselves) to add a cost for another member.
   - Leads can switch the list between **Mine** and **All members** (each row then shows who paid). Adding a cost for someone else switches to All members, so the new cost is visible.
3. **Settle**
   - Every pending suggested transfer where you're sender or receiver, across events, each with **Settle**.
   - **"Hand over money"**: pick a party; the amount defaults to your whole balance in that event and can be edited.
   - For leads, a second section lists all `OPEN`/`LOSS` events.
4. **Band fund**, shown only while the band fund is on: balance (red if negative), "not handed over yet", the holder, money from events (handed over or waiting), and other entries (one list by date since ITERATION_7 §2e). Everyone sees it; lead+ adds, edits and removes entries, including refunding a pending cost from the fund. (Since ITERATION_7 §2d the holder keeps the plain entries too; since §2e money in names who paid it, and money out only refunds a cost.)
5. **⚙ Cog** in the top bar: band switcher, New band, Join a band, Band members (roster), History, Settings.
   - **Settings:** language (device / English / Polski); for admins the band name and the band fund switch with its holder; for the owner a red **Delete band** button (§4.4); the account and sign-out.

A sync indicator next to the cog shows offline / syncing / errors; when the server needs a new sign-in it opens a "Sign in again" dialog.

**Website banner:** the web app shows a quiet strip above the tab bar suggesting the Android app (it links to the APK, §6.2). It is hidden on iPhone/iPad (including iPadOS, which reports itself as a Mac), after "Don't show again", and while no APK is published. In the Android app the same strip offers an update when a newer APK is published.

### 4.2 Event screen
- **Header:** name, date, location, notes, status chip.
- **Participants:** avatar chips, **pre-selected from the last event**, tap to toggle. "+ Guest" sits alongside. "Adjust shares" reveals the weights (default 100).
  - Removing a participant who has holdings, transfers or refunds is blocked ("move or delete their entries first").
- **Collected money:** a list of holdings with + Add.
- **Refunds:** included costs, plus "Pending from earlier" (§2.4).
- **Band fund amount** (while the band fund is on): "To band fund" / "From band fund" in the totals, edited with a direction toggle (§2.1).
- **Summary table:** each participant's share, refunds, payout, current balance, delta, and a per-member **Settle** button.
- **Suggested transfers** with **Settle** each, plus **Settle All**. Disabled while `LOSS`.
- **Recorded transfers.**
- **History** of this event.
- **Delete:** only if the event has no recorded transfers; otherwise **Archive**. Deleting removes its refunds, so those costs become pending again.

### 4.3 Zero screen and invites
- A user with no bands sees **Create band** (name + currency → they become the owner, and a member entry for them is created) or **Join band**.
- **Invite** (admin only):
  - Created on an *unlinked* member, with a chosen role (default Member).
  - Produces a **6-character code** from the alphabet `23456789ABCDEFGHJKMNPQRSTUVWXYZ`. It is valid for **72 h** and single use.
  - A new code for the same member revokes the old one. Admins see active codes and can revoke them.
  - The share sheet sends: "Join <Band> in Kapelusz: code ABC123 — https://kapelusz.jacekgajek.com/join/ABC123". **Copy link** next to Share copies just the link.
- **Join link** behaviour:
  - Android with the app installed: an App Link opens the app.
  - Otherwise: the web app opens, signs in with Google, and joins in the browser. Its banner suggests the Android app (§4.1).
  - Typing the code manually also works.
- The confirmation screen reads: "You'll join *Band* as *Alice* (Member)".
- **Brute-force protection:** 10 failed code attempts per user per hour, plus API throttling.
- An admin can **unlink** a user from a member (e.g. lost account), so the member can be invited again.

### 4.4 Admin
- **Roster** (Band members): add, rename, reorder (the order is used for tie-breaking), archive. Hard delete is allowed only if the member never took part in anything.
- **Roles** are picked with radio buttons that explain what each role allows; changing your own role asks for confirmation. **Invites, unlink** (not your own account), **band name, band fund on/off and holder**; **transfer ownership** (owner only).
- **Delete band** (owner only, online only): a red button in Settings. The confirmation lists everything that is deleted for everyone, how many other members lose access, that unsynced changes are lost, and that it cannot be undone; the band name must be typed to enable the red **Delete forever** button. Other members' apps then say the band was deleted and remove it. The server keeps deleted objects for 30 days (bucket versioning), so a mistake can still be undone by hand.
- The band-wide **History** (cog menu) is visible to every member, like the finances it records.

### 4.5 Attachments
- Up to 5 per cost, images and/or PDFs: **Add photo or PDF** (gallery or files) everywhere, **Take photo** on Android; on phones the browser's file chooser offers the camera itself.
- **Images** are compressed on the device: orientation applied, longest edge 1600 px, JPEG quality ~80, EXIF/GPS dropped; at most 3 MB after compression.
- **PDFs** are uploaded as-is, max 10 MB.
- **Offline first:** the attachment is added like any change (`AddAttachment`, synced as an operation); the file waits on the device, marked "uploading", until the server has the attachment. The sync then uploads it through a **presigned PUT URL** (§5.3) and drops the local copy. Files picked while adding a new cost are attached when the cost is saved.
- Tapping an attachment opens it in the browser or system viewer through a short-lived presigned GET URL. A file still waiting for upload cannot be opened yet.
- Anyone who can see the cost can see its attachments. Removing an attachment or deleting its cost deletes the file; bucket versioning keeps it for 30 days.

### 4.6 History (audit log)
- Every accepted operation is appended server-side to the band's log.
- It is shown as human-readable entries ("Alice recorded transfer Dave → Bob 129,60 zł · 2 h ago") per event and band-wide, in pages of 100 pushes, newest first.
- Read-only. No undo in v1.

### 4.7 Web addresses and accessibility
- The web app keeps the screen in the address: `#events`, `#costs`, `#settle`, `#fund`, `#event/<id>`, `#cost?costId=<id>`, `#settings`, `#members`, `#history`, `#new-band`. Back/forward work, and a reload or a link opens the same screen. Unknown addresses open the event list.
- Only one browser tab can use the app at a time (Web Locks); another tab offers to take over.
- Screen readers hear transfers as one phrase ("Bob to Alice"), avatars by name, section titles as headings, and status labels as text (not as buttons).

---

## 5. Architecture

```
┌──────────────┐  ops batch / state   ┌─────────────────────┐   JSON    ┌──────────────────────────┐
│ Android app  │ ───────────────────► │ API Gateway HTTP API │ ────────► │ Lambda art-split-api     │
│ Web (Wasm)   │ ◄─────────────────── │ (throttled)          │           │ JVM21 arm64 SnapStart    │
└──────┬───────┘  session token       └─────────────────────┘           │ :shared reducer+perms    │
       │                                                                 └────────────┬─────────────┘
       │ presigned PUT/GET (attachments)                                              │
       └─────────────────────────────► S3 jacekgajek-art-split-data ◄─────────────────┘
```

### 5.1 Gradle modules (`com.jacekgajek.kapelusz`)
```
shared/      KMP (android, wasmJs, jvm). commonMain:
               model/   Band, Member, Guest, Event, Participant, Holding, Cost, Attachment,
                        Refund, Transfer, FundEntry, Invite (kotlinx.serialization)
               ops/     sealed Operation + reducer: apply(state, op, actor): Result<State>
               domain/  SettlementCalculator, status derivation, transfer suggestions
               auth/    Permissions.check(actor, op): Allowed | Denied(reason)
             commonTest: golden + property tests (Σ payout invariant, rounding, transfers)
composeApp/  KMP Compose Multiplatform library (android, wasmJs executable), commonMain UI,
             PL/EN string resources, navigation, view models, sync engine, local store
androidApp/  Android application shell (MainActivity, manifest, signing) depending on composeApp
server/      JVM Lambda (no web framework): Handler (API Gateway + SnapStart priming), Api (routes), Auth
             (Google tokens), Sessions (Kapelusz session tokens), BlobStore (S3 with conditional writes, presigning)
```

**Libraries:** Compose Multiplatform (Material 3, extended icons, resources), Navigation Compose, kotlinx.serialization, kotlinx.datetime, kotlinx.coroutines, and Ktor client (Android and JS engines). Android also uses Credential Manager + Google ID, activity-compose and ExifInterface. Server: AWS SDK for Java v2 (`s3` only, `UrlConnectionHttpClient`), nimbus-jose-jwt, CRaC. There is no DI framework and no view models: wiring is done by hand (`AppGraph`). Versions are pinned in `gradle/libs.versions.toml` (see §8 for the IDE constraints).

**Web target:** `wasmJs`. It needs WasmGC browsers: Chrome/Edge 119+, Firefox 120+, Safari 18.2+.

### 5.2 Sync (operation-based, offline-first)
- Every user action becomes an `Operation` with `clientId` (per install), a monotonic `seq`, an `opId`, a timestamp and the actor.
- Ops carry **entity ids** (UUIDv7, generated on the client) and **patch semantics** (only the changed fields).
- **Client:** `displayedState = pendingOps.fold(serverState, reducer)`. The UI never waits on the network.
  - Android persists to app-private files; the web persists to IndexedDB.
- **Push:** `POST /bands/{id}/ops` with the pending batch. The server does:
  1. Load `state.json` with its ETag.
  2. For each op: skip it if `seq ≤ lastSeq[clientId]` (idempotency); otherwise check permissions and apply the reducer.
  3. Conditional `PUT` with `If-Match` (retry ≤5 on a race).
  4. Append accepted ops to the log.
  5. Return the new state, version, and a list of rejected ops with reasons.
- **Rejected ops** are dropped on the client, with a snackbar: "Your change to X was rejected: <reason>".
- **Pull:** on app open, after every push, and every 30 s while in the foreground (Android syncs only while visible). `If-None-Match` → `304`. Offline, it retries with backoff (5 s doubling up to 5 min).
- **Attachments:** after a band's push, the sync uploads the files of attachments the server now has (§4.5).
- **Bands** are synced independently, so a problem with one never blocks the others. A band the server says is gone (`band_not_found`, or `band_deleted` after its owner deleted it) is removed from the device, with a message; unsent changes of a band the user was removed from are kept as a backup.
- **Conflicts:** last write wins per field/entity, in server-received order. There is no other conflict resolution.

### 5.3 API
All endpoints require `Authorization: Bearer <token>`: a **Kapelusz session token** (`as1.…`, from `POST /session`) or a **Google ID token**. For Google tokens the server verifies the signature (Google JWKS, cached), `iss`, `exp`, `email_verified`, and that `aud ∈ GOOGLE_CLIENT_IDS`; session tokens are HMAC-signed by the server and expire after 30 days. The user key is Google's `sub` either way. CORS preflights are answered without authentication.

| Method & path | Purpose |
|---|---|
| `POST /session` | with a Google ID token or a current session → `{token, expiresAt}` (Kapelusz session, 30 days) |
| `GET /me` | profile + band list |
| `POST /bands` | create band (caller = owner) |
| `GET /bands/{id}/state` | state + version (ETag) |
| `POST /bands/{id}/ops` | push op batch |
| `DELETE /bands/{id}` | delete the band for everyone (owner only) |
| `GET /bands/{id}/log?month=yyyy-MM` | history |
| `POST /bands/{id}/invites` / `DELETE /bands/{id}/invites/{code}` | create / revoke invite |
| `GET /invites/{code}` / `POST /invites/{code}/accept` | preview / accept invite |
| `POST /bands/{id}/attachments` | `{costId, attachmentId}` → `{putUrl, headers}` for an attachment already on the cost |
| `GET /bands/{id}/attachments/{attachmentId}` | returns a short-lived GET URL |

Band management ops (roles, treasurer, unlink, ownership) go through the same `/ops` endpoint.

### 5.4 S3 layout (`jacekgajek-art-split-data`)
```
users/{googleSub}.json                    profile + bandIds
bands/{bandId}/state.json                 full band state incl. lastSeq per clientId
bands/{bandId}/log/{yyyy-MM}/{millis}-{uuid}.json   one object per accepted push
bands/{bandId}/attachments/{attachmentId}.{jpg|pdf}
bands/{bandId}/invites.json               the band's active invite codes (admins only)
bands/{bandId}/deleted.json               marker left by deleting the band: name, who deleted it, former members
invites/{code}.json                       bandId, memberId, role, expiresAt, createdBy
users/{googleSub}-join-attempts.json      invite-code lookups in the current hour (limit 10)
server/session-key.json                   signing key of session tokens, created on first use
```

### 5.5 Auth on clients
- **Sign-in** gives a Google ID token: on **Android** via Credential Manager + Google ID (`serverClientId` = web client ID), on the **web** via Google Identity Services (the official button).
- The app trades it for a **Kapelusz session** (`POST /session`, 30 days) and sends that with every request. Once a session is 10 days old, the app renews it with the session itself, so Google is asked only at sign-in or after a month unused. Renewing Google's one-hour token on Android can show the account picker, which is why the app does not rely on it.
- When there is no usable session (an older server, offline), the app falls back to the Google token. A silent Google renewal is tried at most once every 15 minutes, and a cached token handed back by Google does not count as renewed.
- On a `401` the client refreshes once and retries; if that fails too, sync waits in "Sign in again" (same account only) instead of retrying.
- Each device's data is stored per user, so another account signed in on the same device never sees it.

### 5.6 Lambda performance
- JVM 21 with **SnapStart** on published versions, called via the `live` alias.
- **Priming** in a CRaC `beforeCheckpoint` hook: build the S3 client, fetch JWKS, run a dummy serialization and a settlement pass.
- Lean dependencies (no Spring/Ktor server/DI); target jar ≤ 15 MB.
- `JAVA_TOOL_OPTIONS=-XX:+TieredCompilation -XX:TieredStopAtLevel=1`, arm64, 1024 MB, no VPC, one function for all routes.
- Expected cold start is ~300–600 ms, and the offline-first UI hides it. GraalVM native image is the fallback if needed.

---

## 6. Infrastructure and delivery

### 6.1 Terraform (`my-aws/terraform/art_split.tf`, already written, **applied manually by Jacek**)
- `jacekgajek-art-split-data`: private, versioned (noncurrent versions expire after 30 d), CORS for presigned GET/PUT from the web origin and `http://localhost:8080`.
- `jacekgajek-art-split-web` + CloudFront (OAC, SPA fallback to `index.html`) + ACM (us-east-1) + Route53 A/AAAA for `kapelusz.jacekgajek.com`.
- Lambda `art-split-api`:
  - Created with a placeholder package. `ignore_changes` covers the code, so CI owns the code.
  - Alias `live`, also ignored, so CI moves it.
  - IAM role scoped to the data bucket; log group with 14 d retention.
  - Env: `DATA_BUCKET`, `GOOGLE_CLIENT_IDS`, `JAVA_TOOL_OPTIONS`.
- API Gateway **HTTP API** → alias `live`. It has CORS for the web origin and dev origin, and throttling (burst 50, rate 20/s).
- GitHub **OIDC provider** + role `art-split-github-deploy`. It trusts only the repository's `main` branch (both the name-based subject `repo:jacekgajek/kapelusz:ref:refs/heads/main`, and the old `band_settlement` name until it is removed, and the id-based one GitHub may send) and may only update/publish the Lambda, move the alias, write the web bucket, and invalidate CloudFront.
- **Outputs:** `art_split_deploy_role_arn`, `art_split_api_url`, `art_split_web_bucket`, `art_split_cloudfront_distribution_id`, `art_split_web_url`.

### 6.2 GitHub Actions
- **`ci.yml`** (PRs and pushes to main): `./gradlew check` (shared tests on JVM, server tests, app tests as Android host tests), plus builds of the Android debug **and release** APK (the release runs R8, which deploy relies on), the development web bundle (no wasm-opt) and the server jar.
- **`deploy.yml`** (runs after CI succeeds on a push to `main`, and deploys exactly the commit CI tested):
  1. AWS credentials through OIDC (`role-to-assume: ${{ vars.AWS_DEPLOY_ROLE_ARN }}`).
  2. **Fingerprints** of the release signing certificate (from the keystore secret) plus the optional debug one, for Android App Links. On failure it prints keytool's error, the keystore's aliases and the decoded keystore size.
  3. `scripts/deploy.sh`, with the keystore and `KAPELUSZ_VERSION_CODE` = the Deploy run number:
     - builds `:server:shadowJar`, `:composeApp:wasmJsBrowserDistribution` (single `wasm-opt -O2` pass) and `:androidApp:assembleRelease` (R8, signed);
     - **API:** `update-function-code` → `wait function-updated` → `publish-version` (SnapStart snapshot) → `update-alias live`. Rollback = move the alias back;
     - **Web:** generates `/.well-known/assetlinks.json`, uploads new `*.wasm` (content-hashed names, immutable one-year cache) first, then everything else with `Cache-Control: no-cache`, then deletes old wasm files, so a page loaded mid-deploy never points at a missing file. `download/` is never deleted by the sync;
     - **APK:** uploads `/download/kapelusz.apk`, then `/download/version.json` (`versionCode`, `versionName`);
     - invalidates CloudFront `/*`.
  4. **GitHub Release** `build-N` with the APK attached, as a record (the repository is private, so bandmates download from the website).
- **`scripts/deploy.sh`** runs the same steps locally with an AWS profile. Without `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `KAPELUSZ_VERSION_CODE` it skips the APK and says so. A local version code must not exceed CI's next run number, or phones would not accept CI's later builds as updates.
- **Versions:** the version code is the deploy run number; the version name is `kapelusz.versionName` in `gradle.properties`, bumped by hand.
- **Android updates:** on each fresh start, release builds read `/download/version.json`. If the published build is newer, the app shows "A new version of Kapelusz is available · Update", which downloads the APK in the browser; the user installs it. Dismissing hides that version only. It is not silent: that would need the Play Store.

### 6.3 Secrets and variables to configure (Jacek)
Set them in Repo → Settings → Secrets and variables → Actions, or with `gh secret set` / `gh variable set`.

| Name | Kind | How to obtain |
|---|---|---|
| `AWS_DEPLOY_ROLE_ARN` | Variable | `terraform output art_split_deploy_role_arn` |
| `AWS_REGION` | Variable | `eu-central-1` |
| `API_URL` | Variable | `terraform output art_split_api_url` |
| `WEB_BUCKET` | Variable | `terraform output art_split_web_bucket` |
| `CLOUDFRONT_DISTRIBUTION_ID` | Variable | `terraform output art_split_cloudfront_distribution_id` |
| `GOOGLE_WEB_CLIENT_ID` | Variable | Google Cloud Console → APIs & Services → Credentials → OAuth client "Web application". JS origins: `https://kapelusz.jacekgajek.com`, `http://localhost:8080` |
| `ANDROID_KEYSTORE_BASE64` | Secret | `keytool -genkeypair -v -keystore artsplit.jks -alias artsplit -keyalg RSA -keysize 4096 -validity 10000`, then set it straight from the file: `gh secret set ANDROID_KEYSTORE_BASE64 < <(base64 -w0 artsplit.jks)` (a pasted value can get truncated; keytool then reports `EOFException`). **Back up the .jks** (it is git-ignored in the repo folder). |
| `ANDROID_KEYSTORE_PASSWORD` | Secret | from keytool |
| `ANDROID_KEY_ALIAS` | Variable | `artsplit` |
| `ANDROID_KEY_PASSWORD` | Secret | from keytool; optional when it equals the keystore password |
| `ANDROID_DEBUG_CERT_SHA256` | Variable (optional) | SHA-256 of the debug keystore, so App Links also open debug builds |

**Google sign-in setup (one-time, free, ~15 min, no billing account needed):**
1. Open https://console.cloud.google.com, sign in with your normal Google account, and accept the terms.
2. Project picker → **New project** → name it `Kapelusz`.
3. **Google Auth Platform → Get started** (older UI: "OAuth consent screen"):
   - App name `Kapelusz`, support email = yours, Audience **External**, contact email = yours.
   - Request only the default scopes (`openid`, `email`, `profile`). These are non-sensitive, so no Google verification is needed.
   - **Audience → Publish app**, so any Google account can sign in. Otherwise every bandmate must be added as a test user.
4. **Clients → Create client → Web application:**
   - Authorized JavaScript origins: `https://kapelusz.jacekgajek.com` and `http://localhost:8080`.
   - No redirect URIs.
   - Copy the client ID: it goes into the `GOOGLE_WEB_CLIENT_ID` variable and into `art_split_google_client_ids`.
5. **Clients → Create client → Android** (do this twice):
   - Package `com.jacekgajek.kapelusz`.
   - First client: the SHA-1 from `keytool -list -v -keystore artsplit.jks` (release builds).
   - Second client: the SHA-1 from `keytool -list -v -keystore ~/.android/debug.keystore -storepass android` (local debug builds).
   - These clients only register the app's identity. Their IDs are not used in code.
6. In `my-aws/terraform/art_split.tf`, set `art_split_google_client_ids = ["<web client id>"]` and re-apply.

The Android app requests ID tokens with the **web client ID** as `serverClientId`, so all tokens (web and Android) have the same `aud` and the server needs only that one ID.

---

## 7. Milestones and task checklist

Workflow:
- One branch + PR per milestone (`m1-domain-core`, …). CI must be green to merge.
- Each checkbox is ticked **in the commit that completes it**.
- Commit messages reference task IDs, e.g. `feat(shared): settlement rounding [M1.3]`.
- Plan changes go into the Decision log (§8).

### M0 — Infrastructure (applied by Jacek)
- [x] M0.1 Write `my-aws/terraform/art_split.tf` (+ `archive` provider), passing `terraform fmt` and `validate`
- [x] M0.2 Jacek: `terraform init -upgrade && terraform plan && terraform apply` in `my-aws/terraform`
- [x] M0.3 Jacek: Google sign-in setup (§6.3: project, consent screen, web + 2 Android clients), put the web client ID into `art_split_google_client_ids`, re-apply
- [x] M0.4 Jacek: generate the keystore and set the GitHub secrets/variables (§6.3)

### M1 — Domain core (`:shared`)
- [x] M1.1 Gradle KMP skeleton (`shared`, `composeApp`, `androidApp`, `server`), version catalog, `ci.yml`
- [x] M1.2 Model + serialization
- [x] M1.3 SettlementCalculator: shares, rounding, refunds, fund amount, loss detection
- [x] M1.4 Balances, suggested transfers, status derivation
- [x] M1.5 Costs: outstanding amount, refund eligibility (participants only), deferral
- [x] M1.6 Operations + reducer (patch semantics, idempotency by clientId/seq)
- [x] M1.7 Permissions matrix
- [x] M1.8 Golden tests (§2.6) + invariant tests (Σ payout, transfers converge to SETTLED)

### M2 — Local-only app (Android + Web)
- [x] M2.1 App shell: navigation, tabs, PL/EN resources, theme
- [x] M2.2 Local store (Android files / web IndexedDB) + pending-op queue
- [x] M2.3 Roster (local) and event list with status, "your number", Continue card
- [x] M2.4 Event screen: participants/guests/shares, holdings, refunds, summary
- [x] M2.5 Transfers: suggestions, Settle, per-member Settle, Settle All, Hand over money
- [x] M2.6 My costs + fast add-cost form (no attachments yet)
- [x] M2.7 Settle tab; archive/delete event rules

### M3 — Backend and sync
- [x] M3.1 `:server` handler, routing, Google token verification
- [x] M3.2 S3 storage with ETag conditional writes, `/state`, `/ops`, op log
- [x] M3.3 SnapStart priming, shadow jar, `deploy.yml` (API + web), `scripts/deploy.sh`
- [x] M3.4 Google sign-in on Android and Web
- [x] M3.5 Sync engine (push/pull/backoff/rejections UI)
- [x] M3.6 Zero screen: create band, `/me`, band switcher

### M4 — Collaboration
- [x] M4.1 Role-aware UI (tab and action visibility), "Only my events" filter
- [x] M4.2 Invites: create/revoke/accept, `/join/{code}` web route, Android App Links + assetlinks.json
- [x] M4.3 Admin: roster, roles, unlink, ownership transfer
- [x] M4.4 History views (event + band)

### M5 — Extras
- [x] M5.1 Band fund: treasurer setting, fund amount, fund transfers, manual entries, cost refunds from fund
- [x] M5.2 Attachments: image compression, PDFs, presigned upload/download, offline queue
- [x] M5.3 Signed release APK published on the website (`/download/kapelusz.apk` + `version.json`) and attached to a GitHub Release, plus in-app update check
- [x] M5.4 Polish: empty states, error states, accessibility pass
- [x] M5.5 Rename the band in Settings (admin+; `RenameBand` operation already exists)
- [x] M5.6 "Copy link" button next to Share on an invite
- [x] M5.7 Subtle banner on the website suggesting the Android app (not shown on iPhone/iPad)

### After M5 (still iteration 1)
- [x] Coarser cost filter (Pending / Refunded / All, default Pending); switch to All members after adding a cost for someone else
- [x] Fix: event fund amount edited with a direction toggle instead of a minus sign
- [x] Fix: Google account picker popping up during syncs on Android; then Kapelusz server sessions (§5.5)
- [x] Fix: a waiting attachment file was discarded on a generic 404 (older server)
- [x] Short, readable web addresses (§4.7)
- [x] Explicit band fund on/off switch (§2.5)
- [x] Delete band (owner, §4.4)

---

## 8. Decision log
| Date | Decision |
|---|---|
| 2026-10-20 | Plan refined from PLAN.md through a Q&A session (band fund, costs-as-member-debt, transfers, roles, ops-based sync, Lambda API, invites, distribution, i18n, history). |
| 2026-10-20 | API exposed via **API Gateway HTTP API** instead of a Lambda Function URL: built-in throttling and a well-known permission model; cost is negligible. |
| 2026-10-20 | No spreadsheet importer; the earlier spreadsheets are used only as the source of the golden tests (§2.6). |
| 2026-10-20 | Income sources extended with HAT, CONTRACT, SPONSORSHIP (seen in DV sheet). Refund percentages stay out despite the 50% block in the DV sheet: members enter the refundable part as the cost amount. |
| 2026-10-20 | Separate `androidApp` module: AGP 9 no longer supports `com.android.application` inside a KMP module, so `composeApp` is a KMP library and `androidApp` is the thin app shell. compileSdk/targetSdk 36. Toolchain: Kotlin 2.4.21, Compose MP 1.11.1 (1.12 needs compileSdk 37, which IntelliJ IDEA 2026.2 does not know yet), AGP 9.1.0 (newest supported by IntelliJ IDEA 2026.2; bump only together with the IDE), Gradle 9.8.2, JDK 21. |
| 2026-10-20 | M1: archived is a flag on the event, not an `EventStatus` (see §2.3). |
| 2026-10-20 | M1: "own cost locked once any part is refunded" refined to "locked once a refund of it sits in an event with recorded transfers, or it was refunded from the band fund". Costs incurred for an event are auto-refunded there on creation, so the literal rule would lock every cost immediately. |
| 2026-10-20 | M1: Settle / per-member Settle / Settle All are one op, `SettleSuggestions(eventId, partyId?)`, whose transfers are computed against the state it is applied to, so a stale client never records outdated amounts. Transfer ids are `<opId>-<n>`. |
| 2026-10-20 | M1: when the band fund and the treasurer both take part in an event's suggestions, they are paired first ("keep for band fund"); the rest is greedy largest-surplus to largest-deficit. |
| 2026-10-20 | M1: a member may record holdings only in events they take part in; leads may record them for any participant. Guests can be promoted to the roster (admin) with all their entries in that event re-pointed. |
| 2026-10-21 | PR #1 review: the band fund balance may go negative (shown in red, never blocked). A treasurer with the Member role acts for the band fund when recording/settling transfers. |
| 2026-10-21 | M2: `composeApp` commonTest runs as Android host (JVM) tests; the wasm browser test task is disabled (needs a headless browser, and the code under test is platform-independent). Navigation uses type-safe routes; the web build binds them to browser history. |
| 2026-10-21 | M2: until sign-in (M3) the device user is a fixed local identity (`local-user`) that owns the locally created band; all operations stay in the pending queue. The web build stores state in IndexedDB, Android in app-private files. The band fund tab and fund amount UI wait for M5.1. |
| 2026-10-21 | A cost shows as refunded only once the event refunding it is settled (or it was refunded from the band fund); until then it is "Refund planned". Assigning still counts immediately, so a cost cannot be refunded twice. |
| 2026-10-21 | Every rejection carries a stable `RejectionReason`; apps show its translated text (PL/EN), the English `message` stays for logs and server responses. A test fails if a reason has no text in a UI language. |
| 2026-10-21 | Unreadable saved band data never crashes the app or gets discarded silently: it is copied to `band-backup-<timestamp>` and the user can start over. No export UI; the backup is for manual recovery. Unreadable settings fall back to defaults. |
| 2026-10-21 | M3: history is one S3 object per accepted push (`log/{yyyy-MM}/{millis}-{uuid}.json`) instead of a monthly `.jsonl`: S3 has no append, and separate objects need no read-modify-write. Sequence numbers are tracked per user and device (`{sub}/{clientId}`). Non-members get `404` for a band, the same as a missing band. Band ids come from the client so offline-created bands keep their ids. |
| 2026-10-21 | M3 client: each band is stored as `band-{id}` with a `band-index` of the user's bands; the M2 band (placeholder user) is adopted by the first signed-in user and created on the server with its original ids by the first sync. Android uses Ktor's Android engine (OkHttp 5 needs compileSdk 37). Joining a band waits for invites (M4); the first screen shows it as coming soon. |
| 2026-10-21 | Build time: CI builds the development web bundle (no wasm-opt). Production uses a single `wasm-opt -O2` pass instead of Kotlin's default 5×`-O3`/`--gufa`/`-Oz`: 10 s instead of 43 s locally, +15% compressed app wasm (~180 KB brotli; the 8 MB Skia wasm is unaffected). |
| 2026-10-21 | M4: invite codes are stored server-side (`invites/{code}.json`, `bands/{id}/invites.json`), never in the band state that every member can read. Wrong codes count per user (10/hour) for both preview and accept. The web app sets `<base href="/">` so deep links like `/join/CODE` load the app. `assetlinks.json` is generated at deploy from the release keystore plus the optional `ANDROID_DEBUG_CERT_SHA256` variable. |
| 2026-10-21 | M4 review: band-wide history is visible to every member (the audit trail of shared money; all underlying data is visible anyway). Invites are cancelled whenever their member's account link changes and only stand while their issuer is an active admin. Each user has 10 invite-code lookups per hour (a join uses two). History is served in pages of 100 pushes, newest first. |
| 2026-10-21 | The APK is published on the website (`/download/art-split.apk`, `/download/version.json`) because the repository is private: bandmates could not download from its GitHub Releases. The release is still attached to a GitHub Release as a record. |
| 2026-10-21 | M5.1: the band fund is a fourth tab, shown only once someone holds the fund (balance, money from events and whether it has been handed over, other entries). The holder is chosen in Settings and can be changed but not cleared. A cost refund from the fund is linked to its cost only when the entry is created. |
| 2026-10-21 | M5.7: the website shows a quiet strip above the tab bar suggesting the Android app. It is hidden on iPhone/iPad (including iPadOS, which reports itself as a Mac with touch), after "Don't show again" (localStorage), and while `/download/version.json` is missing, so it never links to an unpublished APK. |
| 2026-10-21 | M5.3: every deploy builds the release APK (R8-shrunk, about 2.6 MB), signed with the keystore secrets. Its version code is the deploy run number; the version name is `artsplit.versionName` in gradle.properties, bumped by hand. The deploy uploads the APK before `version.json`, and the web sync never deletes `download/`. Each deploy also creates a GitHub Release `build-N`. The app checks `version.json` once per launch and offers an update (not in debug builds); dismissing it hides that version only. CI builds the unsigned release too, so R8 problems fail before merge. |
| 2026-10-21 | M5.2: the client chooses attachment ids, so attaching works offline: `AddAttachment` syncs like any change, and the file waits on the device (base64, per user) until the server has the attachment, then goes to a 15-minute presigned PUT. That URL is signed for the exact size and content type and only creates the object (`If-None-Match: *`), so a file can never be replaced. Any non-viewer member may upload a listed attachment, so an upload still finishes if a refund locks the cost meanwhile. Limits: photos 3 MB after compression, PDFs 10 MB. Removing an attachment or deleting its cost deletes the file. Files open through a presigned GET; on the web the tab opens at click time so popup blockers allow it. A file still waiting for upload cannot be opened yet. |
| 2026-10-21 | Server sessions: the apps trade the Google ID token for an Art Split session token at sign-in (`POST /session`, HMAC-SHA256, 30 days) and renew it with itself once 10 days old, so Google is asked only when signing in or after a month unused. Renewing Google's one-hour token on Android can show the account picker, which made it pop up during syncs. The signing key is created on first use in the data bucket (`server/session-key.json`), so no infrastructure change is needed. The server still accepts Google tokens directly, and the apps fall back to them while `/session` is unavailable. Signing out drops the session on the device; tokens are not revoked server-side. |
| 2026-10-21 | The band fund has an explicit on/off switch next to its holder (admins). It can be turned off (`DisableBandFund`) only when it is empty and every event has handed over its fund amount; that is checked per event, so amounts owed by different events do not cancel out. Past transfers do not prevent it. A settled event stays settled after the fund is off: "choose a holder" only applies while fund money is still to move. Choosing a holder turns it on again, with its history intact. |
| 2026-10-21 | Deleting a band: the owner only, online only, from a red button in Settings. The confirmation lists what is lost and how many members lose access, and requires typing the band name. The server deletes the state first (so concurrent pushes fail), then the members' band lists, invites, history and attachment files. A marker `bands/{id}/deleted.json` lets former members be told "was deleted" (others get the usual not-found). Bucket versioning keeps everything for 30 days, so a mistake can still be undone by hand. Not an operation: it cannot be queued offline. |

| 2026-10-21 | Version 1 finalized: `REFINED_PLAN.md` became `VERSION_1.md` and describes the app as built. Further work goes to `VERSION_2.md`. |

## 9. Out of scope (v1)
Multi-currency, push notifications, real-time sync, undo, Play Store (and with it silent auto-updates), iOS native app, spreadsheet import, refund percentages, PDF/CSV export, server-side revocation of sessions.
