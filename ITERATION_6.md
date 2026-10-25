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

**Waiting costs** (the Money page) read as a choice to make, so "not now" became Refuse. Now: under "Waiting", "Not
refunded yet. Refund one here, or leave it for a later event."; one button, Refund here (was Include); Edit and Refuse
in the row's ⋮, Refuse confirmed ("It won't be refunded at any event. Bob can edit it and submit it again."), Undo kept.

## 6. Milestones and task checklist

### V6-M1 — Share receipts to Kapelusz (§1)

- [x] Share entries for images and PDFs; the text one removed with the Maps place dialog
- [x] "Attach to…": events, then targets; handing files to the forms; attaching with Undo
- [x] API: `/e/` and `/join/` not found without signing in (for §2's CloudFront change)
- [x] Location: picked on a map (§3), the cursor at the end after a lookup

### V6-M2 — Link cards (§2)

- [x] Event pointers, rendering `index.html` with the tags, the icon
- [ ] Terraform applied (CloudFront behaviours, Lambda reads `index.html`)

## 7. Decision log

- 2026-10-25: Ideas not taken: debt reminders shared by hand (not wanted), event links in the settlement summary
  (the event link already gets there), the band's past venues first in Location (rare).
- 2026-10-25: Shared text dropped rather than kept with a use for any text: none worth having.
- 2026-10-25: The map picker uses Google Maps on Android only: free, drawn by Play services (no big library), the
  same map data as the rest; OpenStreetMap would add ~10 MB and has weaker data in Poland.
- 2026-10-25: Link cards name the event although anyone with the link sees them: the name and date say little.
- 2026-10-25: Invite cards stay generic (Q11 changed after review): a band's name would confirm guessed codes.
- 2026-10-25: Guests can't become members any more (§5); duplicate member names stay refused: rows show only names.
