# Kapelusz — Iteration 8

Started 2026-10-29. Same loop as before: a branch per piece of work; implement, independent review, fix, push, PR,
merge when CI is green.

Bigger groups (an orchestra, a big band, a choir: 20–120 people) are the theme. The rule for all of it: a five-piece
band must not notice. What changes with size appears only past a threshold (12, `Crowd.THRESHOLD`), and nothing new
needs a setting.

## 1. V8-M1 — Choosing participants at scale (grilled 2026-10-29)

Today everyone not taking part is a chip under the participants, a tap adds them; with 60 members that is several
screens of chips, and finding one name means reading them all.

### 1.1 When (Q1)

Up to 12 members not taking part: the chips as they are. Above: in their place one button, **Add participants (n)**,
which opens the picker. Counted from the members who could take part (current, not viewers), not from the guests.

### 1.2 The picker (Q2–Q6, Q10)

Full screen on phones, a dialog about 560 dp wide on tablets and the web.

- **The whole line-up** (Q3): everyone who can take part (current members, not viewers), those in the event ticked.
  Unticking removes them, except who has money in the event (collected, transfers, refunds): their tick is locked,
  with "has money in this event". Then this event's guests under **Guests** (Q5), ticked; unticking removes them, with
  the same lock. A new guest is added outside the picker, with **Add guest** beside the button, as today.
- **Shares** (Q4) shown on a row when not the default ("1½", "300 zł", "10%"), not editable there: a participant's
  share is changed on their row in the event, as today.
- **Start from…** (Q2): an earlier event (newest first, searchable by name) replaces the ticks with its line-up:
  its members who still can take part ticked, everyone else unticked (the locked ones stay); its guests copied in as
  new guests of this event (one of the same name already here is kept, not doubled), this event's other guests
  unticked. Newcomers take the share they had there; who is already here keeps theirs. Then adjust and Save; nothing
  changes before Save.
- **Order and search** (Q6, changed by Q12): grouped by role as on the members screen (§2), with quieter headings,
  then **Guests**; alphabetical within each. A search field at the top matches
  any part of a name, ignoring case and Polish letters ("lukasz" finds "Łukasz"). **Tick all shown** / **Untick all
  shown** beside it: the matches while a search is typed, else everyone; locked rows stay as they are.
- **Save** with the count, "42 of 58". Back with changes asks **Discard changes?**
- **Who** (Q10): leaders (as `AddParticipant`). Others, above the threshold, get "16 not taking part" in place of the
  button, which opens the same list read only (search, no ticks, no Save); below it, the greyed chips as today.

### 1.2a New events start with their creator (2026-10-29)

A new event no longer copies the line-up of the latest one: it starts with whoever creates it (unless a viewer), as
it did when there was no event before. Copying a line-up is **Start from…** in the picker, from any event, not only the
latest. Below the threshold, the chips add people one tap each.

### 1.3 Saving: `ChangeLineup` (Q8)

One operation, `ChangeLineup(eventId, add: List<Participant>, remove: List<Id>, guests: List<Guest>)`, for leaders;
[guests] are new guests (from Start from…), each with its participant in `add`:

- **All or nothing:** the same checks as `AddParticipant`, `AddGuest` and `RemoveParticipant` one by one (no viewers, no former
  members, nobody twice, nobody removed who has money in the event, the limits); one failing refuses all of it, the
  picker can be opened again. Removing a guest removes the guest, as today.
- One History entry ("Changed the line-up of Koncert: +5, −2"), undoable from History like any other; one snackbar
  with Undo (held back, as usual).
- The single operations stay for the chips and the rows' remove buttons.

### 1.4 The participant list in the event (Q7)

The section's title has the count, "Participants (42)". Over 12 participants the first 8 rows, then **Show all 42**,
open until leaving the event. At 12 or fewer, as today.

### 1.5 Dropdowns of people (Q9)

The shared `Dropdown`, with more than 12 options, gets a search field at the top of its menu, matching as the picker.
So "Collected by", "Hand over from/to", "Who paid" and the like scale too.

## 2. Alphabetical order, no roster order (Q12–Q15, 2026-10-29)

Leaders arranged the roster with up/down buttons, and that order was used everywhere people are listed. In a big group
a hand-kept order is a chore nobody does; alphabetical is what one searches by.

- **Alphabetical everywhere, for every band size** (Q12): event participants, settlement lines, the picker, chips,
  dropdowns, shares and texts. Ignoring case, with Polish letters in their place (Ł after L, Ś after S). Guests stay
  where they are apart today (their own heading in the event, after the members in the settlement), alphabetical among
  themselves.
- **The members screen grouped by role** (Q13), a heading with the count and a divider each, alphabetical within:
  **Leaders** (the owner and leaders; the owner's row says Owner), **Members**, **Artists**, **Viewers**, **Not in the
  app** (unlinked, whatever role is stored), **Former members** (collapsed, as now). Empty groups don't show.
- **Search on the members screen** (Q15) when there are more than 12 current members: groups without a match hide while
  searching; former members are searched too, their group opening when one matches.
- **No manual order** (Q14): the up/down buttons and `MoveMember` are gone. Operations the app no longer knows become
  `UnknownOperation` when read: History shows "A change this version doesn't know", the server refuses it, and a phone
  drops one it still has unsent. So an old `MoveMember` in History, or one waiting on a phone, breaks nothing, and the
  next operation removed won't either.
- Money never depended on the order since 1.31.1 (ties by party id); this changes only what is shown.

## 3. Milestones and task checklist

### V8-M1 — Choosing participants at scale, alphabetical order (§1, §2), branch `lineup-picker`, 1.32.0

- [x] `ChangeLineup`: reducer, permissions, limits, undo, History text (with tests)
- [x] `UnknownOperation`: decoding, refused by the server, dropped from a phone's queue, History text (with tests);
  `MoveMember` and its buttons gone
- [x] Alphabetical order (Polish letters) for participants, settlement lines, chips, dropdowns, texts
- [x] The members screen by role, its search
- [x] The picker (Start from, search, tick all, locks, Discard changes?) and its read-only form
- [x] The participant list's count and Show all; Add participants (n) past the threshold
- [x] New events start with their creator, not the latest line-up (§1.2a)
- [x] Search in `Dropdown` past the threshold

## 4. Decision log

- 2026-10-29 (bug, 1.31.1): moving a member in the roster could reopen a settled or archived event by 0.01: the
  leftover grosz, ties between suggested transfers and a pair's name followed the roster's order. Ties go by party id
  now; existing events whose grosz moved changed once (accepted).
- 2026-10-29: Past a threshold rather than a "Pro mode" or a setting (Q1): small bands see no change.
- 2026-10-29: The picker edits the whole line-up (Q3), not only adds: dropping five of sixty is five taps.
- 2026-10-29: One `ChangeLineup` (Q8) rather than many single operations: one History entry, one Undo, never half
  applied.
- 2026-10-29: Start from… replaces the line-up with the other event's, guests included (Q2): a concert repeating a
  line-up is the common case; whoever has money here stays, locked, as anywhere in the picker.
- 2026-10-29: New events start with their creator (§1.2a) rather than the latest line-up: the latest is often the wrong
  one; Start from… copies the right one.
- 2026-10-29: Alphabetical everywhere instead of the roster's order (Q12): one rule, for every band size; the roster's
  order is gone rather than kept for small bands (Q14).
- 2026-10-29: Unknown operations are read as such (Q14), rather than keeping `MoveMember` working without a button: no
  dead code that looks alive; old History entries say "a change this version doesn't know".
