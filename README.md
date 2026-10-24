# Kapelusz (Pass the Hat)

"Kapelusz" is Polish for hat, and starts like *kapela*, a band. The app is called Kapelusz in Polish and
Pass the Hat in English: the hat that goes round after a gig.

Settles a band's money after gigs: who collected what, who paid which costs, what goes to the band fund, and who
hands over how much to whom. Shared by the whole band and synced through a small server; works offline.

- Web: https://kapelusz.jacekgajek.com
- Android: download `kapelusz.apk` from the website; the app updates itself.

## Project

- `shared/` — the data model, operations, permissions and settlement rules, used by the apps and the server alike.
- `composeApp/` — the app (Compose Multiplatform) for Android and the web (Wasm).
- `androidApp/` — the Android application shell.
- `server/` — the API (AWS Lambda, Java 21, SnapStart); data in S3.
- `scripts/deploy.sh` — deploys API, website and APK (run by CI after a merge to `main`).
- `design/icon/` — sources of the app icon.

## Build and run

Needs JDK 21 and the Android SDK (`local.properties` with `sdk.dir`, as Android Studio writes it). Gradle comes with
the wrapper.

- Web app in a browser, reloading on changes: `./gradlew :composeApp:wasmJsBrowserDevelopmentRun --continuous`
- Web production bundle (what is deployed): `./gradlew :composeApp:wasmJsBrowserDistribution`, output in
  `composeApp/build/dist/wasmJs/productionExecutable/`
- Android on a connected phone or emulator: `./gradlew :androidApp:installDebug`. A debug build never updates itself.
- Android release APK: `./gradlew :androidApp:assembleRelease`. It is unsigned unless `ANDROID_KEYSTORE_PATH`,
  `ANDROID_KEYSTORE_PASSWORD` and `ANDROID_KEY_ALIAS` are set.
- Server (Lambda) jar: `./gradlew :server:shadowJar`, output `server/build/libs/server-all.jar`. There is no local
  server; the apps use the API in `gradle.properties`.

The API URL, website URL and Google client id compiled into the apps come from `gradle.properties` (`kapelusz.*`).
The environment variables `KAPELUSZ_API_URL`, `KAPELUSZ_WEB_URL` and `KAPELUSZ_GOOGLE_WEB_CLIENT_ID` override them,
e.g. for a test API. The version name is `kapelusz.versionName`. The version code comes from `KAPELUSZ_VERSION_CODE`
(set by CI); local builds show "dev".

## Test

- Everything CI runs: `./gradlew check`. CI also builds the release APK, the web bundle and the server jar (see
  `.github/workflows/ci.yml`).
- Shared rules: `./gradlew :shared:allTests`. Server: `./gradlew :server:test`.
- App: `./gradlew :composeApp:allTests`. The common tests run on the JVM and in headless Chrome (wasm), together
  with the browser storage tests. Chrome is taken from `CHROME_BIN`, the `PATH` or a Playwright install; without one
  the browser tests are skipped.
- Android lint: `./gradlew :androidApp:lint`.

## Deploy

CI (`.github/workflows/ci.yml`) tests every pull request and push to `main`. On `main`, once the tests pass, its
deploy job runs `scripts/deploy.sh`: it builds the release artefacts once (the signed APK, the production web bundle,
the server jar) and uploads the API to Lambda, the web app to S3 and CloudFront, and the APK to the website and a
GitHub Release. The version code is CI's run number. AWS access uses GitHub OIDC, and the keystore
comes from the repository's secrets. Running `scripts/deploy.sh` by hand is described at its top.

## Documents

- [ITERATION_1.md](ITERATION_1.md) — the first planning iteration, as built.
- [ITERATION_2.md](ITERATION_2.md) — the second iteration: plan, milestones and decision log (app 1.0).
- [ITERATION_3.md](ITERATION_3.md) — the third iteration: hardening, quality work and new features.

The app was called Art Split until 1.0 (and Split Art for a few hours). The S3 buckets (`jacekgajek-art-split-*`) and the Lambda (`art-split-api`)
keep the old name: they hold the bands' data and run the API.
