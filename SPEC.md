# Specification

What Kapelusz (Pass the Hat) does, as it is today: one place instead of eight iteration plans. Consolidated from
`ITERATION_1` to `ITERATION_8` (now under [docs/history/](docs/history/)) on 2026-10-31; a citation such as `I5 §7.3`
or `I2 DL 2026-10-21` points at the iteration and section, or its decision log, where the behaviour was decided and the
reasons are; other codes are the item labels inside an iteration (`R8`: a review item of I3; `V2-M3`: a milestone of
I2; `A9a`, `B2`, `C2`, `E1`: the lettered work items of I5). The code's comments cite the same codes (`I5 §7`,
`I3 R12`): search this file for the citation to find the behaviour it stands for, or open the iteration under
[docs/history/](docs/history/). A **Superseded** note lists what an older iteration said that no longer holds.

How it is built is in [ARCHITECTURE.md](ARCHITECTURE.md); why, in [DECISIONS.md](DECISIONS.md). When the behaviour
changes, change it here in the same pull request.

## 1. Glossary (current meanings)

| Term | Meaning | Source |
|---|---|---|
| User | A Google account (`sub`) signed in to the app; local dev uses `dev-<name>` | I1 §1, LD |
| Band | A roster, events, costs and a band fund with one currency (chosen at creation, default PLN). A user may belong to ≤ 20 bands | I1 §1, §2; I3 R6 |
| Member | Roster entry, linked to ≤ 1 user (1:1 within a band). Unlinked members allowed; a leader enters data for them. Names unique (case-insensitive, trimmed, archived included) | I1 §1; I5 §12 |
| Former member | An archived member (shown under "Former members") | I1 §4.4; I8 §2 |
| Guest | Ad-hoc participant living in one event only; no login; never becomes a member | I1 §1; I6 §5 |
| Participant | Member or guest in an event with a share: weight ("shares"), fixed amount, or percent of profit | I1 §1; I3 §2.4; I7 §6 |
| Received money (formerly "holding"/"collected") | Money a participant received for an event; income = Σ received | I1 §2.1; I5 §3.5 |
| Planned payment / awaited fee | Money the organiser still owes; counts in income, "Received…" turns it into received money | I4 §2.2; I5 §3.5 |
| Cost | Money a member spent for the band; belongs to the member, optionally "incurred for" an event | I1 §1 |
| Refund | Part or all of a cost paid out of one event's income or from the band fund | I1 §1 |
| Transfer | Money handed from one party to another (participants ↔ participants ↔ band fund), always between real people | I1 §1; I5 §7.3 |
| Band fund | Shared band money held by the **treasurer** (a band setting, not a role); optional | I1 §1 |
| Leader | The `LEAD` role; Admin was merged into it on 2026-10-25 | I5 §11 |
| Artist | A role that sees only their own events, costs and money | I4 §1.1 |
| Pair / settle together | Two members (artists allowed) settling as one party | I5 §7; I6 §11 |

## 2. Identity, sign-in, sessions

- Sign-in with Google only: Android via Credential Manager (`serverClientId` = web client id), web via Google Identity
  Services. Both yield the same `aud`, so the server needs one client id (I1 §5.5, §6.3).
- The app trades the Google ID token for a Kapelusz session (`POST /session`, HMAC, 30 days), renews it with itself once
  ≥ 10 days old; absolute session lifetime 90 days (I1 §5.5, I1 DL "Server sessions"; I3 R6). Falls back to the Google
  token when no session; silent Google renewal at most once per 15 min; a cached token does not count as renewed (I1 §5.5).
- On 401: refresh once, retry; otherwise "Sign in again" (same account only); a refused token is discarded only if it is
  still the current one (I1 §5.5; I3 R9). Background runs never publish NEEDS_SIGN_IN (I5 A6).
- "Sign out on all devices" (`POST /sessions/revoke`): per-user revocation time; invalidates sessions and Google tokens
  from before it; also rotates the calendar link and forgets FCM devices (I3 R6, V3-M3.4; I5 A10b; I7 §2.4).
- Sign-out drops the session on the device, takes `refreshLock`, runs in the app scope, deletes the FCM token (I5 A5; I7 §2.3).
- Device data is kept per user; another account on the same device never sees it (I1 §5.5; LD "Several users").
- Google profile photo: taken from the sign-in token at interactive sign-in and when creating/joining a band; for already
  signed-in users the app posts the kept (expired, Google-signed) token to `POST /me/picture`; session renewals bring no
  photo. Unlink/archive removes the photo from that band; not a history entry (I2 §7.4, I2 DL 2026-10-22).
- Leave band is the app's account deletion for Google Play; after the last band the server keeps only the account id and
  the sign-out-everywhere time (I6 §11a; I7 §1.4 Q3).

## 3. Bands

- **Create**: name + currency → creator is Owner, a member entry is created for them. Band ids come from the client, so
  offline-created bands keep their ids and are created on the server by the first sync (I1 §4.3, I1 DL M3).
- **Rename**: leaders (I1 §3; `RenameBand`).
- **Zero screen**: no bands → Create band / Join band (I1 §4.3).
- **Band switcher** in the cog menu; each band syncs independently (I1 §4.1, §5.2). Switching bands clears saved tab
  back stacks; a route whose id isn't in the current band redirects to the event list (I5 C2).
- **Hints on the events list** (owner and leaders): "no members yet" (not hideable) and "no one else uses the app yet"
  (hideable per band per device); both go when resolved (I5 §11).
