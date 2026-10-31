# Kapelusz Pro — bigger ensembles (analysis, 2026-10-29)

An analysis, not a plan: what an orchestra, a big band or a choir (20–120 people) would need from the app, what it
costs to build, and how to keep it out of the way of a five-piece band. Nothing here is decided.

## 1. Who the big groups are, and how they differ

| | Small band (today) | Big band / orchestra / choir |
|---|---|---|
| People | 3–10, all friends, all in the app | 20–120; a core plus substitutes ("deps"); many never open the app |
| Who runs it | everyone a bit; one or two leaders | a manager or a board, a librarian, section leaders |
| Money | the hat or the fee, split in shares | one contract fee to the organisation; fixed fees per musician, often by position (principal, tutti, sub) |
| Who plays | everyone, mostly | a different line-up per concert; rehearsals in between |
| Paperwork | a few receipts | parts for every piece and section, setlists, contracts, payout lists for the accountant |
| Privacy | everyone sees everything | players see their own fee, not the others' |

What already fits:
- **Artists** (ITERATION_4 §1.1, ITERATION_7 §3.2) are what most orchestra players should be: they see their own
  events, fee and transfers, and record only their own costs.
- **Fixed shares and percentages** (ITERATION_3 §2.4, ITERATION_7 §6) cover fees by position.
- **The calendar feed, event links, attachments, the period summary and search** all apply as they are.
- **The settlement's transfers:** in a big group one person or the fund usually collects. The greedy matching then
  gives one transfer per player, all from that person, which is the right shape. There are just many of them (§3.4).

The gaps are about scale, not settlement rules: picking 60 people, grouping them, telling them what to play, finding out
who is available, and paying them all.

## 2. The rule: small bands must not notice

1. **Shaped by the data, not by a "Pro mode" switch.** Section headers appear once the band has a section. The bigger
   participant picker appears once there are too many members for chips. The Library tab appears once it is turned on.
   A band without sections looks exactly as it does today.
2. **Off by default, one place to turn things on:** Settings › *Features* (Sections, Library, Availability), for
   leaders. Members never see the switches.
3. **Scale fixes are not Pro.** A better participant picker or roster search helps a 15-person band too. These go into
   the free app. Pro is for what costs money to run (storage, members) or only a big group needs.
4. **No new axes where an existing one will do.** Prefer sections over a role per section, the artist role over a new
   "player" role, and shares over a new "pay scale" model, unless the simpler one fails.
5. **Test each feature on the Test band (5 people) before merging:** a screen gaining a row, a chip or a menu entry
   with no sections, no library and five members is a regression.

## 3. Features

Effort: S (days), M (a week or two), L (a milestone or more). Value: for big groups.

### 3.1 Choosing participants at scale — **free**, M, value very high

Today: participants are rows, and everyone not taking part is a chip in a `FlowRow` (`ParticipantsSection.kt`). With
60 members, 50 chips fill several screens, and finding "Kowalska" means reading them all.

Proposal:
- **Up to ~12 members not taking part, keep the chips** (no change for small bands).
- **Above that, one button "Add participants (48)"** opens a full-screen picker:
  - a search field at the top, filtering by name;
  - grouped by section when the band has sections, each header with a tri-state checkbox (the whole section in or out);
  - a checkbox per member with their avatar and default share; the count chosen at the bottom; **Add** applies them as
    one batch (one snackbar, one Undo: the batch already exists for the sync).
- **"Same as…"**: start from the line-up of another event (the last one by default), then adjust. In big groups most
  concerts repeat a line-up.
- **The participant list itself** collapses per section ("Violins I — 8") when there are sections and more than ~20
  participants; the share is shown only when it isn't the default, as today.
- Limits: `MAX_PARTIES_PER_EVENT = 200` is enough. Adding 60 at once is one push of 60 ops, which is fine.

### 3.2 Sections (artist grouping) — Pro, M, value very high

Model: `Band.sections: List<Section(id, name)>` in order, and `Member.sectionIds: List<Id>`. A list, because doublers
exist: sax and clarinet, a singer in two voices. Guests can be in a section for one event (`Guest.sectionId`), or not.

Uses, roughly in order of value:
1. **Picking participants by section** (§3.1).
2. **The roster grouped by section,** with a filter chip per section.
3. **A default share per section** (principals 120, tutti 100, subs fixed), applied when the member has no default of
   their own. This replaces most of a "pay scale".
