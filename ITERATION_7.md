# Kapelusz — Iteration 7

Started 2026-10-27. Same loop as before: a branch per piece of work; implement, independent review, fix, push, PR,
merge when CI is green.

## 1. V7-M1 — Google Play (grilled 2026-10-27)

Pass the Hat goes to Google Play, which since 2026-10-27 is the only place the Android app comes from (§1.1).

### 1.1 Google Play only (Q1, changed 2026-10-27)

- **First decided:** both channels for a while (the website's APK with its own updater, and Play), the APK dropped
  later. **Changed the same day:** Play only, at once. The one person on the APK reinstalls from Play (or updates over
  it: same signature); everyone else installs from Play directly.
- **One Android build**, without the self-updater, its install permissions, "Automatic updates", the version.json
  update strip, Support me! (Q5) and notifications (Firebase Messaging's `POST_NOTIFICATIONS` is removed). "Update
  required" opens the Play listing. A phone that came from the APK has the updater's leftovers (scheduled checks, its
  notification channel, a downloaded APK) cleaned up at start (`ApkLeftovers`).
- **The website** offers no APK: the web app's install hint links to Google Play (joining the closed test until it is
  public, §1.5). `version.json` is still written
  by every deploy, for the web app's "a new version: reload". The old `/download/kapelusz.apk` is removed by hand.
- **Not on Play yet** (closed test): testers join the testers' Google Group, then the opt-in link; anyone else uses the
  web app meanwhile.

### 1.2 Signing

- **Play App Signing with the existing release key** (Q2): uploaded once through the Console (PEPK). The app from
  Play carries the same certificate as the old APK (an update over it works), and its fingerprint is the one in
  `assetlinks.json`: links, Google sign-in and the Maps key need nothing new.
- **A new upload key** for what CI uploads to Play; a CI secret. Lost or leaked, Google resets it.

### 1.3 Releases

