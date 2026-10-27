# Kapelusz — Iteration 7

Started 2026-10-27. Same loop as before: a branch per piece of work; implement, independent review, fix, push, PR,
merge when CI is green.

## 1. V7-M1 — Google Play (grilled 2026-10-27)

Pass the Hat goes to Google Play while the website's APK keeps working for everyone who has it.

### 1.1 Two channels, the APK later dropped

- **Both for now** (Q1): the `androidApp` module has two variants, `site` (the APK on the website, with its own
  updater, ITERATION_2 §1) and `play` (for Google Play). Same application id, same version code (CI's run number + 10),
  so a phone moves from one to the other as an ordinary update, without uninstalling or losing anything.
- **Later**, when (by judgement, or `version.json` requests in CloudFront) hardly anyone is left on the APK, the `site`
  variant is deleted.
- **The `play` variant** has no self-updater (code, `REQUEST_INSTALL_PACKAGES`, `UPDATE_PACKAGES_WITHOUT_USER_ACTION`,
  `POST_NOTIFICATIONS`, the update worker and receiver, "Automatic updates" in Settings, the version.json update strip)
  and no **Support me!** (Q5: Play's payments policy and a tip link). "Update required" opens the Play listing. The web
  app and the `site` APK keep Support me!.
- The updater's code lives in the `site` variant's own sources, so the Play bundle doesn't contain it (WorkManager's
  keep rules would otherwise keep the worker, and with it the installer, in a build that never calls them).

### 1.2 Signing

- **Play App Signing with the existing release key** (Q2): uploaded once through the Console (PEPK). Play and site
  builds carry the same signature: links (`assetlinks.json`), Google sign-in and the Maps key need nothing new.
- **A new upload key** for what CI uploads to Play; a CI secret. Lost or leaked, Google resets it.

### 1.3 Releases

- **CI** (Q6) builds the `play` bundle (AAB) on every merge to `main`, signed with the upload key, and uploads it to the
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

- During the closed test nothing changes: the website offers the APK; testers get the opt-in link directly.
- In production: a "Get it on Google Play" badge first on the website and in the web app's install hint, the APK as a
  smaller link; the `site` variant shows once "Kapelusz is now on Google Play", with a button to the listing.

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

- **Android** (both variants, ITERATION_7 §2.1): Firebase messages.
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
- **Fallback polling** while on screen: every **5 minutes** with working Firebase (the server confirmed the token),
  every **30 s** without (no Google Play services, registration failed), as before. Plus, as before, at once when the
  app comes to the screen and after every local change.
- **Firebase only after sign-in:** automatic start off (`firebase_messaging_auto_init_enabled=false`), Analytics
  deactivated, only `firebase-messaging` added. After sign-in the app turns messaging on and registers its token; on
  sign-out it deletes the token (`deleteToken`) and its registration. In the shared module's Android code, for both
  variants. Without Google Play services nothing breaks: 30 s polling.

### 2.4 Devices on the server (Q6)

- Each installation makes a random **installation id** once. `PUT /me/devices/{installationId}` with its FCM token:
  after sign-in, at each start, and when Firebase issues a new token (replaces the old one). `DELETE` on sign-out.
- Kept per user in `users/{userId}-devices.json`: installation id, token, last seen. At most **10** per user (an
  eleventh replaces the longest unseen).
- Forgotten: on sign-out (that device), on "Sign out on all devices" (all), after the user's last band
  (ITERATION_6 §11a), when FCM reports a token invalid (`UNREGISTERED`, at once), and when unseen for **60 days**.
- The app sends its installation id with pushes (a header), so the server skips that device.
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
  failures and timeouts are ignored: the fallback polling catches up. The sender's own push answers ~100–300 ms later.

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

## 3. Milestones and task checklist

### V7-M1 — Google Play (§1), branch `play`

- [x] The `site` and `play` variants, the updater in `site` only, Support me! and "Update required" per variant
- [x] The play bundle in CI, signed with the upload key; the upload to closed testing when the secret is set
- [x] Listing texts, Data safety and content rating drafts; screenshots of the demo band
- [ ] (User) The app in the Console, the signing key, the first upload, the testers' group, 14 days
- [ ] (Later, in production) Play first on the website; the `site` variant's note

Old apps need nothing for V7-M2/M3: they don't register and keep polling every 30 s. No minimum version.

### V7-M2 — Sync: server (§2.2, §2.4, §2.5)

- [ ] (User) Firebase in the project, the `kapelusz-fcm` service account and key; the key in the data bucket
  (`aws s3 cp kapelusz-fcm.json s3://jacekgajek-art-split-data/server/fcm-service-account.json`)
- [x] Device endpoints, the registry and its pruning (with tests)
- [x] The FCM sender, messages after pushes, joins, leaves and deletion, the sender excluded (with tests)

### V7-M3 — Sync: apps (§2.3, §2.6, §2.7)

- [ ] Android: Firebase after sign-in, the token's registration and removal, syncing on a message, 5 min / 30 s
- [ ] Web: pausing in a hidden tab
- [ ] Privacy policy and Data safety; tested on the emulator against the web app

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