- **Delete band** (owner only, online, type the band name): asks the server, which records `ScheduleBandDeletion` with
  `deleteAt` a week later (op refused from apps). Everyone sees a notice on the events list and in Settings; any leader
  may **Keep the band** (`CancelBandDeletion`). Neither is undoable. Due deletions run when the band is next loaded or by
  an hourly sweep piggybacked on any request (`server/scheduled-deletions.json`). A band never uploaded is deleted at
  once. The server deletes state first (so concurrent pushes fail), then members' band lists, invites, history, files,
  event links; leaves `deleted.json` (name, who asked, former members' ids) so former members are told "was deleted"
  (others get not-found). Bucket versioning keeps everything 30 days (I1 §4.4, I1 DL "Deleting a band"; I7 §9; DR).
- **Leave band** (any role but owner): from Settings › Account. Unsent changes, held (Undo) changes and files are pushed
  first (failure stops it with a message); then `POST /bands/{id}/leave`; band removed from the device. Server unlinks
  the member (role back to Member; photo, BLIK, bank account dropped; pair ends; invites cancelled), records "X left the
  band", removes the band from the user; after the last band drops the account leftovers. "band not found" = an earlier
  attempt succeeded. Owner gets `owner_cannot_leave` with a dialog offering Band members / Delete band (I6 §11a).
- **Secret menu** (leaders, tap the version in Settings): "Copy raw JSON data" = `bands/<id>/state.json` byte for byte
  via `GET /bands/{id}/raw`; offline shows an error (I2 §7.1; I5 §11).
- **Privacy policy** entry in Settings (`/privacy-policy`, `/pl`) (I6 §11a).

## 4. Roles and permissions

Roles form one axis `VIEWER < ARTIST < MEMBER < LEAD (shown "Leader") < OWNER`, but visibility is not monotone:
Viewer sees everything and changes nothing; Artist sees less but acts on their own things (I4 §1.1; I5 §11).

Consolidated matrix (✔ = allowed; "own" = only entities the actor owns/is party to):

| Action | Viewer | Artist | Member | Leader | Owner |
|---|---|---|---|---|---|
| See all events, money, fund, history, summary, roster | ✔ | – (own events, own costs, own numbers only) | ✔ | ✔ | ✔ |
| Be added to an event (play) | – (refused, not offered) | ✔ | ✔ | ✔ | ✔ |
| Add/edit/delete own costs + files | – | ✔ | ✔ | ✔ | ✔ |
| Add/rename files on own cost even when locked | – | ✔ | ✔ | ✔ | ✔ |
| Record own received money in events they play | – | – (nobody may record money on an artist) | ✔ | ✔ | ✔ |
| Record/settle own transfers (Paid / Received / Part / Hand over), remove a transfer they recorded | – | ✔ (capped at what they were overpaid) | ✔ | ✔ | ✔ |
| Mark an awaited fee received when paid to oneself | – | – | ✔ | ✔ | ✔ |
| Settle together (invite/answer/end, own pair only) | – | ✔ | ✔ | ✔ | ✔ |
| Set own BLIK number / bank account | – | ✔ | ✔ | ✔ | ✔ |
| Share info / Share settlement / Copy link / Copy amount, calendar subscription, Leave band | ✔ | ✔ | ✔ | ✔ | – (cannot leave) |
| Create/edit/archive/restore/delete events, line-up, guests, shares, refunds ("Refund here"), fund amount/percent, awaited fees, event files, Settle all / any transfer, costs of anyone, refunds from the fund, fund entries | – | – | – | ✔ | ✔ |
| Roster (add, rename, archive, delete member), roles, invites, unlink, band name, fund on/off + holder, fund default, fund payment details, agenda template, secret menu, place lookups, Keep the band | – | – | – | ✔ | ✔ |
| Transfer ownership (to a leader with an account), Delete band | – | – | – | – | ✔ |

Treasurer (the fund holder, any role ≥ Member, never Artist/Viewer): acts as the band fund's party in transfers; adds,
edits, removes plain "paid in" fund entries; sets the fund's BLIK/bank details; marks an awaited fee paid to the fund
(I1 §3; I4 §1.1; I5 B2, C6; I7 §3.3, §7).

Rules:
- Exactly one owner, always a leader; ownership goes to a non-archived leader with an app account; the former owner
  becomes a leader (I1 §3; I5 B3a; I5 §11).
- Changing one's own role asks for confirmation; nobody can demote the owner; leaders manage everyone but the owner
  (I1 §4.4; I5 §11).
- Making someone a Viewer is refused while they play in a non-archived event or hold the fund; `SetTreasurer` refuses
  viewers and artists (I5 B2, C6).
- Making someone an Artist (role change or invite acceptance) is refused while they hold received money in an unsettled
  event or hold the fund; settled events don't matter. An artist invite for such a person is refused at creation; at
  acceptance the invitee is told to settle first and the invite stays (I4 §1.1).
- Permissions are enforced on the server through shared code and mirrored in the UI (hidden actions) (I1 §3).
- Every rejection has a stable `RejectionReason` with PL/EN text; a test fails if a reason lacks a translation (I1 DL
  2026-10-21).
- A missing entity yields NOT_FOUND, not "requires role" (I5 B3b — listed "not now"; verify before relying on it).

**Superseded**: the Admin role (I1 §3; merged into Lead, I5 §11); Artists cannot record transfers (I4 §1.1 → I7 §3.2);
Artists can't settle together (I5 §7.2 → I6 §11); per-member Settle button on the event summary (I1 §4.2 → removed
I2 §7.4).

## 5. Roster (Band members screen)

- Add, rename, archive ("former members", collapsed), restore ("Welcome back" when a former member's name is typed in
  Add member or Add guest; from Add guest also adds them to the event with their usual share) (I1 §4.4; I6 §5).
- **Delete member** (`DeleteMember`, leaders): only one nothing refers to — no costs, never in an event, never named as
  who recorded a transfer/received money/added a participant, not fund holder, not in a pair or invitation, not the
  owner, not yourself, has not paid into the fund. Rule lives in shared `MemberDeletion`. A linked member is deleted
  in one step (Unlink, not undoable + delete). Undo (snackbar/history) brings them back without the account; refused
  if the name was taken meanwhile. An unlink is treated by the server as leaving (I7 §3.1, §5.1).
- Names unique ignoring case and surrounding spaces, archived included; server refuses too; pre-existing duplicates
  stay (I5 §12).
- **No manual order**: alphabetical everywhere (case-insensitive, Polish letters in place: Ł after L, Ś after S); the
  members screen is grouped by role — Leaders (owner's row says Owner), Members, Artists, Viewers, Not in the app,
  Former members — alphabetical within; empty groups hidden; a search field above 12 current members (former members
  searched too) (I8 §2).
- Member dialog (tap a row): leaders see role (radio buttons with descriptions, picker order Artist, Member, Leader,
  then "Read-only: Viewer"), invite, unlink, default share, settle together, Delete member; non-leaders see only
  settling together; artists see names and avatars only (I4 §1.1; I5 §7.4; I6 §11; I7 §3.1).
- Crossed eye instead of avatar for members without an app account; tapping it (leaders) offers to invite. Guests show a
  handshake, no tap (I5 §12; I6 §5).
- **Default share per member** (`SetMemberShare`, leaders): shares (weight), fixed amount or percent of profit; the
  starting share when added to an event or copied by "From an event…"; new member = 1 share. Changing it offers "Use it
  in N events too?" for unsettled, unarchived events where the member still has the old default; applied in the same
  step (one undo); archived members have none (I4 §2.3; I7 §6).
- **BLIK number** (`SetBlikPhone`, own only, artist+): 7–15 digits, optional `+` (`00` → `+`), separators dropped; Polish
  shown `600 123 456`, copied without +48. Not in history, not undoable; removed on unlink, kept on archive (I5 §8).
- **Bank account** (`SetBankAccount`, own only, artist+): IBAN or Polish 26-digit NRB, mod-97 checked; Polish stored and
  copied as 26 digits, shown in fours; same visibility/history rules as BLIK (I7 §7).
- Unlinking a user removes photo, BLIK, bank account, ends a pair, cancels invites; so the member can be invited again
  (I1 §4.3; I5 §8; I6 §11a).

**Superseded**: roster reorder buttons and `MoveMember` (I1 §4.4 → I8 §2); hard delete only "never took part"
(I1 §4.4 → I7 §3.1); `PromoteGuest` (I1 DL M1, I5 §12 → I6 §5).

## 6. Invites and joining

- Leaders create an invite on an unlinked member with a role (default Member): 6-char code from
  `23456789ABCDEFGHJKMNPQRSTUVWXYZ`, 72 h, single use; a new code for the member revokes the old; leaders see and revoke
  active codes; codes are stored server-side (`invites/{code}.json`, `bands/{id}/invites.json`), never in band state
  (I1 §4.3, I1 DL M4).
- Invites are cancelled when the member's account link changes and only stand while their issuer is an active leader;
  revoke uppercases the code (I1 DL M4 review; I5 A10d).
- Share text: "Join <Band> in Kapelusz: code ABC123 — https://kapelusz.jacekgajek.com/join/ABC123"; Copy link; the
  invite card shows a **QR code** of the link (black on white, qrcode-kotlin + Canvas) (I1 §4.3; I6 §8).
- Join: Android App Link opens the app; otherwise the web app signs in and joins; typing the code works; Android has
  **Scan QR code** via Play services' code scanner (no camera permission; accepts a join link or bare code) (I1 §4.3;
  I6 §10).
- Confirmation "You'll join Band as Alice (Member)"; 10 code lookups per user per hour (a join uses two) plus API
  throttling (I1 §4.3, I1 DL M4 review).
- Invite links get a generic link card (a band name would confirm guessed codes) (I6 §2).
- The web app sets `<base href="/">` so `/join/CODE` and `/e/ID` deep links load (I1 DL M4).

## 7. Guests

