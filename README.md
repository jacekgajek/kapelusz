# Kapelusz (Pass the Hat)

"Kapelusz" is Polish for hat, and starts like *kapela*, a band. The app is called Kapelusz in Polish and
Pass the Hat in English: the hat that goes round after a gig.

Settles a band's money after gigs: who collected what, who paid which costs, what goes to the band fund, and who
hands over how much to whom. Shared by the whole band and synced through a small server; works offline.

- Web: https://kapelusz.jacekgajek.com
- Android: Google Play only (in closed testing: testers join through the band's testers group).

## Project

- `shared/` — the data model, operations, permissions and settlement rules, used by the apps and the server alike.
- `composeApp/` — the app (Compose Multiplatform) for Android and the web (Wasm).
- `androidApp/` — the Android application shell.
- `server/` — the API (AWS Lambda, Java 21, SnapStart); data in S3.
- `localServer/` — the same API on this machine, with files instead of S3 and DynamoDB, for development offline
  ([LOCAL_DEVELOPMENT.md](LOCAL_DEVELOPMENT.md)).
- `scripts/deploy.sh` — deploys API and website, and builds the bundle for Google Play (run by CI after a merge to
  `main`).
- `scripts/restore-s3.sh` — puts a band's data back as it was at a moment in the past; see `DISASTER_RECOVERY.md`.
- `design/icon/` — sources of the app icon.

## Build and run

Needs JDK 21 and the Android SDK (`local.properties` with `sdk.dir`, as Android Studio writes it). Gradle comes with
the wrapper.

- Web app in a browser, reloading on changes: `./gradlew :composeApp:wasmJsBrowserDevelopmentRun --continuous`
- Web production bundle (what is deployed): `./gradlew :composeApp:wasmJsBrowserDistribution`, output in
  `composeApp/build/dist/wasmJs/productionExecutable/`
- Android on a connected phone or emulator: `./gradlew :androidApp:installDebug`. The app comes only from Google Play
  (since 2026-10-27, [DECISIONS.md](DECISIONS.md) 15), which updates it.
- Google Play bundle: `./gradlew :androidApp:bundleRelease`, signed with the upload key when
  `ANDROID_UPLOAD_KEYSTORE_PATH`, `ANDROID_UPLOAD_KEYSTORE_PASSWORD` and `ANDROID_UPLOAD_KEY_ALIAS` are set.
- Server (Lambda) jar: `./gradlew :server:shadowJar`, output `server/build/libs/server-all.jar`. The apps use the
  API in `gradle.properties`.
- Everything on this machine, offline too (local API, emulator, sign-in as made-up users):
  [LOCAL_DEVELOPMENT.md](LOCAL_DEVELOPMENT.md), `scripts/local-dev.sh`.

The API URL, website URL and Google client id compiled into the apps come from `gradle.properties` (`kapelusz.*`).
The environment variables `KAPELUSZ_API_URL`, `KAPELUSZ_WEB_URL` and `KAPELUSZ_GOOGLE_WEB_CLIENT_ID` override them,
e.g. for a test API. The version name is `kapelusz.versionName`. The version code comes from `KAPELUSZ_VERSION_CODE`
(set by CI); local builds show "dev".

## Test

- Everything CI runs: `./gradlew check`. CI also builds the Play bundle, the web bundle and the server jar (see
  `.github/workflows/ci.yml`).
- Shared rules: `./gradlew :shared:allTests`. Server: `./gradlew :server:test`. Local server: `./gradlew :localServer:test`.
- App: `./gradlew :composeApp:allTests`. The common tests run on the JVM and in headless Chrome (wasm), together
  with the browser storage tests and the screen smoke tests (`composeApp/src/wasmJsTest/.../ui/SmokeTest.kt`, which
  boot the app on a fake platform and server). Chrome is taken from `CHROME_BIN`, the `PATH` or a Playwright install;
  without one the browser tests are skipped.
- Android lint: `./gradlew :androidApp:lint`.

## Deploy

CI (`.github/workflows/ci.yml`) tests every pull request and push to `main`. On `main`, once the tests pass, its
deploy job (paused since 2026-10-31 for the refactoring, see `ci.yml`) runs `scripts/deploy.sh`: it builds the release artefacts once (the Play bundle, the production web bundle,
the server jar) and uploads the API to Lambda and the web app to S3 and CloudFront. The version code is CI's run
number plus 10 (since the first Play bundle, 231, went up by hand). AWS access uses GitHub OIDC; the keys come from
the repository's secrets (the app signing key only for its fingerprint, in `assetlinks.json`). Running
`scripts/deploy.sh` by hand is described at its top.

Google Play: with the upload key in the secrets (`ANDROID_UPLOAD_KEYSTORE_BASE64`,
`ANDROID_UPLOAD_KEYSTORE_PASSWORD`, optionally `ANDROID_UPLOAD_KEY_PASSWORD`, and the variable
`ANDROID_UPLOAD_KEY_ALIAS`) the deploy also builds the Play bundle, and with a service account's key
(`PLAY_SERVICE_ACCOUNT_JSON`) uploads it to the closed testing track. Production is promoted by hand in the Play
Console.

## Documents

- [SPEC.md](SPEC.md) — what the app does, consolidated from the eight iterations; the regression checklist at its end.
- [ARCHITECTURE.md](ARCHITECTURE.md) — how it is built: modules, packages, state, sync, the server, tests.
- [DECISIONS.md](DECISIONS.md) — the choices in force and why.
- [BACKLOG.md](BACKLOG.md) — what is known to be wrong or missing.
- [LOCAL_DEVELOPMENT.md](LOCAL_DEVELOPMENT.md), [DISASTER_RECOVERY.md](DISASTER_RECOVERY.md) — working offline; restoring data.
- [REFACTORING.md](REFACTORING.md) — the 2026-10-31 restructuring: plan, progress, reviews under `docs/refactoring/`.
- [docs/history/](docs/history/) — the planning iterations as they were written (`ITERATION_1` to `ITERATION_8`), the
  record of how each decision was reached; `SPEC.md` cites them.

The app was called Art Split until 1.0 (and Split Art for a few hours). The S3 buckets (`jacekgajek-art-split-*`) and the Lambda (`art-split-api`)
keep the old name: they hold the bands' data and run the API.