- **CI** (Q6) builds the release bundle (AAB) on every merge to `main`, signed with the upload key, and uploads it to the
  **closed testing** track through the Play Developer API (a Google Cloud service account, its key a GitHub secret).
  Without the secret, the bundle is built and the upload skipped. The very first upload is by hand (the API can't create
  the app's first release).
- **Version codes** (2026-10-27): CI's run number + 10, as the first Play bundle went up by hand as 231 while the live
  version was 225; every later upload must be higher. While the app is still a draft in the Console (the first release in review),
  the variable `PLAY_RELEASE_STATUS=draft`; removed once it is published.
- **Production** is promoted by hand in the Console.
- **Raising the minimum app version** (`Api.MIN_APP_VERSION`, ITERATION_2 §2) waits until the build it requires is live
  on Play too: Play builds come later (review, promotion by hand), and until then Play users would be told to update
  to a build Play doesn't have yet.
- **Closed test first** (Q4): a personal account created after Nov 2023 must run a closed test with at least 12 testers
  opted in for 14 days in a row before applying for production. **15–20 testers** (Q10): the bands' members with Android
  and friends, through a **Google Group**, using the app as usual (Google wants real use). They install from Play over
  their website APK.

### 1.4 Listing

- **Titles** (Q8): "Pass the Hat: Band Money" (en), "Kapelusz: rozliczenia zespołu" (pl). The launcher name stays.
- **Category** (Q9): Finance, with tags for music and bill splitting.
- **Audience** (Q12): 18 and over; the privacy policy says so. Content rating: no to everything.
- **App access** (Q11): any Google account; instructions to create a band.
- **Account deletion** (Q3): Leave band, as built (ITERATION_6 §11a); the policy (§8) says so.
- **Screenshots** (Q13): a demo band, "The Night Owls", made up on the emulator in the user's account and kept; the
  same five screens in English and Polish, in `design/play-store/`.
- **Data safety**, content rating and descriptions: drafted from the code in `design/play-store/`, reviewed by the user.

### 1.5 The website (Q7)

- No APK anywhere (§1.1). The web app's install hint ("Get the app") links to the Play listing once anyone can install
  it there: `PLAY_PUBLIC` in `platform/Updates.kt`, **set to true at the production release**. During the closed test
  (the listing is "not found" for everyone but the testers) it links to joining the test,
  play.google.com/apps/testing/com.jacekgajek.kapelusz (2026-10-28; before, it wasn't shown at all). That works only
  for an account in the testers' Google Group: anyone else sees Play's "not available" and keeps using the web app.

## 1a. The sync icon (2026-10-27)

- A sync round shows "syncing" only while it sends something: the check for news every 30 s keeps the cloud icon as it
  is, instead of blinking it (and redrawing the band's screens) each time. A tap on the icon still shows "syncing" at
  once. Faster sync: §2 (Firebase messages that ask the app to sync).

## 2. V7-M2/M3 — Sync when something changed (grilled 2026-10-27)

Bandmates settling at the same table should see each other's changes within seconds, without the battery cost of
polling often. The server tells the Android app, through Firebase Cloud Messaging, that a band changed; the app then
syncs it as usual. **Messages only trigger syncing: no notifications** (ITERATION_6 §9 stays). Q2–Q12 below are this second
grilling's questions, not §1's.

### 2.1 Which apps (Q2)

- **Android:** Firebase messages.
- **Web:** keeps polling every 30 s, but **pauses in a hidden tab** (§2.6). Web push can't be silent (browsers require
  a visible notification per message), and a live connection (API Gateway WebSockets) would be a new paid service.

### 2.2 What sends a message, to whom (Q3)

- **Triggers:** every write that changes a band for others: a push with accepted operations, joining by invite,
  leaving, deleting the band. Not reads, refused pushes or attachment file uploads (the operation adding the
  attachment already sent one).
- **Recipients:** every registered device of every linked, non-archived member, **except the device that made the
  change** (it sends its installation id with the request, §2.4). A user's other devices do get it.
- **Content:** only the band's id, as an FCM data message: no names, amounts or anything else of the band goes through
  Google. The app syncs through the API as usual.

### 2.3 On the phone (Q4, Q5, Q9, Q12)

- **Normal priority.** An app on screen syncs on a message; in the background or closed the message is **ignored**
  (the app syncs when it comes to the screen anyway). High priority without a visible notification gets downgraded by
  Android.
- **Any of the user's bands:** the open band syncs as usual; another band is fetched quietly (so switching to it shows
  current data), downloaded if it isn't on the phone yet, or let go with the usual dialog when it was deleted or the
  user removed.
- **Fallback polling** while on screen: every **5 minutes** with working Firebase (the registration's answer says
  messages will come: the server has its key, and the user is in a band),
  every **30 s** without (no Google Play services, registration failed), as before. Plus, as before, at once when the
  app comes to the screen and after every local change.
- **No notifications at all:** Firebase Messaging asks for `POST_NOTIFICATIONS`, removed in the manifest (lint is told
  that Firebase's notification display is never reached).
- **Firebase only after sign-in:** automatic start off (`firebase_messaging_auto_init_enabled=false`), Analytics
  deactivated, only `firebase-messaging` added. After sign-in the app turns messaging on and registers its token; on
  sign-out it deletes the token (`deleteToken`) and its registration. In the shared module's Android code. Without Google Play services nothing breaks: 30 s polling.

### 2.4 Devices on the server (Q6)

- Each installation makes a random **installation id** once. `PUT /me/devices/{installationId}` with its FCM token:
  after sign-in, at each start, when Firebase issues a new token (replaces the old one), and after joining or
  creating a first band. `DELETE` on sign-out. The answer, `{"messaging": true|false}`, says whether messages will
  come. A user in no band is not stored (`messaging: false`), so nothing of theirs comes back after Leave band.
- Kept per user in `users/{userId}-devices.json`: installation id, token, last seen. At most **10** per user (an
  eleventh replaces the longest unseen).
- Forgotten: on sign-out (that device), on "Sign out on all devices" (all), after the user's last band
  (ITERATION_6 §11a), when FCM reports a token invalid (404 `UNREGISTERED` or 400 about the token, at once), and when
  unseen for **60 days** (dropped at the user's next registration, or when a message to the band finds it).
- The app sends its installation id with every request (the query parameter `installation`, like `appVersion`), so
  the server skips that device: only the caller's own, not another user's with the same id.
- **Tokens, not topics** (Q6): only current members get messages (anyone knowing a band id could subscribe to a
  topic), removed members stop at once, and Firebase advises tokens for fast delivery to small groups.

### 2.5 Firebase and sending (Q7, Q8)

- **Firebase in the existing Google Cloud project** (`bold-vial-278611`, with the sign-in client, the Maps key and
  `kapelusz-ci`; to be checked). `google-services.json` committed: public identifiers, in every APK anyway.
- **A separate service account `kapelusz-fcm`**, role Firebase Cloud Messaging API Admin only. Its key in the **data
  bucket**, `server/fcm-service-account.json`, next to the session signing key (`server/session-key.json`), put there
  by hand; the Lambda reads it on the first message (not into the SnapStart snapshot). Missing: no messages. (Q7 said
  Parameter Store; changed while building: its SDK added 6 MB to the Lambda, and the bucket already holds the session
  key with the same protection, needing no Terraform.)
- **FCM's HTTP v1 API called directly**, with a self-signed short-lived Google token (cached for its lifetime); no
  Firebase Admin SDK (tens of MB in the Lambda).
- **Sent within the request, in parallel** (HTTP v1 has one call per device), waiting **at most ~1 s** in all;
  failures and timeouts are ignored: the fallback polling catches up. The members' device lists are read in parallel
  first (at most 0.4 s); the Google sign-in has its own 1.5 s, only on an instance's first message an hour (a refused
  one is tried again after a minute). Without the key nothing is read or sent. The sender's own push answers a few
  hundred milliseconds later.

### 2.6 The web app in a hidden tab (Q10)

- On `visibilitychange` to hidden: send what is waiting (one round; on failure the usual backoff until it is out),
  then pause. Visible again: sync at once, then every 30 s. A closed tab keeps unsent changes for the next start, as
  before.

### 2.7 Privacy

- The FCM token and installation id are device identifiers: the privacy policy and the Data safety answers
  (`design/play-store/console-answers.md`) say so (Device or other IDs, app functionality; Firebase Messaging's own
  SDK disclosure).

### 2.8 Battery (estimated, 2026-10-27)

On mobile data with the app open, polling every 30 s keeps the radio active about a third of the time (+0.1–0.2 W,
about 1 % of a battery per hour); every 10 s it never rests (+0.3–0.5 W, about 3 %). Messages ride on Google Play
services' one connection, shared by all apps: the radio wakes only when something changed and every 5 minutes, about
+0.01–0.03 W.

## 2a. Deleting members added by mistake (grilled 2026-10-27)

- **When** (Q3): only a member nothing in the band refers to: no costs, never in an event (so no collected money or
  transfers), never named as who recorded a transfer or collected money or added a participant, not holding the band fund, not in a
  settle-together pair or invitation, not the owner and not yourself (Leave band is for that). Checked by the shared
  rules (`MemberDeletion`), so app and server agree. Anyone else is moved to the former members, as before.
- **A member using the app** (Q1): deleted in one step; the confirmation says it also removes their access. Underneath,
  two changes: Unlink (not undoable) and the deletion.
- **Undo** (Q2): from the snackbar or the history; it brings the member back without the app account (a new invite gives
  access again; invites made before the deletion are cancelled with it). The account joins only by invite. Refused
  when another member has taken the name meanwhile.
- **Where** (Q4): Band members › the member › a red "Delete member" at the bottom, shown only when allowed; "Delete Bob?"
  with what it changes; a snackbar "Deleted Bob" with Undo.
- **The server** (Q5): a push that unlinks someone (a leader's Unlink, or this) is treated as leaving: the band leaves
  their account's list, and after their last band what the server kept for them goes too (ITERATION_6 §11a).
- **Not widened** (Q6): deleting a member who took part by turning them into a guest was considered and left out.

## 2b. Updates from Google Play in the app (2026-10-27)

- Play's own automatic updates usually get there first; for when they don't, the app asks Play (its in-app updates
  library, `com.google.android.play:app-update`) whenever it comes to the screen. A newer version available for this
  phone shows the quiet strip above the bottom bar: "A new version of Pass the Hat is on Google Play · Update"; Update
  downloads it in the background (Play's flexible update), then "The new version is downloaded · Restart" installs it.
- **Update required** (too old for the server) starts Play's full-screen update when Play offers one, else opens the
  listing.
- No notifications and no permissions. Offered only to the app installed from Play, and only once Google's review has
  passed and the release reached the phone: hours after a merge, not at once. A version declined in Play's dialog isn't
  offered again (a newer one is); a download that fails or is cancelled is offered again.

## 2c. Artists see and settle their own transfers (2026-10-27, settling 2026-10-28)

Artists still see none of the band's money. Their Money page shows **To settle** under their payout, with only their
own transfers: who pays them ("Bob → Robert 200 zł"), and whom they hand back to, with that person's BLIK number and the
amount to copy. As members do, they mark each **Paid** or **Received** (or a part of it), with Undo. Before, only the
other side or a lead could, so an artist who paid another artist had to tell someone who then told a lead. This replaces
ITERATION_4 §1.1's "artists can't record transfers": they still hold no money (the Reducer caps what an artist sends at
what they were paid too much). A pair's transfer counts as the artist's own part of it (`Artists.ownTransfers`); money a
partner receives for the pair stays "Kept for you by …" in the payout. The server already sends artists the whole band
(the app hides the rest), so only the app and the shared permission changed.

## 2d. Fewer messages to a leader (2026-10-28)

Who knows something happened records it, instead of telling a leader (a search after §2c; the user picked these four):

1. **Taking back your own transfer.** Whoever recorded a transfer (Paid, Received, Hand over money) may remove it from
   Recorded transfers, e.g. a Paid tapped by mistake after the Undo is gone; leads any, as before. Undo from History of
   a transfer still needs a lead (ITERATION_2 §7.2).
2. **An organiser's fee arriving.** Who it was paid to marks it received (Paid on the awaited fee), and the fund's holder
   for the fund; leads for anyone, as before. Recording an awaited fee stays with leads (ITERATION_4 §2.2). The money
   received stays the lead's record: who received it can't edit or remove it, nor its contract (only collected money
   one recorded oneself is one's own to change).
3. **The treasurer keeps the fund's ledger.** The fund's holder adds, edits and removes its plain entries (since §2e
   only money paid in, e.g. membership fees; money out refunds a cost). Refunding a cost from the fund decides about someone's cost, so it stays with leads, and so do
   entries that refund one.
4. **A forgotten receipt.** A cost's owner adds and renames its files after it is locked (refunded at an event with
   transfers): a file changes no money. Removing one (Undo of adding it too), and changing the cost, stays locked.

## 2e. Who paid into the band fund (grilled 2026-10-28)

- **Entries:** money paid in names who paid it: a member (artists too), or **Outside the band** ("Spoza zespołu", e.g.
  a sponsor). It defaults to whoever adds it. **Money leaves the fund only to refund a cost** (the Reducer refuses a
  plain withdrawal): strings bought from the fund are a member's cost, refunded from it. An entry keeps its direction;
  its payer can change. Old plain withdrawals stay as they are (deleted, not edited; the Summary counts them under Outside the band). A member who paid in can't be deleted (§2a).
- **Adding:** "Paid by" is a dropdown (members, then Outside the band) with a People button beside it: a list to tick
  several people, e.g. this month's membership fee (składka), each then getting their own entry for the same amount
  (the amount is per person; the total is shown). Money out picks a cost waiting for a refund, with a + beside it
  that adds a cost and comes back to refund it. Leads add both; the treasurer (§2d) only money paid in.
- **The Fund tab:** one list, events' money and entries together, by date (an event's before entries on its day),
  newest first or oldest first by a toggle the device remembers (entries on a day in the order added, either way). Entries say "from Bob", "from outside the band" or
  "refund to Bob".
- **Summary:** "Into the fund" and "Out of the fund" open into who paid in (members, Outside the band, From events)
  and whose costs it refunded (and For events, what it added to an event). "To the band fund" stays as it is. Seen by
  everyone who sees the band's numbers (not artists, ITERATION_4 §1.1).

## 2f. Where we played (grilled 2026-10-28)

- **Where:** Summary's last row, "Where we played · 12 places", for the Summary's period; it opens a map (Android) or
  a list (the web, which has no map; each place there opens in Google Maps).
- **Which concerts:** every event of the period already played (date ≤ today), settled or not, archived too, with a
  location; gigs still to come never (tried as grey pins, dropped the same day). An artist sees only theirs.
- **Places:** concerts whose coordinates match to about 10 m (4 decimals) are one marker (Google's red pin). A marker is just a pin;
  tapped, its bubble says the place (the newest concert's location up to its first comma) and "3 concerts", and opens a
  sheet with the full address and the concerts, each opening its event. Fitted to all markers; one place up close. No
  clustering.
- **Coordinates, not in the band:** a location ending in a pin is read as it is. Addresses are looked up by the server
  (`GET /bands/{id}/locations`, any member; only the band's own locations, so nobody has addresses looked up at its
  cost) in Google's Geocoding API, each address once ever: cached in DynamoDB (`art-split-geocode`, keyed by the
  SHA-256 of the normalised text, so no addresses are stored as such). "Not found" is kept 30 days (TTL), then asked
  again; a failed lookup isn't kept. At most 25 new addresses a call; the app asks again while some are pending, and
  keeps the coordinates found on the device. Changing an event's address needs nothing: it is another key.
- **Below the map:** "Finding places…" while lookups run, and "2 concerts not on the map" (Google can't find them),
  listed to open and fix.
- **Privacy policy (§4):** event locations go to Google's Geocoding; only their coordinates are kept.
- **Limits:** a band's own locations only (at most 25 new a call), but no rate limit per user: anyone may create bands
  full of places. Acceptable for now; failed lookups (quota) aren't kept and are asked again.

## 2g. The band fund as a percentage (grilled 2026-10-28)

- **On an event:** "To the fund" is a fixed amount or a **percentage of the profit** (income less cost refunds),
  exactly that share, not a weight among the players: 10% of 5000 zł is 500 zł whoever plays. Rounded **down to whole
  units** of the currency (456.789 → 456), never below 0 (a loss gives the fund nothing). Worked out live, so it follows
  income and refunds; taken off before fixed shares and weights, as the fixed amount is. "From the fund" is always an
  amount. Stored as `Event.fundPercent` (`fundAmount` then 0); `SetFundAmount` carries `percent`, 0 being nothing.
- **The editor:** its own, not the member share one (a share's percentage is a weight; they may part ways in UX later):
  % or zł; the percentage on a slider of 0–50% in 5% steps; a live "= 500 zł of 5000 zł profit". The model takes
  0–100. The event's line says "To band fund (10%)".
- **Band default:** Settings → Band fund → "Default for new events" (leaders, while the fund is on): a percentage, an
  amount or nothing (`Band.fundDefault`, `SetFundDefault`). New events get it in `CreateEvent`, as members' default
  shares; changing it asks "Use it in events not settled yet too?" for the unsettled, unarchived events still on the
  old one. Turning the fund off drops the default and turns percentages into the amounts they came to (one still following
  income would owe a fund that is off). A default sent after the fund was turned off elsewhere is dropped, not the event. Nothing is remembered
  per device (tried in the grilling, dropped for the default).

## 2h. Shares in shares, and as a percentage of the profit (grilled 2026-10-29)

- **Shares, not percent:** a share by weight is shown as a number of shares, 100 being one: "1½ shares" / "1½ udziału",
  "¾ share", "No share"; a weight off the quarters as a decimal ("1.1 shares" / "1,1 udziału"). Only the display: the
  weight is stored as before. A normal share (1) goes unsaid in the member list, as in an event's participants; a
  member's dialog still says it, beside its edit button. "%" is left to what is a real percentage.
- **A percentage of the profit:** a participant (member or guest) or a member's default share may be a percentage
  instead of shares or a fixed amount (`Participant.percent`, `Member.sharePercent`; `AddParticipant`, `AddGuest`,
  `SetFixedShare` and `SetMemberShare` carry `percent`, never with a fixed amount, 0% being no share: a weight of 0).
  It replaces the participant's shares: they get no part of the rest. The weight stays underneath for switching back.
- **Of what:** the profit (income less refunds) **plus what the band fund adds** to the event, the same base for every
  percentage, the fund's own included, so they add up: fund 10% + Alice 50% of 1000 zł leaves 400 zł for the shares. A
  fund topping up the refunds is then as if there were no costs. Rounded down to the grosz; nothing of a loss.
- **The order:** percentages (the fund's and the participants') and fixed amounts come off first, the rest is split by
  shares. The grosze rounding leaves go to the shares; with nobody on shares and the percentages at exactly 100%, to the
  percentage holders, in proportion (ties in party order).
- **Problems, not refusals** (two phones may set percentages at once): over 100% in all is "The percentages add up to
  110%…" (`OVER_PERCENT`, not a loss); money with nobody to go to (percentages under 100% and nobody on shares) is the
  "no shares" problem, reworded.
- **The editor:** "Shares | % | zł" (PL "Udziały | % | zł"). Shares on a slider in quarters up to 2 with marks at 0, ½, 1,
  1½ and 2, and what one share comes to; the percentage on a slider of 0–100% in 5% steps with "Others already take 10%
  of the profit", in red past 100%. Payout lines and the shared text say "(50% of profit)".

## 2i. Bank accounts, and where to pay into the band fund (grilled 2026-10-29)

- **A member's bank account** (`Member.bankAccount`, `SetBankAccount`), beside their BLIK number and kept exactly like
  it (ITERATION_5 §8): set only by the member, from artists up, in Settings ("Your bank account"); not in the history
  (nor in the members' snapshots of other changes), not undoable, removed by unlinking, kept by archiving; shown only
  to who pays that transfer.
- **What is taken:** an IBAN, or a Polish NRB of 26 digits, checked with the IBAN checksum (mod 97), so a mistyped digit
  is caught. Spaces, dashes and invisible marks dropped, letters capitalised. A Polish one is stored and copied as its
  26 digits (as Polish bank apps take it, `PL` dropped) and shown `61 1090 1014 0000 0712 1981 2874`; any other with its
  country code, in fours.
- **The band fund's own details** (`Band.fundBlikPhone`, `Band.fundBankAccount`; `SetFundBlikPhone`,
  `SetFundBankAccount`): set by its holder and leads, on the Band fund tab and in Settings › Band fund. They are the
  band's, so in the history and undoable (undo now compares and restores band settings one by one, so setting them
  doesn't block undoing a rename). Only while the fund is on; turning it off forgets them (the next holder keeps the
  money elsewhere); choosing another holder keeps them, for the new one to change.
- **Everyone sees them** on the Band fund tab, in a "Pay into the fund" card under the balance, each with a copy button
  (its holder and leads also an edit button), once something is set: nothing there before, for leaders neither (no
  "Not set" rows; changed 2026-10-29). They are added in Settings › Band fund, which lists both, set or not.
- **A transfer to the fund** shows only the fund's own details, **never its holder's** personal BLIK or account
  (changed from ITERATION_5 §8); without any, nothing. So does "Hand over money" to the fund.
- **Beside a transfer** (an event's To settle, the Settle screen, an artist's transfers): the phone icon for BLIK and a
  bank icon for accounts, each when a recipient gave one, each opening its own sheet (for a pair, in the BLIK order).
  "Hand over money" shows the recipient's account under their BLIK number.
- **Not in:** shared settlement texts; a transfer title.

## 3. Milestones and task checklist

### V7-M1 — Google Play (§1), branch `play`

- [x] The `site` and `play` variants, the updater in `site` only, Support me! and "Update required" per variant
- [x] The play bundle in CI, signed with the upload key; the upload to closed testing when the secret is set
- [x] Listing texts, Data safety and content rating drafts; screenshots of the demo band
- [ ] (User) The app in the Console, the signing key, the first upload, the testers' group, 14 days
- [x] Google Play only (§1.1, 2026-10-27): one Android build, no APK on the website, the install hint to Play
- [ ] (User) Remove the old `/download/kapelusz.apk` from the web bucket
- [ ] At the production release: `PLAY_PUBLIC = true` (the web links to the listing instead of joining the test)

Old apps need nothing for V7-M2/M3: they don't register and keep polling every 30 s. No minimum version.

### V7-M2 — Sync: server (§2.2, §2.4, §2.5)

- [ ] (User) Firebase in the project, the `kapelusz-fcm` service account and key; the key in the data bucket
  (`aws s3 cp kapelusz-fcm.json s3://jacekgajek-art-split-data/server/fcm-service-account.json`)
- [x] Device endpoints, the registry and its pruning (with tests)
- [x] The FCM sender, messages after pushes, joins, leaves and deletion, the sender excluded (with tests)

### V7-M3 — Sync: apps (§2.3, §2.6, §2.7)

- [x] Android: Firebase after sign-in, the token's registration and removal, syncing on a message, 5 min / 30 s
- [x] Web: pausing in a hidden tab
- [x] Privacy policy and Data safety
- [x] Tested on the emulator (2026-10-27): a change from another device reached it, which fetched the band about 1 s
  after the change

### Deleting members added by mistake (§2a)

- [x] The shared rule and the server's clean-up after an unlink (with tests); Delete member in Band members, Undo

### Artists see and settle their own transfers (§2c)

- [x] To settle on the artist's Money page, their own transfers only, with BLIK when they pay
- [x] Paid / Received / Part on them; RecordTransfer of one's own from artists up

### Fewer messages to a leader (§2d)

- [x] Removing one's own transfer; receiving an awaited fee paid to oneself; the treasurer's fund entries; receipts on a locked cost

### Who paid into the band fund (§2e)

- [x] Payer on entries, money out only for a cost; several people at once; one sorted list; the Summary breakdown

### Where we played (§2f)

- [x] The server's cached lookup (DynamoDB, Terraform), the Summary row, the map (Android) and the list (web)
- [ ] Seen with real lookups once deployed

### The band fund as a percentage (§2g)

- [x] Percentage of the profit on events, the fund editor, the band default with apply-to-unsettled

### Shares in shares, and as a percentage (§2h)

- [x] Shares shown as shares, a percentage of the profit for participants and members' defaults, the three-way editor

### Bank accounts, and the band fund's payment details (§2i)

- [x] Members' accounts, the fund's BLIK and account (its tab and settings), both icons beside transfers

### Updates from Google Play in the app (§2b)

- [x] The strip (available, downloading, downloaded), Update required's full-screen update
- [ ] Seen with a real update from Play (needs a Play-installed build and a newer release)

## 4. Decision log

- 2026-10-27: Both channels, the APK dropped later (Q1), rather than Play only at once: nobody has to do anything now.
- 2026-10-27: The existing key as Play's app signing key (Q2): moving between channels stays an update.
- 2026-10-27: Leave band is the account deletion (Q3): built before the grilling; after the last band the server keeps
  only the account id and the sign-out-everywhere time.
- 2026-10-27: Support me! hidden in the Play build only (Q5): a tip link outside Play billing risks a strike on a new
  account.
- 2026-10-27: The play and listing work on one branch (Q14).
- 2026-10-27: Firebase messages trigger syncing only, no notifications: the earlier "no FCM" (ITERATION_2) was about
  notifications, which stay gone (ITERATION_6 §9).
- 2026-10-27: The 10 s polling tried first was dropped (about 3 % battery an hour on mobile data); its icon fix kept
  (§1a).
- 2026-10-27: Tokens rather than topics (§2.4); within the request rather than an asynchronous Lambda or a queue
  (§2.5); no background syncing on a message (§2.3).
- 2026-10-27: The FCM key in the data bucket rather than Parameter Store (§2.5): 200 KB instead of 6 MB in the
  Lambda, no Terraform, the session key's precedent.
- 2026-10-27: Google Play only, at once (Q1 changed): one APK user, who reinstalls; no second build to maintain.
- 2026-10-27 (bug): an artist paid their share before a refund was accepted at the event owed the difference back, and
  "Settle all" refused it ("artists don't hold money"). An artist now pays back what they were paid too much, never
  more; a single transfer from an artist is held to the same (it wasn't checked at all).
- 2026-10-27: Artists see their own transfers (§2c), read only; nobody else's, so the rest of the band's money stays
  hidden from them (ITERATION_4 §1.1).
- 2026-10-28: Artists record their own transfers (§2c): an artist paying another artist otherwise needed two people to
  pass the word to a lead.
- 2026-10-28: Fewer messages to a leader (§2d): four things the person involved knows are theirs to record. Refunding a
  cost from the fund, recording an awaited fee and undoing from History stay with leads.
- 2026-10-28: Money leaves the band fund only to refund a cost (§2e), rather than also as plain withdrawals: every
  withdrawal has an owner, so the Summary's breakdown always adds up.
- 2026-10-28: Several payers through a People list beside the dropdown (§2e), rather than chips: most entries are one
  person's.
- 2026-10-28: The web links to joining the closed test (§1.5) rather than showing no app link until production.
- 2026-10-28: The map's coordinates in a server cache (DynamoDB), not in the band's events (§2f): events stay as they
  are, nothing to clear when an address changes; DynamoDB rather than S3, a batch read instead of a file per address.
- 2026-10-28: The server looks up only the band's own locations (§2f), rather than addresses the app sends: no
  lookups at the band's cost for anything else.
- 2026-10-28: The fund's percentage is of the profit, exactly (§2g), not a weight beside the players': it means what it
  says whoever plays. Rounded down to whole units: a round sum to hand over, never more than the percentage.
- 2026-10-28: A band default for the fund (§2g) instead of remembering the last value per device: shared by the band,
  like members' default shares.
- 2026-10-29: Shares are shown as shares (§2h), "1½", not "150%": a member's "%" and the fund's meant different things.
- 2026-10-29: Participants' percentages are of the profit plus the fund's top-up (§2h), the same base as the fund's
  percentage, so all percentages add up; a percentage replaces the shares rather than coming on top of them.
- 2026-10-29: Over 100% and money nobody gets are problems on the event (§2h), not refusals: two phones may set
  percentages at once, and a refusal would drop one of the changes unseen.
- 2026-10-29: Transfers to the band fund show only the fund's own payment details (§2i), never its holder's: whose
  money is whose stays clear. Bands that set none see no number for the fund until they do.
- 2026-10-29: The fund's details are the band's (§2i): in the history and undoable, unlike a member's own.