4. **Sheet music and announcements by section** (§3.3, §3.5).
5. **A subtotal per section in the event summary** ("Brass: 6 players, 2 400 zł"): cheap and useful for the
   accountant.

Deliberately not, at first: **section leaders with their own permissions.** Roles are one cumulative axis (`Role`). A
role scoped to a section adds a second axis to `Permissions`, to the server's checks and to every screen. Only if
managers really ask for it ("the principal of the cellos sets the cellos' line-up"). Even then, start with a single
"may manage participants of their sections" permission and nothing more.

Small bands: no sections means no section headers anywhere, and Settings shows only "Sections: none · Add".

### 3.3 Sheet music — Pro, L (files) or S (links), value high but off-core

This is the biggest step away from what the app is (money after gigs). Worth doing only as a library hung on events,
not as a score viewer.

Model:
- **A Piece:** title, composer or arranger, and **parts**, each a file or a link, each for one or more sections, or
  "full score" for leaders.
- **An event's programme:** a list of pieces, in order. It doubles as the setlist, shown on the event and in its link
  card.
- **A player sees** the programme of each of their events, and opens the parts for their own sections. The other
  sections' parts are behind "All parts".

Two ways to build it, start with the first:
- **(A) Links, size S.** Each part is a URL to the band's own Google Drive or Dropbox folder. No storage costs, no
  copyright exposure for us, permissions handled by the drive. It solves "where is my part for Saturday", which is the
  real pain.
- **(B) Uploaded PDFs, size L.**
  - Attachments already do PDFs up to 10 MB and have a viewer. But a library is hundreds of files per band, not
    `MAX_BAND_ATTACHMENTS` receipts, so it needs its own quota, S3 prefix and listing. They can't live in `state.json`:
    a library index of 500 pieces × 20 parts would eat the 3 MB cap.
  - It also needs offline caching of the next concert's parts, which is the point on a stage without signal.
  - Storage is what makes Pro cost money. It is also where copyright comes in: uploaded parts are usually licensed to
    the ensemble, not to us. The terms need a line on that, and the privacy policy and Play's content policy need
    checking.

Not: annotations, page turning by pedal, rehearsal marks. Dedicated apps (forScore, Newzik, MobileSheets) do these
well. A link to the part in their format is enough.

### 3.4 Paying many people — Pro, M, value very high for the treasurer

With 60 players, the treasurer makes 60 bank transfers and records 60 transfers in the app.

- **A payout list per event, or for a period:** who gets how much, their account number (ITERATION_7 §7) and a
  title ("Koncert 12.11 — Kowalska"). A checkbox per line, then **"Mark checked as paid"**, which records all those
  transfers in one batch.
- **Export for the bank:** CSV, or the Polish bulk-transfer formats (Elixir-O / the CSV most banking sites import),
  from the members' stored account numbers. One upload instead of 60 transfers. For a Polish orchestra this alone could
  justify paying for Pro.
- **Export for the accountant:** per member and period (the period summary exists, ITERATION_6 §7) as CSV or XLSX,
  with what each was paid and when. Contracts and taxes (umowa o dzieło, PIT-11) stay the accountant's job; we only
  give them the numbers.

### 3.5 Availability before the line-up — Pro, M–L, value high

In a big group the hard part is asking "who can play on 12 November?", not settling afterwards. Today that question
lives in WhatsApp polls.

- **An event can ask for availability:** each member answers Yes / No / Maybe, from the event or from a notification.
  The leaders see the answers grouped by section ("Violas: 3 yes, 1 maybe, 2 no answer") and add the yes-es as
  participants in one tap (§3.1).
- **It needs notifications to work.** The app decided against them (ITERATION_6 §9) and has only silent sync messages
  (ITERATION_7 §2). Visible notifications here means a permission prompt, settings and Play's notification rules. For
  small bands it stays off: no event asks unless a leader turns it on.
- **Rehearsals** are where availability matters most, and they have no money. An event kind "Rehearsal" with no money
  sections at all (no Collected, no Split, kept out of the Summary and the Played list) is cheap once availability
  exists. Without availability, rehearsals are just calendar entries, which other apps do better.

### 3.6 Regular substitutes — free, S, value medium

Guests exist only in one event (ITERATION_6 §5). Big groups call the same five deps over and over. Options:
- they become members with no app (they already can: members need not be linked), shown as "Substitutes" when sections
  exist;
- or the guest field suggests names of guests from earlier events.

The second is enough for small bands too, and needs no model change.

### 3.7 Desktop and tablet layout for managers — free, M, value high