- Live in one event; a handshake icon; never promoted. A guest named like a current member triggers "Add them as a
  participant instead?" (still allowed as guest); a former member's name offers Welcome back (I5 §12; I6 §5).
- Listed under "Guests" after members, alphabetical among themselves; share edited like a member's; removal via row
  with Undo snackbar, blocked while they hold money; history names a removed guest (I5 §2.3, §4.5, PR #42; I8 §2).
- Guest ids must not collide with member ids; `band-fund` reserved (I3 R1).

## 8. Events

### 8.1 Fields and creation
- Name, date, location (free text / picked place / coordinates), details (multi-line, selectable, collapsed after ~6
  lines, full-screen editor, leaders), agenda times, files (≤ 10), `archived` flag, `fundAmount` or `fundPercent`,
  participants, received money, refunds, transfers, `settledTogether`, who added each participant (I1 §4.2; I2 §4;
  I4 §2.1; I7 §5.2).
- **New events start empty** (no participants). While empty, leaders get "From an event…" (any band size) and "Add
  participants"; the band's `fundDefault` is applied in `CreateEvent` (I8 §1.2a; I7 §5.2).
- Event ids are client-generated UUIDs, unique across bands; `[A-Za-z0-9_-]{1,64}` (I1 §5.2; I3 R1; I5 §10).

### 8.2 Agenda times and details
- Band template (leaders, Settings → Event agenda): default Arrival, Sound check, Concert start; entries marked
  `fromTemplate`; "Reset to defaults" stores "the defaults" so later built-ins apply (I2 §4.1, §7, I2 DL V2-M3).
- A new event copies the template as of that moment; per-event one-off times; unset times shown as chips in create and
  edit dialogs; times before 05:00 count as next day "(+1)" (I2 §4.1, §4.3).
- Notes were replaced by Details (existing notes became details without migration) (I2 §4.2).

### 8.3 Location and map
- Location is the place itself: picked suggestion → "Name, full address" (Place Details in the same session); pasted or
  shared Maps link → resolved by the server (`POST /bands/{id}/places/resolve`, leaders): named place → name + geocoded
  address; dropped pin → `52.229722, 21.012194`; `?q=` → that text; unreadable → the shared name or "Couldn't read the
  link". `Event.mapUrl` removed (unknown fields ignored on read) (I5 §9.1).
- Suggestions: ≥ 3 chars, ~500 ms pause, ≤ 5 results via `GET /bands/{id}/places?q=&lang=` (leaders), "Powered by
  Google"; no key → 503 `places_unavailable`, field stays plain text; one-line wrapping field with clear button; cursor
  goes to the end after a lookup (I5 §9; I6 §3).
- Android-only **pin button**: full-screen Google map (Maps SDK key from `GOOGLE_MAPS_ANDROID_KEY`, no button without
  it), satellite layer, no "my location"; "Use this place" writes coordinates (optionally prefixed by a name) (I6 §3).
- **Navigate** (everyone, when there is a location): Google Maps directions to the text/coordinates (I2 §4.4; I5 §9.1).

**Superseded**: separate Maps link + "Select on map" (I2 §4.4 → I3 DL 2026-10-22, I5 §9.1); "Share from Google Maps to
Kapelusz" text share (I2 §4.4 → removed I6 §1).

### 8.4 Line-up
- Members who can take part = current, non-viewer members. Up to 12 not taking part: tappable chips; above:
  "Add participants (n)" opens the **picker** (full screen on phones, ~560 dp dialog elsewhere): whole line-up ticked,
  guests under "Guests", locked ticks for anyone with money in the event ("has money in this event"), shares shown
  read-only, grouped by role then Guests, alphabetical, search (case/diacritics-insensitive), Tick/Untick all shown,
  "From an event…" (replaces ticks with that event's line-up incl. guests copied as new guests, keeping locked and
  existing ones; newcomers take the share they had there), Save "42 of 58", "Discard changes?" on Back; non-leaders
  above the threshold get a read-only "16 not taking part" (I8 §1.1–1.2).
- Former members or viewers still in an event are listed in their own groups (I8 §1.2).
- A sync meanwhile is kept: the picker applies ticks on top of the live event (I8 §1.2).
- `ChangeLineup(eventId, add, remove, guests)` (leaders): all-or-nothing with the same checks as the single ops; one
  history entry "+5, −2", one Undo. Single ops (`AddParticipant`, `AddGuest`, `RemoveParticipant`) stay for chips/rows
  (I8 §1.3).
- Removing a participant with received money, transfers or refunds is blocked ("remove their money and transfers
  first") (I1 §4.2; I2 §7).
- Actions row chips (From an event…, Add participants, Add guest) look different from person chips (I8 §1.2a).
- Participants section: rows (avatar, name, share when not 1 share, edit, remove with Undo); title has the count above
  12; all rows always shown (I5 §4.5; I8 §1.4).
- Dropdowns of people get a search field above 12 options (I8 §1.5).

**Superseded**: participants pre-selected from the last event (I1 §4.2 → I8 §1.2a); "Adjust shares" toggle (I1 §4.2 →
rows I5 §4.5); collapsed participant list with Show all (tried and dropped, I8 §1.4).

### 8.5 Event screen layout
- Three swipeable Material tab pages, fixed at top, each keeping scroll: **Gig** (date · place + Navigate, times,
  details, files, line-up, Share info), **Money** (status row, totals, received money, refunds incl. Waiting), **Settlement**
  (status, problems, who gets what, To settle, recorded transfers (folded with count), Share settlement, history).
  Artists: Gig and Money (payout, own costs, own To settle). Last page used is remembered per event on the device; a new
  event opens on Gig (I4 §2.4; I5 §3.2, §4.4, §4.6; I7 §3.2).
- "When and where" edited in place via its own button; top-bar edit button gone, ⋮ menu stays (Archive/Restore/Delete,
  Copy link) (I5 §4.9, §10).
- Dialogs close only on success; the details editor keeps text on refusal and has a 2000-char cap/counter; dialog state
  is hoisted and survives rotation (`configChanges` in the manifest) (I5 C3, C4, §1.7).

### 8.6 Archive, restore, delete
- Archive needs a settled event or a money-less draft (no refunds, no fund amount); OPEN/LOSS/AWAITING refused
  (`EVENT_UNSETTLED`, server too), menu item greyed "Not settled yet". Archived events are read-only except Restore and
  Delete (leaders) and are hidden from the list by default (I1 §2.3; I3 R10, I3 DL 2026-10-22).
- Delete: removes its refunds (costs become waiting again) and its files (cannot be undone); the dialog says so. Deleting
  an event also removes its `events/{id}.json` pointer (I1 §4.2; I4 §2.1; I6 §2).

**Superseded**: delete only when no recorded transfers else archive (I1 §4.2); bulk archive (I2 §3.3, removed V2-M7).

### 8.7 Event list
- Rows: name, date (+ first agenda time), location, overlapping avatars with "+N" (true total), status chip, "your
  number" ("you get" / "you hand over"; artist wording "To hand back"; a kept-by-partner part counts as received)
  (I1 §4.1; I5 §3.3, C7; I6 §11).
- Filters: "Only my events" (per user, default off), "Show archived"; empty state explains (I1 §3, §4.1).
- Cards on top: band deletion notice, settle-together invitation, band hints (leaders), **Continue** (most recent
  unsettled: not archived and OPEN/LOSS/AWAITING, or a past-dated DRAFT; latest date, then last edited; same for
  everyone; hidden when nothing), **Upcoming** (nearest non-archived event dated today or later you take part in, else
  the band's; never the same event as Continue) (I2 §3.1, §3.2; I4 §2.2; I5 §7.4, §11; I7 §9).
- **Search**: icon left of the chips; searches name and Location only, every word must match, case- and
  diacritics-insensitive (own folding table), ignores the hidden filters, artists only their events, hides the cards;
  summaries built once in the background when the search opens; query survives opening an event and tab changes, not
  restarts (I6 §6).
- Event summaries for the normal list are memoised per band/me/filters (I6 §6).

### 8.8 Sharing and links
- **Share settlement** (Money/Settlement page button; everyone; hidden for drafts): header, income, refunded costs, band
  fund, shares/payouts (with "fixed"/"(50% of profit)"), Done, Still to pay (pairs named), "All settled", event link as
  footer; one item per line; sender's language (I3 §2.1; I5 §3.4, §10; I7 §6).
- **Share info** (end of Gig page; everyone, drafts too): name; date, place, map; plan; details; line-up; event link
  (I5 §3.4).
- System share sheet on Android; Web Share or clipboard on the web; a share that fails for another reason than
  AbortError falls back to the clipboard (I3 §2.1; I5 D7).
- **Event link** `https://kapelusz.jacekgajek.com/e/<eventId>`: Copy link in ⋮ for everyone; in both share texts and the
  calendar description. Opening: Android App Link or the web app; in one of the user's bands → switch and open; signed
  out → after sign-in; not on device → "Opening the event…" and refresh all bands; no access/deleted → "You don't have
  access… Ask your band leader"; the address resets so reload doesn't reopen (I5 §10).
- **Link cards** (`og:` tags): CloudFront routes `/e/*` to the API which returns `index.html` with the event's name and
  date (`dd.MM.yyyy`), `og:site_name` Kapelusz, no image; 5 min cache (1 min generic); pointer `events/{eventId}.json`
  → band written on event creation/undo-restore, first band keeps the id; 401 never (paths answered before sign-in)
  (I6 §2).

**Superseded**: top-bar share icon sharing the settlement (I3 §2.1 → I5 §3.4); the app address as footer (→ event link).

## 9. Money rules (domain core, `shared/`)

### 9.1 Representation and limits
- `Long` minor units, never floating point; one currency per band (I1 §2).
- Limits (shared `Limits`, enforced by the reducer on client and server): amounts ≤ 10^11 minor units (`MAX_AMOUNT`),
  `Money.parse` rejects longer numbers, overflow-safe multiplication; names 100, texts 500, notes 2000; ids
  `[A-Za-z0-9_-]{1,64}`, `band-fund` reserved, member ids unique against guest ids; max members 200, events 2000, costs
  5000, fund entries 5000, band attachments 5000, parties/event 200, items/event 500, undos/push 20; ≤ 4 nested undos;
  server refuses a push whose resulting band exceeds 3 MB measured in UTF-8 bytes (413) (I3 R1, R6; I5 A2).
- Amounts parsed per language (thousand separators; "1,000" in English is a thousand) (I3 R8).

### 9.2 Event inputs
- **Received money**: (party, amount, source ∈ `TICKETS_ONLINE, DOOR, HAT, MERCH, CONTRACT, SPONSORSHIP,
  OWN_CONTRIBUTION, OTHER`, note, ≤ 5 files). Members record their own in events they play; leaders for any participant;
  never on an artist's line (I1 §2.1; I1 DL M1; I4 §1.1, §2.1).
- **Planned payment (awaited fee)**: a leader's entry "awaiting from the organiser" counting in income at once;
  "Paid"/Received… (who got it: a money-holding participant or the fund, how much; the rest stays awaited) by leaders,
  the recipient, or the treasurer for the fund; "Welched" removes it after confirmation; the received money stays the
  lead's record (recipient can't edit it) (I4 §2.2; I5 §3.5; I7 §3.3).
- **Band fund amount**: positive = taken off the top to the treasurer; negative = fund covers part; entered as direction
  + positive amount; only while the fund is on. Alternatively `fundPercent` (0–100 in the model, slider 0–50% in 5%
  steps): percent of profit (income − refunds), rounded **down to whole currency units**, never below 0, computed live;
  "From the fund" is always an amount. `Band.fundDefault` (percent/amount/nothing; leaders) applied to new events and
  optionally to unsettled unarchived events still on the old value (I1 §2.1; I7 §5.2).
- **Refunds**: (costId, amount) — only costs of this event's participants; always 100% of the chosen amount, no
  percentages (I1 §2.1, §2.4).
- **Shares** per participant (exactly one of): weight (display "shares", 100 = 1, quarters on a slider up to 2,
  off-quarter shown decimal, 0 = no share), fixed amount (off the top), or percent of profit (0–100 in 5% steps;
  0% = weight 0). Weight is kept underneath for switching back. Per event, not remembered per member (except via the
  member default) (I1 §2.1; I3 §2.4; I7 §6).

### 9.3 Computation
```
base        = income − refundsTotal + fund top-up (negative fundAmount)      // "profit plus what the fund adds"
fundTake    = fundAmount (>0) or floor_units(base' × fundPercent)             // fund % is of profit (income − refunds)
percentages = floor(base × percent) per percent participant                  // same base as the fund's percent
fixed       = Σ fixed amounts
rest        = income − fundTake − refunds − percentages − fixed
share(p)    = rest × weight(p) / Σ weights   (exact rational), floor, leftover grosze one by one by largest
              fractional remainder, ties by party id
payout(p)   = share/fixed/percent + Σ refunds of p's costs in this event
```
- Order: percentages (fund's and participants') and fixed amounts first; the rest by shares; leftover grosze go to share
  holders; with nobody on shares and percentages at exactly 100%, to percent holders in proportion (I7 §6).
- Invariant `Σ payout = income − fundTake` (I1 §2.2).
- **Problems, not refusals** (shown on the Settlement page, settling disabled): `LOSS` (income does not cover fund,
  refunds and fixed shares; fixes: defer refunds, add income e.g. OWN_CONTRIBUTION, negative fund amount), `NO_SHARES`
  (money left but nobody with weight > 0 / percentages under 100%), `OVER_PERCENT` (percentages > 100%) (I1 §2.2;
  I3 §2.4; I7 §6).
- Tie-breaks between settlement lines and suggested transfers are by **party id**, never roster position (since
  1.31.1) (I5 B1; I8 §4).

### 9.4 Balances, suggestions, transfers, status
- Target: participant = payout; band fund = fund take. Balance = received − sent + received transfers (settled only).
  Suggestions are recomputed live, never stored: greedy largest surplus → largest deficit; the fund and the treasurer
  (or the treasurer's pair) are paired first ("Alice: keep 100 zł for band fund [Confirm]"); suggestions only for money
  actually held (awaited fees excluded) (I1 §2.3, I1 DL M1; I4 §2.2; I5 §7.3).
- The treasurer's line says "hands over X" with "keeps Y for the fund" underneath (I6 §8).
- `SettleSuggestions(eventId, partyId?)` computes transfers against the state it is applied to (stale clients never record
  stale amounts); transfer ids `<opId>-<n>`, duplicates rejected (I1 DL M1; I5 B3c).
- Buttons on a suggestion: "Paid" for the payer, "Received" for the recipient, "Settle" for a leader recording others';
  "Part…" records at most the suggested amount; "Settle all"; "Hand over money" (any party to any other, amount defaults
  to the balance; the "no more than you hold" limit is UI-only except the artist cap); all record at once with an Undo
  snackbar (I1 §2.3; I5 §4.1, §4.6, §8, §1.6).
- Copy-amount button beside each suggestion (locale-formatted, two decimals, no currency) (I5 §3.1).
- BLIK/bank icons beside a transfer are shown only to who pays it (member, either partner of a paying pair, fund holder
  for the fund) and open a sheet with the number(s) and copy; a transfer **to the fund** shows only the fund's own
  details, never the holder's (I5 §8; I7 §7).
- Whoever recorded a transfer may remove it from Recorded transfers; leaders any; Undo from History of a transfer or
  settlement still needs a leader (I2 §7.2; I7 §3.3).
- An artist's outgoing transfer (single or via Settle all) is capped at what they were overpaid; nobody may record
  received money on an artist (I4 §1.1; I7 DL 2026-10-27 bug).
- **Status** (derived): `DRAFT` (nothing at all in the event), `LOSS`, `OPEN` (some balance ≠ target), `AWAITING`
  (everything held has been passed on, a fee still awaited; not archivable, counts as unsettled), `SETTLED`. Archived is a
  separate flag (I1 §2.3; I3 R10; I4 §2.2).
- Editing a settled event reopens it with compensating suggestions; recorded transfers are never rewritten (I1 §2.3).

## 10. Costs and refunds

- Fields: payer (leaders may pick "Paid by"), amount, date (default today), "Incurred for" event (only events the payer
  takes part in; reducer refuses others), category `TRAVEL, ACCOMMODATION, EQUIPMENT, FOOD, PROMOTION, OTHER`
  (icons: car, bed, speaker, cutlery, …, shown everywhere a cost is a row), description, ≤ 5 files (I1 §2.4; I3 R2;
  I6 §4).
- Outstanding = amount − Σ refunds assigned anywhere (events + fund) → a cost can never be refunded twice. Paid = refunds
  in SETTLED events + fund refunds (a refund paid out in an AWAITING event may still show as waiting — I5 B4b "not now").
  Owner-facing statuses: **Waiting**, **Partly refunded**, **Refunded**. Reopening a settled event makes its refunds
  planned again (I1 §2.4, I1 DL 2026-10-21; I4 §1.2; I6 §5).
- **Auto-include**: only a cost entered by a leader (own or on behalf) for an open event with no recorded transfers and
  not archived is pre-included; costs of members/artists always wait; creating an event or adding a participant includes
  nothing; a member's edit never raises an included refund (lowering follows the cost) (I3 R2; I4 §1.2).
- Event Money page "Waiting": "Not refunded yet. Refund one here, or leave it for a later event." with **Refund here**
  (leaders) and a pencil to the cost's form; included refunds can be lowered (partial) or removed (deferred); the refund
  amount button is "sliders", not a pencil; a member sees their own waiting costs on the event (I1 §2.4; I5 PR #44;
  I6 §5).
- Each cost row on the Money page shows its files read-only (I5 §4.8).
- Cost → event links in the cost form open the event's Money page (I6 §5).
- **Lock**: a member's own cost is locked once a refund of it sits in an event with recorded transfers or it was refunded
  from the fund; a leader can still edit it, never below the refunded amount; the owner may still add and rename files
  (not remove) (I1 §2.4, I1 DL M1; I7 §3.3).
- My costs: filters Pending (default; anything not fully refunded) / Refunded / All; "+ Add cost" with amount first;
  leaders see Paid by and a Mine / All members switch (adding for someone else switches to All); viewers see everyone's
  costs; "+ Add cost" on the event's refunds opens the form with Incurred for and Paid by preset (I1 §4.1; I2 §7;
  I4 DL "Viewer fixes").
- Deleting a cost asks (dialog), is final for its refunds (I5 §2.3).
- A deleted or foreign cost shows "Cost not found" (I5 C7).

**Superseded**: Refuse / Refused status / resubmit dialog (I4 §1.2 → removed I6 §5); "Pending from earlier" naming (I1
§2.4 → "Waiting", I4 §1.2); Pending / Refund planned statuses (→ Waiting, I4 §1.2); auto-include for everyone (I1 §2.4
→ I3 R2, I4 §1.2).

## 11. Band fund

- Off until a leader chooses a holder (Settings → Band fund page; read-only for non-leaders); holder is a non-archived,
  non-artist, non-viewer member; can be changed, not cleared while on (I1 §2.5, I1 DL M5.1; I5 C6).
- Balance = Σ settled fund transfers from events + Σ entries; may go negative (red, never blocked). "Not handed over" is
  split into owed to the fund and to pay out (I5 B4a — listed "not now"; verify) (I1 §2.5).
- **Entries**: money in names the payer — a member (artists too) or "Outside the band"; defaults to who adds it; a
  People button adds the same amount for several members at once (membership fees). **Money out only refunds a cost**
  (plain withdrawals refused by the reducer; old ones remain, deletable, counted under Outside the band). Picking the
  cost fills the description (description → category → "Cost"); entries read "from Bob" / "refund to Bob". Leaders add
  both kinds; the treasurer only money in (I1 §2.5; I6 §8; I7 §3.3, §5.1).
- Fund tab (shown only while on, everyone): balance (red if negative), payment details card (BLIK/bank with copy, read
  only there), one list of events' money and entries by date (event before entries on its day; newest/oldest toggle
  remembered per device) (I1 §4.1; I7 §5.1, §7).
- Fund's own BLIK/bank (`Band.fundBlikPhone`, `Band.fundBankAccount`; holder and leaders; Settings › Band fund): band
  data, in history, undoable; forgotten when the fund is turned off; kept across holder changes (I7 §7).
- **Turning off** (`DisableBandFund`, leaders): only when balance = 0 and every event has handed over its fund amount
  (checked per event, no netting); drops the fund default and converts event percentages to the amounts they came to;
  settled events stay settled; history kept; choosing a holder turns it on again (I1 §2.5, I1 DL "explicit on/off";
  I7 §5.2).
- Fund changes need a treasurer, or must leave the fund in a state it could be turned off in (I3 R10).
- Undo is refused when it would leave the fund in use without a treasurer or with an archived treasurer (I2 §7.2).
- A fund default sent after the fund was turned off elsewhere is dropped, not the event (I7 §5.2).

**Superseded**: plain withdrawals (I1 §2.5 → I7 §5.1); the holder's personal BLIK shown for fund transfers (I5 §8 →
I7 §7); "not handed over" as a single netted total (I1 §2.5; fix in I5 B4a).

