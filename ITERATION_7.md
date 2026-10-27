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
- **The website** offers no APK: the web app's install hint links to the Play listing. `version.json` is still written
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

- No APK anywhere (§1.1). The web app's install hint ("Get the app") links to the Play listing, shown only once anyone
  can install it there: `PLAY_PUBLIC` in `platform/Updates.kt`, false during the closed test (the listing is "not
  found" for everyone but the testers), **set to true at the production release**.

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
  transfers), never named as who recorded a transfer or added a participant, not holding the band fund, not in a
  settle-together pair or invitation, not the owner and not yourself (Leave band is for that). Checked by the shared
  rules (`MemberDeletion`), so app and server agree. Anyone else is moved to the former members, as before.
- **A member using the app** (Q1): deleted in one step; the confirmation says it also removes their access. Underneath,
  two changes: Unlink (not undoable) and the deletion.
- **Undo** (Q2): from the snackbar or the history; it brings the member back without the app account (a new invite gives
  access again). The account joins only by invite.
- **Where** (Q4): Band members › the member › a red "Delete member" at the bottom, shown only when allowed; "Delete Bob?"
  with what it changes; a snackbar "Deleted Bob" with Undo.
- **The server** (Q5): a push that unlinks someone (a leader's Unlink, or this) is treated as leaving: the band leaves
  their account's list, and after their last band what the server kept for them goes too (ITERATION_6 §11a).
- **Not widened** (Q6): deleting a member who took part by turning them into a guest was considered and left out.

## 3. Milestones and task checklist

### V7-M1 — Google Play (§1), branch `play`

- [x] The `site` and `play` variants, the updater in `site` only, Support me! and "Update required" per variant
- [x] The play bundle in CI, signed with the upload key; the upload to closed testing when the secret is set
- [x] Listing texts, Data safety and content rating drafts; screenshots of the demo band
- [ ] (User) The app in the Console, the signing key, the first upload, the testers' group, 14 days
- [x] Google Play only (§1.1, 2026-10-27): one Android build, no APK on the website, the install hint to Play
- [ ] (User) Remove the old `/download/kapelusz.apk` from the web bucket
- [ ] At the production release: `PLAY_PUBLIC = true` (the web suggests the app on Play again)

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