Managers of big groups work at a desk. The web app is phone-shaped. Google's own large-screen notice (#113) points the
same way.
- Two panes on wide screens: the list and the details side by side (events and event, roster and member).
- Tables where lists get long: the payout list, the roster with section, role, default share and account, the
  availability grid.
- This is also where the participant picker (§3.1) is easiest.

### 3.8 Bringing the roster in — free, S

Pasting a list of names (one per line, optionally "name; section") into the roster. The invite QR code and link exist
already for those who will use the app. 60 members typed one by one is the first thing a manager would give up on.

### 3.9 Smaller ideas, mostly not

- **Announcements and chat:** no. Every group already has WhatsApp or Messenger. A link to the group chat in Settings
  is the most worth doing.
- **Several ensembles under one organisation** (an orchestra with a chamber group and a big band): no. They can be
  separate bands; a manager in several bands switches between them already.
- **Seating plans, divisi, uniforms, instrument inventory:** no; outside what the app is for.
- **Fee confidentiality:** players as artists see only their own fee. Members as `MEMBER` see everyone's, as today. No
  new rule needed, only a hint when a big band makes everyone a member.

## 4. What scale does to the system

- **One `state.json` per band, 3 MB cap** (`Api.MAX_BAND_BYTES`). 60 participants per event is ~5 KB, and 80 concerts
  a year for 5 years is ~2 MB with costs and attachments. That is within the cap, but not for ever.
  - Archived events could move to a separate file per year, loaded on demand.
  - A library (§3.3) must never be in `state.json`.
- **Every change is a full-state download for every member's device.** A change messages all members (ITERATION_7
  §2), and each device fetches the whole state. That is 60 devices × up to 3 MB per change.
  - Cheaper first steps: compress the state response (gzip, roughly 10×); stop messaging artists about events they
    aren't in (§3.3 of ITERATION_7 already cuts leaders' messages).
  - Later: deltas (the ops since the device's version) instead of the state.
- **Write conflicts:** pushes are serialised by the S3 ETag (`retryOnConflict`). Ten managers editing at once is fine;
  sixty availability answers on the evening an event is announced may need more retries, or the answers kept outside
  `state.json` (a small file per event).
- **`MAX_MEMBERS = 200`** is enough for any orchestra.
- **Lambda and S3 costs** grow with the downloads above more than with storage, until a library is uploaded.

## 5. Paying for Pro

- **What is Pro:** sections, the library, bulk payouts and exports, availability. Free: everything a small band has
  now, plus the scale fixes (§3.1, §3.6–§3.8).
- **Per band, bought by any leader,** recorded on the band on the server (an entitlement with an expiry). Every member
  of that band gets it, whichever device they use. One person pays, as the treasurer does now.
- **Size limits as the push:** free up to e.g. 25 members. Above that the band works but is read-only for new members
  until it goes Pro. It is simplest to explain, and a big group hits it before anything else.
- **Billing:**
  - On Android, Google Play Billing is required for digital features (15% for subscriptions). It needs a server check of
    the purchase token and real-time notifications (Pub/Sub) for renewals and cancellations.
  - On the web, a card payment (Stripe) to the same entitlement. Play's policy allows the web purchase to unlock the
    app as long as the Android app doesn't link to it.
- **Privacy policy and Data safety:** purchase history becomes collected data (Financial info › Purchase history).

## 6. A possible order

| Step | What | Pro? | Effort |
|---|---|---|---|
| 1 | Participant picker with search, "same as…", one batch (§3.1) | free | M |
| 2 | Roster import by pasting (§3.8), guest name suggestions (§3.6) | free | S |
| 3 | Sections, grouped picker and roster, default share per section (§3.2) | Pro | M |
| 4 | Payout list, mark all paid, bank and accountant exports (§3.4) | Pro | M |
| 5 | Billing and entitlement (§5): only once 3–4 exist to sell | — | M–L |
| 6 | Programme and sheet music as links (§3.3 A) | Pro | S |
| 7 | Two-pane layout on wide screens (§3.7) | free | M |
| 8 | Availability, then rehearsals (§3.5) | Pro | M–L |
| 9 | Uploaded parts with offline caching (§3.3 B), state compression and deltas (§4) | Pro | L |

Steps 1–2 are worth doing now, Pro or not. Before step 3, it would pay to talk to two or three managers of real
orchestras or big bands. The ordering of 4, 6 and 8 depends on whether their pain is money, music or attendance, and
this analysis can only guess at that.
