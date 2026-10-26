# Kapelusz — Iteration 6

Started 2026-10-25. Same loop as before: a PR per milestone; branch, implement, review, fix, push, merge, smoke test.
**Before implementing a batch: report the plan and wait for approval.**

Features that don't add screens or settings, but act on what is already there.

## 1. V6-M1 — Share receipts to Kapelusz (grilled 2026-10-25)

- **Android only.** Photos and PDFs shared to Kapelusz (one or several) go into a cost, received money or the event.
  Kapelusz appears in the share sheet for images and PDFs only. The web app takes no shares (installed web apps can,
  but only in Chrome on Android, where the app is).
- **No more shared text:** Android can't filter shares by what the text says, so Kapelusz used to appear for every
  text. The Maps place share is gone; a link copied in Maps (Share › Copy link) is pasted into Location instead
  (ITERATION_5 §9.1).
- **"Attach to…":** the events of all the user's bands where they can attach something; not archived ones.
  - Order: today's and past events, newest first (receipts come after the spending), then under "Upcoming" the
    upcoming ones, soonest first. The band's name on the second line when there are several bands.
  - Not signed in: after signing in. No such event: "There is no event you can attach files to."
- **Then, in that event:** the shared files (photos as thumbnails, PDFs by name) and:
  - **New cost** / **New received money:** the form opens with the files attached, waiting for Save.
  - **The event's files** (leaders), then under "Add to a cost" the event's **costs** (not those paid back in full) and
    under "Add to received money" its **received money** the user may attach files to: attached at once; the event
    opens at that section, with Undo in the snackbar (which closes by itself, or with its cross).