## 12. Settle together (pairs)

- Pairs only; a member in ≤ 1 pair or invitation; stored on both members (`settlesWith`, `invitedToSettle`); events keep
  `settledTogether` after a pair ends (settled ones stay settled; unsettled ones split again) (I5 §7.1).
- Who: members, leaders, owner and artists (not viewers); both need an account, neither archived; each acts only for
  themselves (nobody pairs others); the pair ends (invitation cancelled) on archive, unlink, or becoming a viewer; moving
  between artist, member, leader, owner keeps it; the confirmation of such a change says so with the number of unsettled
  events (I5 §7.2; I6 §11).
- Only Settlement and its transfers change: the two lines become one party ("Bob and Alice", placed where the first by
  order was); transfers recorded between real people: paid by a pair out of what each holds (bigger holder first, never
  below zero, maybe two transfers, one Undo takes both; an artist never pays out); received by a pair → the one owed
  more; fund ↔ treasurer's pair → always the treasurer (paired with the fund first). Either partner settles in events
  where both play; the Settle tab shows the pair's transfers to both (I5 §7.3).
- Artists in a pair: see only their own numbers ("Kept for you by Ania", "Still to receive" without it); a member
  holding for an artist partner sees "You hold for Ania"; ended pairs are told in the past tense on the events they
  settled; the Summary counts earnings per person (I6 §11).
