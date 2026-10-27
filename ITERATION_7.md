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
  is, instead of blinking it (and redrawing the band's screens) each time. Faster sync: ITERATION_8.

## 2. Milestones and task checklist

### V7-M1 — Google Play (§1), branch `play`

- [x] The `site` and `play` variants, the updater in `site` only, Support me! and "Update required" per variant
- [x] The play bundle in CI, signed with the upload key; the upload to closed testing when the secret is set
- [x] Listing texts, Data safety and content rating drafts; screenshots of the demo band
- [ ] (User) The app in the Console, the signing key, the first upload, the testers' group, 14 days
- [ ] (Later, in production) Play first on the website; the `site` variant's note

## 3. Decision log

- 2026-10-27: Both channels, the APK dropped later (Q1), rather than Play only at once: nobody has to do anything now.
- 2026-10-27: The existing key as Play's app signing key (Q2): moving between channels stays an update.
- 2026-10-27: Leave band is the account deletion (Q3): built before the grilling; after the last band the server keeps
  only the account id and the sign-out-everywhere time.
- 2026-10-27: Support me! hidden in the Play build only (Q5): a tip link outside Play billing risks a strike on a new
  account.
- 2026-10-27: The play and listing work on one branch (Q14).