- **Look** (2026-10-25, after trying it): a bottom sheet. On top the event (or "Attach to…"), with Back, and the files
  as small squares on the right. Events with a date block (day, month under it) and their location; new entries as
  three tonal tiles side by side (icon over the name); existing costs and received money as rows like on the Money
  page: an icon (a cost's category: car, bed, speaker, cutlery, receipt; money: banknotes), the name, the payer or
  source under it, the amount, and a clip. Headings in the primary colour.
- **Limits:** files that can't be used (a PDF over 10 MB, not a photo or PDF, unreadable) are listed as left out, with
  the reason. A target without room for all the files (5 per cost or received money, 10 on the event) is shown
  disabled with "Room for N more"; nothing is dropped silently.

## 2. V6-M2 — Link cards (grilled 2026-10-25)

- **What:** event links (`/e/EVENT_ID`) shown as cards in WhatsApp, Messenger and Slack, via `og:` tags in the page the link reader fetches. Still plain links; only what the recipient sees changes.
- **Event card:** the event's name; description its date only (`14.11.2026`), language free; nothing else from the
  event. Anyone with the link sees it.
- **Invite links** (`/join/CODE`) get the generic card: one naming the band would let anyone check, without signing
  in and past the app's limit of 10 guesses an hour, whether a code is valid (review, 2026-10-25).
- **Every card:** `og:site_name` "Kapelusz", no image (2026-10-25: WhatsApp showed the 512 px icon over a third of
  the screen). Unknown or deleted: the generic card ("Kapelusz · Pass the Hat"), which `index.html` has itself (the
  home page too).
- **How:** CloudFront sends `/e/*` to the API (5 minutes' cache, 1 minute for a generic card), which returns the web app's
  `index.html` (read from its bucket, kept a minute) with the card's title and description in place of the generic
  ones. Without the page, a 404: CloudFront then shows the plain page (it can't for a 401, so V6-M1 made the API answer
  those paths before signing in).
- **Finding the event:** a pointer `events/{eventId}.json` → band, written when a push creates an event (or an undo
  brings one back), removed with the event or its band. The first band keeps an id: another band creating an event
  with the same id (anyone who saw the link could) doesn't take over its card. Events from before have no pointer and
  get the generic card.
- **API errors** (500, 502–504) at CloudFront also give the plain page, cached briefly.

## 3. Location: a place picked on a map, and the cursor (2026-10-25, in V6-M1)

- **Pin button** in Location (Android only; Compose for the web has no map, and a JS map over the page isn't worth it):
  a full-screen Google map. A tap puts the pin; a place on the map (a shop, a forester's lodge) gives its name too. A
  layers button switches to satellite with roads and names, to find a clearing in a forest. "Use this place" puts the
  coordinates in Location, as for a dropped pin pasted from Maps (ITERATION_5 §9.1): `51.109120, 17.032570`, or
  `Leśniczówka, 51.109120, 17.032570`. Navigate takes them as they are.
- **Opens** at the pin Location ends with, else over Poland. No "my location" (it would ask for a permission).
- **Key:** a Google Maps key for Android (Maps SDK for Android only), restricted to the package and the release and
  debug certificates' SHA-1, in the GitHub secret `GOOGLE_MAPS_ANDROID_KEY`. Without one (local builds), no button.
- **Fix:** a place picked from the suggestions, its full address, or a pasted link left the cursor where the typing
  stopped; it goes to the end.

## 4. Cost category icons everywhere (grilled 2026-10-25)

- The category's icon (§1: car, bed, speaker, cutlery, receipt) in a 40 dp tinted circle, as tall as the row's two
  lines, on every cost row: My costs; the event's Money page (refunds, waiting costs, the refund dialog's header); an
  artist's own costs in the event.
- The cost form's category chips: a small icon before the name, kept when selected (the chip's fill says it is).
- Not on band fund entries: there a cost is part of the entry's text, not a row.

## 5. Guests stay guests; former members are welcomed back (2026-10-25)

- A guest is a handshake in place of an avatar, and a tap on one does nothing: making a guest a band member (with a
  free name when theirs is a former member's) was more confusion than help. `PromoteGuest` is gone.
- Members without the app keep the crossed eye and the tap that invites them.
- Names stay unique among members, archived ones too (ITERATION_5 §12). A name of someone who used to be in the band,
  typed in Add member or Add guest: "Bob used to be in the band." with Welcome back, which restores them (and, from
  Add guest, adds them to the event with their usual share). Add guest can still add a guest by that name; Add member
  needs another name.

**Cost → event:** in a cost's form, each "to be refunded at / refunded at" event line opens that event at its Money
page (the refunds).

**Refund amount button:** on the Money page's cost rows, the button that sets how much of the cost is refunded in this
event is sliders (Tune), not the pencil: it doesn't edit the cost. Read out as "Amount refunded here". In its dialog, a tap on the cost (icon, who, what, date) opens the cost's
form (a pencil says so); Back returns to the event. Above the amount: "How much of this cost should be refunded at
this event?"

**Waiting costs** (the Money page) read as a choice to make. Now: under "Waiting", "Not
refunded yet. Refund one here, or leave it for a later event."; one button, Refund here (was Include); a pencil opens the cost's form.

**Refuse is gone** (with the Refused status, resubmitting, and the refused-cost dialog in My costs and an artist's
costs): whether a cost is paid back is talked over in person, not a formal step in the app. A cost no one should repay
is deleted or edited by its owner or a lead; one left alone simply waits.

## 6. V6-M3 — Searching events (grilled 2026-10-26)

- **Where:** a search icon left of the filter chips on the Events screen. A tap turns the row into a search box across
  the full width (the chips hidden), with the keyboard open. The cards above (band hint, settle-together invitation,
  Continue, Upcoming) are hidden while searching, at once, without animation; the list doesn't scroll by itself (the
  row is at the top anyway).
- **What it looks in:** an event's name and Location only. Not the notes: a setlist in every event would match a song
  name everywhere. Not people's names (nearly every event) nor the date (the list is by date already).
- **Which events:** all of them, ignoring the hidden filters (an old gig is often archived, or one you didn't play);
  archived ones show their usual status chip. Artists still find only their own events (privacy, not a filter). Until
  something is typed (spaces don't count), the list stays as it was, filters included. Results keep the list's order.
- **Matching:** each typed word must appear somewhere in the name or Location, in any order ("wesele kraków"),
  ignoring case and Polish letters ("lodz" finds "Łódź"): a small table for ą ć ę ł ń ó ś ź ż and common Latin
  letters (é, ü, ö…), as Kotlin's common library has no `Normalizer`. No highlighting of the match: with letters
  folded, mapping positions back to the original text isn't worth it.
- **Closing:** the X clears and closes at once; Back (Esc on the web) closes the search before leaving the screen.
  The query stays when an event is opened and closed, and across tabs (tabs keep their state); not across restarts.
- **Performance:** today `eventSummaries` settles every event on each redraw of the Events screen; typing would redraw
  on every key.
  - The normal list's summaries are remembered (`band`, `me`, filters): settled again only when the band changes.
  - Opening the search starts a job (`Dispatchers.Default`) building, once, the summaries of every event the user may
    see and their folded name + Location. Typed before it's done: a small spinner in the box, results when ready.
  - The band changes while searching (a sync, an edit): built again; the old results stay until the new ones are
    ready. Closing the search drops it.
  - Each key: the typed words folded the same way, plain `contains` over the prepared texts (1,000 events: well under
    a millisecond). No debounce.
  - The web build has one thread: the job runs between frames there and the spinner may stop for that moment.
- **Wording:** placeholder "Search by name or place" / "Szukaj po nazwie lub miejscu"; nothing found: "No events
  match "kraków wesele"." / "Żadne wydarzenie nie pasuje do „kraków wesele”." (the query repeated: a typo shows);
  the icon read out as "Search events" / "Szukaj wydarzeń", the X as "Close search" / "Zamknij wyszukiwanie".

## 7. V6-M4 — Summary of a period (grilled 2026-10-26)

**Where:** Summary ("Podsumowanie") in the cog menu, next to History. The menu's items get icons for easier hunting:
Band members (ManageAccounts), Summary (BarChart), History, Settings. Title "Summary", the band's name below.

**Period:** chips scrolling sideways, `Last 12 months` · `This year` · `Last year` · `All time` · `Custom…`.
- Presets move with time: the 12 months up to today; 1 January to today; the whole previous year.
- Custom… opens Material's date range picker; kept as fixed dates, the chip reads them ("1 Sep 2025 – 31 Aug 2026").
  A band counting from September picks it once.
- Remembered per band on the device, next to the filters; Last 12 months at first.

**Blocks, in this order:**
1. **You:** Earned (your shares at settled events), Refunded (payments of your costs), Waiting (your costs not fully
   refunded, as of today, whatever the period). Hidden when all are zero (most viewers).
2. **Band:** settled events (count); Collected (awaited fees count once the event is settled), and under it the parts
   that add up to it (2026-10-26, after "the numbers don't add up"): Paid to the band (all shares: everyone's Earned),
   Cost refunds (paid at those events), To the band fund (its part of them; negative: the fund covered some; shown with
   a fund). Fund: in (its cut at events, deposits), out (covering
   events, withdrawals, refunds from it), and the balance at the period's end, hidden without a fund. The fund is the
   money actually there, as on its page (moved by events, on the event's date, settled or not), so the balance
   matches. No "profit": the money is shared out completely.
3. **Costs by category:** Total (costs fully refunded in the period, from events or the fund), then a row per category
   (icon, total), largest first; none: Other.
4. **Members:** anyone with a number in the period, by Earned, highest first, you among them: Earned and Refunded.
   Each of a pair settling together on their own row (the settlement keeps them apart). A member's avatar, or the
   crossed eye: a tap invites them, as for an event's participants (only for those who may invite). Then one "Guests"
   row, with the handshake: guests are per event, nothing joins them. Former members last, apart under a smaller
   "Former members" ("Dawni członkowie") within the block, without the tap (2026-10-26).

**Which money, on which date:**
- Only settled events count: the money is final, a closed period never changes. Events in the period with money,
  not settled yet: "Not settled yet, so not counted: Wesele Ani, Juwenalia." (three named, then "and 2 more"; no
  count, which Polish would need plural forms for). Drafts aren't named: nothing in them to count. An artist's line
  names only their events.
- An event's money (collected, shares, the fund's part, refunds paid there): the event's date.
- Fund entries, refunds from the fund too: the entry's date.
- Refunded (You, Members): each payment on its own date, so part-refunds show as they're paid.
- Costs (Band, categories): a cost counts once fully refunded, on the date that happened (the latest of its refunds'
  dates); waiting and partly refunded costs not at all. So the Refunded rows needn't add up to Costs.
- Nothing in the period: "Nothing settled in this period." (the unsettled line still shows).

**Who sees what:** leaders, members and viewers all four blocks; artists only You (they see only their own money
anywhere).

**Nothing else is tappable:** no drilling into numbers until the first version shows which ones people want.

**Offline, and speed:** all from the band on the device. Settling every event is the costly part: computed in the
background when the screen opens (a spinner meanwhile), again when the band changes (the old numbers stay until then).

**Later:** export (CSV) of the period; a band-wide season start, if remembering a custom range is not enough.

## 8. Band fund fixes and the invite's QR code (2026-10-26, from testing on the emulator)

- **Summary's fund:** a transfer to or from the fund counts on the day it was recorded, not on its gig's date (a
  gig's part may be handed over before the gig); fees paid straight to the fund on the gig's date. "In the fund at the
  period's end" now matches the fund's page (it showed −80 against 20 after an early hand-over).
- **A refund from the fund** needs no typed description: picking the cost fills it in (its description, else its
  category, else "Cost"). On the fund's page the entry says whose cost it was: "26 Oct 2026 · refund to Bob"
  ("zwrot: Bob": Polish names aren't declined by the app).
- **The treasurer's line** in a settlement no longer counts the fund's part as handed over: "hands over 450.00" with
  "keeps 100.00 for the fund" under it (only the second when nothing else leaves their pocket).
- **Invite QR code:** the member's invite card shows a QR code of the join link instead of the link's text (Share and
  Copy link carry the link). Black on white whatever the theme. Encoded by qrcode-kotlin (no Compose of its own) and
  drawn with a Canvas: QRose would have pulled a newer Compose than the app's and compileSdk 37.

## 9. No notifications; the calendar instead (2026-10-26)

Testing on the emulator and Android's documentation showed the app's own notifications can't be relied on: Samsung
puts an app not opened for 3 days to sleep ("alarms will not work anymore", dontkillmyapp.com), Android 13+ moves one
not used for 8 days to the restricted bucket (one alarm a day, jobs once a day), and transfer notifications came only
with the 4-hourly background sync. Members open a band app rarely, so exactly they would miss them; push (Firebase)
wasn't worth it, and a bank's own notification already says money arrived.

- **Removed:** gig reminders, debt reminders, notifications of transfers others recorded, their prompts and settings,
  the secret menu's reminder checks, the periodic background sync. Kept: sending unsent changes once the app leaves
  the screen ("sync soon"), and the updater's own "ready to install" notification (without the permission, the app's
  strip).
- **On update, once:** the old periodic jobs are cancelled, the alarms too (Android 14+; older ones find no receiver),
  the three notification channels deleted (with their notifications), and the reminder and transfer logs.
- **Settings:** "Notifications and calendar" is now "Calendar subscription", on Android as on the web.
- **Calendar card:** the steps, as Google Calendar subscribes only on a computer and the phone app shows the calendar
  only once its sync is on: "Add it on a computer: in Google Calendar (calendar.google.com) choose Other calendars › +
  › From URL and paste the link. Then on your phone, in the Google Calendar app, open Settings, tap the new calendar
  and turn on Sync." Then "How to add it: Google's help", support.google.com/calendar/answer/37100 in the app's
  language.

## 10. The calendar on the app's domain; scanning an invite (2026-10-26)

- **Calendar link:** `https://kapelusz.jacekgajek.com/calendar/<secret>.ics` instead of the API's own address
  (`…execute-api…amazonaws.com`). CloudFront passes `/calendar/*` to the API like `/e/*`, cached up to 5 minutes (Google
  refreshes every few hours anyway). Links given out before keep working on the API's address. Needs the Terraform
  change applied before the server gives out the new links. Like every path, a feed that's gone (a reset link) gets
  the web app's page from CloudFront's error pages rather than a 404: the calendar app just can't read it.
- **Join band, scan:** a QR icon in the invite code field (Android) opens Google Play services' code scanner (its own
  camera screen: no camera permission; "Scanned by Google on behalf of Pass the Hat"). A join link's code, or a bare
  code, fills the field and is checked at once; anything else: "That QR code isn't a Pass the Hat invite." Cancelled:
  back to the dialog. Not on the web (a phone's camera opens the invite link anyway).

## 11. Milestones and task checklist

### V6-M1 — Share receipts to Kapelusz (§1)

- [x] Share entries for images and PDFs; the text one removed with the Maps place dialog
- [x] "Attach to…": events, then targets; handing files to the forms; attaching with Undo
- [x] API: `/e/` and `/join/` not found without signing in (for §2's CloudFront change)
- [x] Location: picked on a map (§3), the cursor at the end after a lookup

### V6-M2 — Link cards (§2)

- [x] Event pointers, rendering `index.html` with the tags, the icon
- [ ] Terraform applied (CloudFront behaviours, Lambda reads `index.html`)

### V6-M3 — Searching events (§6)

- [x] Remembered summaries; folding and matching (with tests)
- [x] The search row, the background build, closing and Back

### V6-M4 — Summary of a period (§7)

- [x] Icons in the cog menu; Summary in it
- [x] The numbers per period (with tests)
- [x] The screen: period chips with the custom range, the four blocks, who sees what

## 12. Decision log

- 2026-10-25: Ideas not taken: debt reminders shared by hand (not wanted), event links in the settlement summary
  (the event link already gets there), the band's past venues first in Location (rare).
- 2026-10-25: Shared text dropped rather than kept with a use for any text: none worth having.
- 2026-10-25: The map picker uses Google Maps on Android only: free, drawn by Play services (no big library), the
  same map data as the rest; OpenStreetMap would add ~10 MB and has weaker data in Poland.
- 2026-10-25: Link cards name the event although anyone with the link sees them: the name and date say little.
- 2026-10-25: Invite cards stay generic (Q11 changed after review): a band's name would confirm guessed codes.
- 2026-10-25: Guests can't become members any more (§5); duplicate member names stay refused: rows show only names.
- 2026-10-25: Refusing costs removed (§5): a conversation, not a procedure; it also simplifies My costs.
- 2026-10-26: Search looks only in names and Location (§6): notes hold setlists, people's names are in nearly every
  event. It ignores the filters it hides, and hides the cards: they would push the results under the keyboard.
- 2026-10-26: The search's summaries are built in the background when it opens, not kept up to date all the time:
  settling every event is the costly part, and most visits never search.
- 2026-10-26: The summary lives in the cog menu, not a fifth tab: labels would have to go, and it's opened a few times
  a year. A chosen range, not calendar years: bands count seasons from September too.
- 2026-10-26: The summary counts only settled events, and costs once fully refunded: final money, so a closed period
  never changes. Refunded counts each payment on its date instead: what a member actually got back then.
- 2026-10-26: The invite's QR code is drawn by the app from a plain encoder rather than a Compose QR library: the
  library needed Compose UI 1.12 (compileSdk 37) while the app is on Compose Multiplatform 1.11.
- 2026-10-26: All notifications removed (§9): unreliable for an app opened rarely (Samsung's sleeping apps, Android's
  restricted bucket), push not worth it. Gigs reach phones through the calendar subscription, whose reminders work.
- 2026-10-26: Scanning invites uses Google Play services' code scanner rather than a camera library: no camera
  permission, no scanning UI to build; phones without Play services type the code.