- History lists every step; none undoable; undoing the archive/role change that ended a pair brings it back (I5 §7.2).
- An invitation waiting for you is a card on the events list (I5 §7.4).

## 13. Settle tab

- Every pending suggested transfer where you are sender or receiver across events, only those you may record (viewers
  see none); "Hand over money"; leaders see a second section with all OPEN/LOSS/AWAITING events (I1 §4.1; I4 DL "Viewer
  fixes").

## 14. History and Undo

- Every accepted operation is recorded server-side; since I5 the history lives in **DynamoDB** (`art-split-history`, one
  record per operation; S3 history dropped); served via `GET /bands/{id}/log?month&before&months`, three months per
  request, pages of 100, newest first; Load more tied to its month; band-wide history visible to every non-artist member;
  an entry an app cannot read shows "A change from a newer version"; `UnknownOperation` shows "A change this version
  doesn't know" (I1 §4.6, I1 DL M3/M4 review; I2 §2; I3 R8; I5 PR #41/#42, I5 DL 2026-10-23; I8 §2).
- Each change records what it touched before and after; an undo names the log entry (I2 §7.2).
- **Undo from History** (↶ on the newest change not undone, skipping undo lines and undone changes; ↷ redo on the
  newest undo unless a change came after): refused "Changed since" when anything touched changed afterwards; ordinary
  change with the permissions of the reverse change; shown as "Undid: …"/"Redid: …"; confirm dialog naming the change;
  refused when the result breaks band rules (fund without treasurer, archived treasurer, dangling references, cost
  over-refunded, would restore removed attachments, refund of a cost no longer eligible); not undoable: join by invite,
  unlink, transfer ownership, delete band, schedule/cancel deletion, settle-together steps, BLIK/bank, leaving; undoing a
  transfer/settlement needs a leader; ≤ 20 undos per push; changes from before V2-M7 or very large offline batches have
  no arrow (I2 §7.2; I3 R6; I5 §1.6, §7.2, §8; I6 §11a; I7 §7, §9).
- **Undo snackbar (hold)**: the change applies on screen at once but is held from syncing (`LocalBand.holds`, op id →
  until), nothing queued after it is synced either; Undo withdraws it (no server change); hold ends when the snackbar
  goes (10 s, ✕, pushed out by a 4th, screen left, app stopped) with a 15 s expiry saved with the queue; past it Undo says
  "already sent"; stacked ≤ 3; per screen. Used for: guest removal, participant removal, attachment removal, settle
  actions, hand over, line-up changes, delete member, attaching shared files; dialogs kept for money removals, cost
  deletion, event deletion, Paid/Welched (I5 §2; I7 §3.1; I8 §1.3; I6 §1).

## 15. Attachments and files

- Receipts on costs (≤ 5), files on received-money entries (≤ 5), files on events (≤ 10); images compressed on device
  (orientation applied, 1600 px, JPEG ~80, EXIF dropped, ≤ 3 MB), PDFs as-is ≤ 10 MB; renamable; file type falls back to
  the extension when the provider gives no MIME (I1 §4.5; I4 §2.1; I5 D7).
- Who: entry files follow who may edit the entry; event files leaders; artists don't see entry files but do see event
  files; a cost's owner may add/rename files on a locked cost (I4 §2.1; I7 §3.3).
- Offline-first: file stored on device first, then `AddAttachment` queued; after the server has it, upload through a
  15-min presigned PUT signed for exact size/type, create-only (`If-None-Match: *`); any non-viewer member may upload a
  listed attachment (so a lock meanwhile doesn't strand it — I5 A7 "upload only by who added it" was listed "not now");
  opening via short-lived presigned GET; a file still waiting cannot be opened; removal/cost deletion deletes the file
  (versioning 30 days); the local file is not deleted eagerly on remove (I1 §4.5, I1 DL M5.2; I3 R4; I5 A8).
- Thumbnails (~96 dp grid), full-screen viewer (zoom, pan, swipe between the same owner's files, copy/rename/remove,
  Android PDF rendering with "Open in app"), disk cache ~100 MB by attachment id (session-only on web) (I5 §4.2–4.3).
- Several files at once from the picker ("Added 3 of 5 files…"), oversize/other types skipped and reported; camera takes
  one (I5 §4.7).
- Android share target for images/PDFs ("Attach to…" bottom sheet: events of all bands the user may attach to, past
  newest first then Upcoming; New cost / New received money / event files / existing costs and entries with room;
  "Room for N more"; nothing dropped silently; after sign-in if signed out) (I6 §1).
- Attachment keys include the cost id (I3 R6).

## 16. Summary of a period and Where we played

- Cog menu → Summary; period chips Last 12 months (default) · This year · Last year · All time · Custom… (date range,
  remembered per band per device). Blocks: **You** (Earned, Refunded, Waiting; hidden when all zero), **Band** (settled
  events count; Collected = Paid to the band + Cost refunds + To the band fund; fund in/out broken down by payer / whose
  cost, balance at period end), **Costs by category**, **Members** (Earned/Refunded, each of a pair separately, Guests
  row, Former members apart; alternating shaded rows). Only settled events count (unsettled named: three then "and N
  more"); event money on the event's date; fund transfers on the day recorded, fees paid straight to the fund on the gig
  date; refunds per payment date; a cost counts once fully refunded on its last refund date. Artists see only You.
  Computed in the background (I6 §7, §8; I7 §5.1).
- **Where we played**: Summary's last row; map (Android) or list (web); every played event (date ≤ today, any status,
  archived too) with a location; artists only theirs; coordinates matched to 4 decimals = one pin; addresses geocoded by
  the server (`GET /bands/{id}/locations`, any member, band's own locations only, ≤ 25 new per call, cached in DynamoDB
  `art-split-geocode` by SHA-256 of the normalised text, "not found" kept 30 days, failures not kept); "N concerts not
  on the map" listed (I7 §8).

## 17. Calendar subscription

- One private link per user: `https://kapelusz.jacekgajek.com/calendar/<secret>.ics` (CloudFront → API, `private,
  max-age=900`; older API-address links still work); Settings → Calendar subscription with Copy link, Add to Google
  Calendar, "Include events I don't take part in" (hidden for artists and ignored by the server), Reset link; the card
  explains subscribing on a computer and turning on sync in the phone app (I2 §6; I4 §1.1; I6 §9, §10).
- Content: events of all the user's bands they take part in, from 3 months back, not archived; title = name, location =
  Location, URL = directions; time: no agenda → all-day, one → one hour, several → earliest to latest (before 05:00 =
  next day); description: times, details, "Band · <event link>"; no money; UID escaped (I2 §6; I3 R1; I5 §9.1, §10).
- A gone feed answers **410** (never an empty calendar); 5xx become the web page and calendar apps keep their last copy
  (I6 §10).
- Stored as `users/{sub}-calendar.json` and `calendar/{secret}.json` (I2 §6).

## 18. Devices and change notifications (sync triggers only)

- Android registers an installation id + FCM token (`PUT /me/devices/{installationId}`, after sign-in, at start, on
  token refresh, after first band; `DELETE` on sign-out); answer `{messaging: bool}`; ≤ 10 devices per user, pruned when
  unseen 60 days, on sign-out-everywhere, after the last band, or when FCM says UNREGISTERED (I7 §2.4).
- Server sends an FCM **data message with only the band id** after every write that changes a band for others (accepted
  push, join, leave, deletion; not reads, refusals or file uploads) to every device of every linked, non-archived member
  except the calling installation (query param `installation`); sent in-request, in parallel, ≤ ~1 s, failures ignored;
  key from `server/fcm-service-account.json` in the data bucket; FCM HTTP v1 called directly (I7 §2.2, §2.5).
- Phone: normal priority; synced only when the app is on screen (background messages ignored); other bands fetched
  quietly or dropped with the usual dialog; polling fallback 5 min with messaging, 30 s without, plus at once on
  foreground and after each local change; Firebase started only after sign-in, no analytics, no `POST_NOTIFICATIONS`
  (I7 §2.3).
- Web: polls every 30 s, pauses in a hidden tab after one push round, syncs at once when visible (I7 §2.6).
- The sync icon blinks only while sending (I7 §1a).

**Superseded**: all user-facing notifications — gig reminders, debt/fee reminders, "transfer recorded with you", their
permissions and settings, periodic background sync, secret-menu reminder checks (I2 §5, I3 §2.2, I4 §1.3, I5 D1–D3 →
removed I6 §9); old jobs, alarms, channels and logs are cleaned once on update (I6 §9).

## 19. App versions, updates, Google Play

- Android comes **only from Google Play** (closed testing until production); one build; Play App Signing with the
  existing release key; CI uploads AAB to closed testing; version code = CI run number + 10; production promoted by hand
  (I7 §1; RM).
- In-app updates via Play's app-update library (flexible update strip "A new version… · Update" → "Restart"); "Update
  required" starts Play's immediate update or opens the listing; a version declined in Play's dialog isn't offered again
  (I7 §4).
- Apps send `appVersion=<versionCode>` (0 = local dev, always allowed) and `installation` as query params; below
  `Api.MIN_APP_VERSION` (currently 12) or missing → `426 update_required`, sync stops, blocking screen (web: reload).
  Raising the minimum needs the required build live on Play first (I2 §2, I2 DL; I7 §1.3).
- Web: service worker caches the app for offline start; `version.json` is still written by every deploy for "a new
  version: reload"; network-first uses only `response.ok` answers and keeps the cache fill alive (I3 §2.3; I5 D6, D7;
  I7 §1.1).
- A phone that came from the website APK has the updater's leftovers cleaned at start (`ApkLeftovers`) (I7 §1.1).

**Superseded**: website APK + `version.json` update strip (I1 §6.2, M5.3); self-updater with silent install (I2 §1,
I5 C1′); "Support me!" link (I7 §1.1); Play Store "out of scope" (I1 §9).

## 20. Settings layout and app-level UI

- Settings list grouped: **Band** — Band name (leaders), Band fund page (on/off, holder, default for new events, fund
  BLIK/bank), Event agenda (leaders), Your BLIK number, Your bank account; **Personal** — Language (device / English /
  Polski), Calendar subscription; **Account** — sign-out, Sign out on all devices, Leave band, Privacy policy; then Delete
  band (or the deletion notice + Keep the band) after a divider; version (secret menu) last (I1 §4.1; I2 §7.3; I5 §8;
  I6 §9, §11a; I7 §5.2, §7, §9).
- Navigation: bottom nav on phones, side rail ≥ 600 dp; tabs Events, My costs, Settle, Band fund (only while on);
  artists: Events, My costs; cog menu: band switcher, New band, Join a band, Band members, Summary, History, Settings
  (with icons) (I1 §4.1; I4 §1.1; I6 §7).
- Sync indicator (offline / syncing / error / sign in again) next to the cog (I1 §4.1).
- Navigation guards: actions only from a resumed screen, never pop the last entry; submit buttons disabled after success;
  one sign-in handled once; band switch resets stacks (I3 R3; I5 C2).
- Rejections from Settings are shown (snackbar), not swallowed (I5 C6).
- Install hints on the events list every visit until installed: iPhone "Add to Home Screen"; Android "Get the app" →
  Play listing (or the closed-test join page while `PLAY_PUBLIC=false`); hidden on iPhone/iPad for the Android one
  (I1 §4.1; I3 §2.3; I7 §1.5).
- Persistent rejections of background pushes and lost bands must be shown and acknowledged (I5 A3 — Tier 4 "not now";
  rejections in the foreground show a snackbar "Your change to X was rejected: <reason>") (I1 §5.2).
- A stuck push the server always refuses is shown with export or discard (I3 R9, V3-M3.4).

## 21. Languages, naming, accessibility

- Polish and English, device language with override; the app name follows the phone's language under the icon
  (Kapelusz / Pass the Hat), the web title the browser's, texts the app's; one code name `kapelusz` (address, app id
  `com.jacekgajek.kapelusz`, packages, storage); S3 buckets and Lambda keep `art-split` names; the calendar feed is
  called Kapelusz (I1 top; I2 V2-M10.0).
- Wording: "received"/"planned" money, "leader"/"your band leader", "Welched" ("Wydymali") deliberately informal;
  shares shown as "1½ shares", "%" only for real percentages (I5 §3.5, §11; I7 §6).
- Polish plurals and gender-neutral texts; error texts in the app's language on the web (I3 R8).
- Web fallback fonts bundled (Noto Sans cuts) and preloaded before first draw (I6 DL 2026-10-26).
- Accessibility: transfers read as one phrase, avatars by name, headings, status as text, labelled buttons naming what
  they remove, minimum touch sizes (I1 §4.7; I3 R13; I5 C7).
- Web addresses: `#events`, `#costs`, `#settle`, `#fund`, `#event/<id>`, `#cost?costId=`, `#settings`, `#members`,
  `#history`, `#new-band`; unknown → events; one tab at a time (Web Locks, hand-over waits for the old tab) (I1 §4.7;
  I3 R9).
- Golden/demo band "The Night Owls" exists in the owner's account for screenshots (I7 §1.4).

---

## 22. Edge cases solved (regression checklist)

A regression checklist: each line is a case that once went wrong or was argued over, and how it behaves now.

### Money and settlement
- [ ] Σ payout = income − fund take, in integer grosze; leftover grosze by largest remainder, ties by party id (I1 §2.2; I8 §4).
- [ ] G1–G12 golden scenarios still pass (I1 §2.6), with G8/G9 tie-breaks reinterpreted by party id (I8 §4).
- [ ] Reordering/renaming members never changes a settled or archived event by 0.01 (I5 B1; I8 §4).
- [ ] Σ weights = 0 with money left → NO_SHARES problem, not a crash (I1 §2.2).
- [ ] net < 0 → LOSS blocks settling; deferring a refund, adding OWN_CONTRIBUTION or a negative fund amount fixes it (I1 §2.2, G10).
- [ ] Fixed amounts off the top; shortfall = LOSS; unclaimed rest = NO_SHARES; refunds on top of fixed (I3 §2.4).
- [ ] Percentages (fund's + participants') share one base; > 100% = OVER_PERCENT problem (never a refusal, two phones may set at once); with nobody on shares and exactly 100%, grosze go to percent holders in proportion (I7 §6).
- [ ] Fund percent rounded down to whole currency units, never negative; converted to an amount when the fund is turned off (I7 §5.2).
- [ ] A huge amount cannot overflow the split; `Money.parse` rejects it (I3 R1).
- [ ] "1,000" parses as a thousand in English and as 1.00 in Polish (I3 R8).
- [ ] `SettleSuggestions` computes against the applied state; a replayed op id cannot create duplicate transfer ids (I1 DL M1; I5 B3c).
- [ ] Treasurer and fund are matched first ("keep for band fund"); the treasurer's line shows "hands over X / keeps Y" (I1 DL M1; I6 §8).
- [ ] Manual hand-over then recompute still converges to SETTLED (G11); late edit reopens with compensating suggestions and untouched recorded transfers (G12).
- [ ] An event paid only from the fund (negative fund amount, no received money) is not a DRAFT and can be settled (I3 R10).
- [ ] Awaited fee: counts in income at once; suggestions only for money held; AWAITING when all held money passed on; not archivable; partial "Received…" leaves the rest awaited; deleting/Welching recomputes (possibly LOSS) (I4 §2.2).
- [ ] Artist outgoing transfer capped at overpayment; "Settle all" with an artist owing works (I7 DL 2026-10-27).
- [ ] Pair pays out of each holder's money, bigger first, never below zero; pair receives to the one owed more; fund ↔ treasurer's pair goes to the treasurer; ended pair keeps settled events settled (I5 §7.1, §7.3).
- [ ] Artist in a pair never holds money; "Kept for you by…" shown; events list counts kept money as received (I6 §11).

### Costs and refunds
- [ ] A cost can never be refunded past its amount across events and the fund (I1 §2.4).
- [ ] A member's cost for a settled/archived event stays waiting; a leader's for an open event auto-includes; creating an event or adding a participant includes nothing (I3 R2; I4 §1.2).
- [ ] A cost may only be "incurred for" an event its payer plays; changing payer narrows the picker; an orphaned link stays visible until changed (I3 R2).
- [ ] A member's edit never raises an included refund; a lowered cost lowers the refund (I4 §1.2).
- [ ] Locked cost: owner can't change money or remove files but can add/rename files; leader never below refunded amount (I1 DL M1; I7 §3.3).
- [ ] Reopening a settled event turns its refunds back to planned (I1 DL 2026-10-21).
- [ ] Deleting an event returns its refunded costs to waiting (I1 §4.2).

### Band fund
- [ ] Balance may go negative, shown red, never blocked (I1 DL PR #1).
- [ ] `DisableBandFund` only at balance 0 and every event handed over, per event without netting; past transfers don't block; settled events stay settled; re-enabling keeps history (I1 DL "explicit on/off").
- [ ] Money out only as a cost refund; old plain withdrawals readable and deletable (I7 §5.1).
- [ ] Fund changes need a treasurer or must leave the fund disable-able; archived events accept no ops except restore/delete (I3 R10).
- [ ] Fund holder can't be an artist or viewer; picker filters them; rejection shown (I5 B2, C6).
- [ ] Fund default sent after the fund was turned off elsewhere is dropped silently, the event kept (I7 §5.2).
- [ ] Summary's fund balance equals the fund page (transfers on the recorded day) (I6 §8).

### Roles, roster, invites
- [ ] Owner is always a leader; ownership only to a non-archived leader with an account; former owner stays leader (I1 §3; I5 B3a, §11).
- [ ] Viewer cannot be added to an event; making someone a viewer refused while they play/hold money/hold the fund (I4 DL; I5 B2).
- [ ] Making someone an artist refused while holding money in an unsettled event or the fund; artist invite refused/paused the same way (I4 §1.1).
- [ ] Nobody can record received money on an artist (I4 §1.1).
- [ ] Member names unique (case, trim, archived); guests may share a member's name with a warning; former member → Welcome back (I5 §12; I6 §5).
- [ ] Delete member only when unreferenced (incl. fund payer, transfer recorder, participant adder); undo restores without the account; refused if the name was taken (I7 §3.1, §5.1).
- [ ] An admin cannot archive themselves with one tap (I5 C5).
- [ ] An archived member's user can still accept an invite for another member (I5 A10c — "not now"; verify).
- [ ] Invite revoked when the member's link changes; only valid while the issuer is an active leader; revoke uppercases (I1 DL M4; I5 A10d).
- [ ] 10 invite lookups/user/hour; invite links show a generic card (I1 §4.3; I6 §2).
- [ ] A pair ends only on archive, unlink or viewer; artist↔member↔leader keeps it; the confirmation counts unsettled events (I5 §7.2; I6 §11).

### Sync, storage, sessions
- [ ] Dirty mark cleared only when nothing changed during the write (I5 A1).
- [ ] A band on disk but missing from the index is not overwritten by a download (I3 R4).
- [ ] Attachment file stored before `AddAttachment` queued; local file not deleted before the server accepts removal (I3 R4; I5 A8).
- [ ] Storage checked before every push batch (sequence numbers never reused after a crash) (I5 A9a).
- [ ] Unreadable data → backup copy + start over, never a crash or silent loss; unreadable settings → defaults (I1 DL 2026-10-21).
- [ ] Bands the user lost access to are dropped (with unsent ops backed up); `band_deleted` vs `band_not_found` messages (I1 §5.2; I3 R9).
- [ ] A band the server refuses to create (409/400) doesn't block other bands; stuck pushes offer export/discard (I5 A4; I3 R9).
- [ ] A refused token is discarded only if still current; two simultaneous 401s don't force sign-in (I3 R9).
- [ ] Background sync never publishes NEEDS_SIGN_IN; sign-out takes the refresh lock and runs in the app scope (I5 A5, A6).
- [ ] Sign out on all devices invalidates sessions and Google tokens before it, rotates the calendar secret, forgets devices (I3 R6; I5 A10b; I7 §2.4).
- [ ] Held (Undo) changes survive a kill and send on next start; nothing queued after a hold syncs before it (I5 §2.1).
- [ ] Leave band pushes unsent work first and aborts with a message if it can't; "band not found" means done (I6 §11a).
- [ ] FCM: sender's own installation excluded; messages ignored in background; polling 5 min/30 s fallback; no Play services → nothing breaks (I7 §2).
- [ ] Web: IndexedDB abort rejects, dropped connections reopened; hidden tab pauses after one push; one tab via Web Locks (I5 D4; I7 §2.6; I1 §4.7).
- [ ] Service worker: only `ok` network answers replace the cache; cache fill kept alive past the 5 s timeout (I5 D6, D7).
- [ ] Old apps: `UnknownOperation` in history/queue never crashes; unknown enum values coerce; a band saved by an older schema is re-downloaded once (`LocalBand.schema`) (I8 §2; I2 DL V2-M3).

### Server
- [ ] Membership check before any undo lookup; ≤ 20 undos per push; ≤ 20 bands per user; attachment storage budget (I3 R6).
- [ ] Band size cap measured in UTF-8 bytes with headroom; history page budget likewise (I5 A2).
- [ ] A push racing a deletion answers "band gone", not 500; non-members get 404 like a missing band (I3 R6; I1 DL M3).
- [ ] `LinkUser` only from the server (invite-only) (I5 §1.6).
- [ ] `ScheduleBandDeletion` refused from apps; cancel by any leader; due deletions run on load and by the hourly sweep (I7 §9).
- [ ] Event pointer: first band keeps the id; pointer removed with event/band; deleted/unknown → generic card (I6 §2).
- [ ] Calendar: hostile ids escaped; gone feed = 410; artists' feed ignores "include all" (I3 R1; I4 §1.1; I6 §10).
- [ ] Places resolve follows short links only through Google hosts (incl. past the EU consent page) (I5 §9.1).
- [ ] Geocode cache: not-found kept 30 days, failures not kept, ≤ 25 new per call, band's own locations only (I7 §8).
- [ ] Leaving/unlinking after the last band drops the account's leftovers (photo, calendar, attempts, devices) (I6 §11a; I7 §2.4).

### UI
- [ ] Double-tap Back never pops past the first screen; double-tap Save never duplicates (I3 R3).
- [ ] Rotation/language change keeps the back stack and form fields incl. Paid by, event, category, drafts (I3 R3; I5 C3).
- [ ] Dialogs close themselves when their choices vanish by sync; stale entry/refund dialogs re-read by id (I3 R3; I5 C4).
- [ ] Forms send only changed fields (no overwriting others' concurrent edits); rows keyed by parties so "Part" never targets the wrong people (I3 R8).
- [ ] Band switch / lost access clears other band's back stacks; foreign route ids redirect to the event list (I5 C2).
- [ ] History "Load more" tied to its month; a failed load keeps the list (I3 R8).
- [ ] Event search ignores hidden filters and hides the cards; summaries built in background (I6 §6).
- [ ] Picker locks anyone with money; "From an event…" keeps locked and foreign participants, dedups guests by name (I8 §1.2).
- [ ] Removing a participant with money/transfers/refunds is refused with a clear message (I2 §7).
- [ ] Share falls back to the clipboard; Android app banner caches only successful checks (I5 D7).
- [ ] Deploy: `.well-known/` never deleted unless rewritten; blank variables count as missing; smoke check fails the job; wasm uploaded before HTML, old wasm deleted last; `download/` untouched (I3 R5; I1 §6.2).

---
