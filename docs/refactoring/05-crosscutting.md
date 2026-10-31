# Cross-cutting review: platform layer, build, resources, tests, conventions

Repo: `/home/jacek/IdeaProjects/band_settlement`, branch `local-dev-setup` @ 11db045. Read-only review; nothing changed.
All paths below are relative to the repo root. Line numbers are from the current working tree.

---

## 1. Inventory (numbers)

### 1.1 Size

| Module | main .kt files | main lines | test files | `@Test` | test lines |
|---|---|---|---|---|---|
| shared | 29 | 4,097 | 29 (commonTest) | 225 | 3,884 |
| composeApp | 146 | 19,057 | 22 commonTest + 3 androidHostTest + 1 wasmJsTest | 144 + 13 + 3 | 2,847 + 344 + 43 |
| androidApp | 2 | 139 | 0 | 0 | 0 |
| server | 23 | 3,114 | 17 | 125 | 2,545 |
| localServer | 5 | 396 | 1 | 7 | 148 |
| **total** | 205 | **26,803** | 73 | **517** | 9,811 |

composeApp commonMain: 254 `@Composable` functions, 141 of them in `ui/screens` (47 files).

### 1.2 Platform layer: expect/actual

24 `expect` declarations in 11 common files (`composeApp/src/commonMain/.../app/platform/*`, `ui/AppLocale.kt`, `auth/GoogleSignInButton.kt`), all with one Android and one wasmJs actual. The flag `-Xexpect-actual-classes` (composeApp/build.gradle.kts:95) exists only for the two `expect object`s.

They come in five different shapes:

| Shape | Count | Declarations |
|---|---|---|
| `@Composable` "remember*" returning a lambda or object | 9 | rememberAttachmentPicker, rememberUrlOpener (Files.kt:29,40); rememberCopyImage, rememberOpenPdf (Media.kt:26,40); rememberShare, rememberCopy, rememberPaste (Share.kt:10,14,18); rememberQrScanner (QrScanner.kt:18); rememberStoreUpdates (StoreUpdates.kt:43) |
| `@Composable` UI drawn by the platform | 4 | MapPickerDialog (MapPicker.kt:13), PlacesMap (PlacesMap.kt:14), PdfPages (Media.kt:33), GoogleSignInButton (auth/GoogleSignInButton.kt:8) |
| plain top-level functions | 6 | uploadFile (Files.kt:32), webInstallState (InstallHints.kt:36), pdfPagePng (Media.kt:36), appReloader, isAndroidApp, storeListing (Updates.kt:4,7,13) |
| top-level `val` flags | 2 | mapPickerAvailable (MapPicker.kt:6), pdfInViewer (Media.kt:29) |
| `expect object` | 2 | FileCache (Media.kt:10), LocalAppLocale (ui/AppLocale.kt:13) |

**12 of the 24 actuals exist only to say "not on this platform"**: on wasm `MapPickerDialog` and `PlacesMap` have empty bodies, `PdfPages = Unit`, `pdfPagePng = null`, `rememberOpenPdf` returns `false`, `rememberQrScanner`/`rememberStoreUpdates`/`storeListing` return `null`, `mapPickerAvailable`/`pdfInViewer` are `false`; on Android `appReloader()` and `webInstallState()` return `null`. The expect/actual mechanism is being used as a capability registry.

In parallel there is a **second mechanism** for the same job, plain interfaces injected into `AppGraph` (data/AppGraph.kt:27-33): `KeyValueStore` (FileStore / IndexedDbStore), `GoogleAuth` (`object AndroidGoogleAuth` / `object WebGoogleAuth`), `MessageChannel` (FirebaseMessageChannel / null). And a **third**: three process-wide globals on Android, `AndroidPlatform.context` (platform/AppSuggestion.android.kt:8, set in AndroidAppGraph.kt:20), `AndroidGoogleAuth.activity` (auth/AndroidGoogleAuth.kt:39, set in androidApp MainActivity.kt:35), and the `AndroidAppGraph` singleton.

How the UI consumes them (common files outside `platform/`):

| Expect | Consumers |
|---|---|
| rememberCopy | 7 files: PaymentDetails, EventDialogs, StuckPushBanner, TransfersSection, RosterScreen, CalendarSettings, SecretMenuDialog |
| rememberShare | ShareInfo, ShareSettlement, RosterScreen |
| rememberStoreUpdates, appReloader, storeListing | AppBanner, UpdateRequiredScreen, SettingsScreen, WebInstallHint |
| isAndroidApp | AppBanner.kt:38, settings/CalendarSettings.kt:83 |
| mapPickerAvailable | event/EventFields.kt:236,240; PlayedScreen.kt:109,179 |
| pdfInViewer | AttachmentsSection.kt:162,186,218 |
| FileCache (object, used as a default ctor arg) | data/Attachments.kt:37 |
| uploadFile (used as default ctor arg) | data/AttachmentUploads.kt:32 |
| everything else | exactly one screen each |

Platform decisions made in common code by flag: `isAndroidApp()` (2 sites), `storeListing() == null` meaning "this is the web" (SettingsScreen.kt:181), `reload != null` / `store != null` (UpdateRequiredScreen.kt:50-53), `AppConfig.DEV_SIGN_IN` (auth/DevSignIn.kt:29), `AppConfig.HAS_MAPS_KEY` (only via `mapPickerAvailable`).

Wasm actuals contain 28 inline `js("...")` blocks (Files.wasmJs.kt alone has ~50 lines of JavaScript at 27-75) and 6 string contracts shared with `index.html`/`sw.js` (`__customLocale`, `kapelusz-google-button`, `kapeluszVersion`, `kapelusz-active-tab`, `kapelusz-join-code`, `kapelusz-app-banner-dismissed`).

### 1.3 Build

6 Gradle files, 383 lines; `gradle/libs.versions.toml` with 46 libraries. No `build-logic`/convention plugin: `jvmToolchain(...)` is repeated in 5 modules, `compileSdk/minSdk` in 2. Two build files read the same env/properties independently:

- version code: composeApp/build.gradle.kts:46 (`KAPELUSZ_VERSION_CODE` else "0") and androidApp/build.gradle.kts:16 (else `1`);
- Google Maps key: composeApp/build.gradle.kts:18-20 (`env > local.properties > gradle.properties`) vs androidApp/build.gradle.kts:20-21 (`env > gradle.properties`, **skips local.properties**).

Static analysis: none. No ktlint, detekt, spotless, `.editorconfig`, Compose compiler reports, Konsist or dependency rules. Only `kotlin.code.style=official` and `androidApp/lint.xml` (one suppression). No Dependabot/Renovate (`.github/` holds only `workflows/ci.yml`).

CI (`.github/workflows/ci.yml`): one job `check` on PRs (`./gradlew check`), one `deploy` on main. The release artefacts (`:androidApp:bundleRelease` with R8, `:composeApp:wasmJsBrowserDistribution`, `:server:shadowJar`) are **built only in the deploy job, after merge**. The R8 crash recorded in `androidApp/proguard-rules.pro:3-6` ("release build crashed … 1.13.0") is exactly the class of failure that this leaves for production. Wasm browser tests are silently disabled when no Chrome is found (composeApp/build.gradle.kts:85-90: `enabled = chrome != null`); CI relies on `ubuntu-latest` happening to ship Chrome, with no explicit setup step.

### 1.4 Resources / i18n

- 2 languages, en (`values/`) and pl (`values-pl/`), both complete: **786 `<string>` + 13 `<plurals>`** each, 0 missing, 0 extra, 0 duplicates.
- 6 files: `strings.xml` (695), `strings_shares.xml` (25), `strings_share.xml` (24), `strings_lineup.xml` (23), `strings_payment.xml` (17), `strings_install.xml` (2). The split is recent and partial; `strings_share` (sharing a link) next to `strings_shares` (member shares) is a trap.
- References: 859 `stringResource(`, 10 `pluralStringResource(`, 3 `getString(` in composeApp; 0 hard-coded `Text("…")` in common code (good). Strings are only read from `ui/` plus `ui/model/HistoryText.kt` and `ui/model/Agenda.kt`.
- **Dead keys (no `Res.string.X` anywhere): `move_down`, `notifications_debts`, `share_fixed`, `share_weight`** (×2 languages = 8 entries).
- Naming: flat snake_case with ad-hoc prefixes. Top prefixes: `h_` 72 (history lines), `share_` 57, `fund_` 57, `reject_` 46, `summary_` 29, `settle_` 22, `delete_` 21, `attachment_` 20; 37 keys have no prefix at all (`back`, `join`, `redo`, `settings`…). `h_` is the only abbreviated prefix.
- androidApp has its own `values/strings.xml` + `values-pl/strings.xml` for `app_name` only (correct: the launcher label must be an Android resource).

### 1.5 Comments and design-doc references

- 1,247 KDoc blocks and 832 `//` lines in 26.8k lines of main code (about one doc comment every 13 lines). 0 TODO/FIXME.
- **710 lines reference `ITERATION_n`** (shared 204, composeApp 395, server 87, androidApp 13, CI 6, scripts 2) pointing at 100 distinct targets; by document: I7 194, I5 156, I3 121, I4 80, I6 68, I2 48, I8 29, I1 7. Another ~88 lines cite decision-log items (`R6`, `R9`…) and milestone codes (`V5-M1 C2a`, `D7`, `C6a`).
- The 8 ITERATION docs are 3,033 lines, plus README (80), LOCAL_DEVELOPMENT (147), DISASTER_RECOVERY (105). README lists only ITERATION_1/2/3/7 under "Documents".
- Resolution check (script over all refs): every `§x.y` / `Rn` reference does point at *something*, but the target's form varies: a heading, a bold paragraph (`**R13. Small fixes.**`, ITERATION_3.md:167), or a numbered bullet inside a section (all `ITERATION_5 §4.x` references, 22 occurrences, resolve to list items 1-9 under ITERATION_5.md:390). Milestone codes such as `C2a`, `C2b`, `C6a`, `D7` (App.kt:110, Navigation.kt:249, SettingsWiring.kt:88, MainActivity.kt:92) appear in the docs **only as codes in scope lists** (ITERATION_5.md:308-320); the text describing them was in chat ("each batch's plan is reported and approved before implementing", ITERATION_5.md:485) and is not in the repository.
- 7 comments carry dates (`since 2026-10-27`, …) and several carry version history ("was for the reminder prompt (ITERATION_2 §5.2), gone since ITERATION_6 §9", Reducer.kt:462; "1.13.0 crashed creating it", QrScanner.android.kt:22).

### 1.6 Code style facts

- Imports unsorted in **129 of 259** files with more than one import (e.g. PlacesMap.android.kt:4-37, Media.android.kt:12-56, Main.kt:3-32, App.kt:3-60, MainActivity.kt:3-26). 0 wildcard imports.
- 2,007 lines over 120 chars, 827 over 140; longest 239 (shared/src/commonTest/.../PlacesTest.kt:23). No `.editorconfig`.
- 53 `!!` in main code (CostScreen.kt:172,180,196; SettleTogether.kt:73-81; PlacesLocator.kt:50-52 …). 133 `runCatching`/`runSuspendCatching` in main.
- Visibility: 480 public top-level declarations vs 73 `internal` in composeApp+shared+server; `internal` is applied inconsistently (`Navigation` internal, `App` public; `WebInstallHint` internal, `AppBanner` public).
- Logging: 28 `println`/`System.err.println` sites, no logger. In the app: AppGraph.kt:35 (unhandled coroutine errors) and BandRepository.kt:493 (storage failures) — on Android these go to `System.out` only, in release nobody sees them. Platform actuals swallow failures with `runCatching { … }.getOrDefault(false)` without logging (Media.android.kt:107-113, 326-335; Share.wasmJs.kt:19).
- Test naming: 514 camelCase sentence names (`aHiddenTabSendsWhatWaitsThenStops`), 0 backtick names. Consistent.
- Test packages: all 29 shared tests sit in the root package `com.jacekgajek.kapelusz` while the code is in `model/domain/ops/auth`; composeApp's 13 `ui/` tests are in `app.ui` while their subjects are in `ui.model` (7), `ui.screens` (4), `ui.components` (3).

---

## 2. Problems

### 2.1 The platform surface is wide, inconsistent, and half of it is "absent" stubs

- Five shapes for one concept (§1.2). A reader must know that `rememberShare()` is composable, `uploadFile()` is a suspend function, `FileCache` is an object, `pdfInViewer` is a val, and `GoogleAuth` is an interface injected through `AppGraph`. There is no single place that lists what a platform must provide.
- 12/24 actuals are no-ops. Common screens then branch on `isAndroidApp()`, `storeListing() == null`, `reload != null`, `mapPickerAvailable`, `pdfInViewer` to decide what to draw. That is platform knowledge leaking upward: `SettingsScreen.kt:181` hides the "support me" link because `storeListing()` is non-null, i.e. it encodes a Play Store billing rule via a function that nominally returns a URL.
- `FileCache` and `uploadFile` are reached as default constructor arguments (Attachments.kt:37, AttachmentUploads.kt:32). This makes `Attachments` untestable without the platform (there is no `AttachmentsTest`), while `AppGraph` gets `KeyValueStore`/`GoogleAuth`/`MessageChannel` injected properly. Two injection styles in the same graph.
- Android globals: `AndroidPlatform.context` is a service locator used from `FileCache.dir()` (Media.android.kt:62), `pdfPagePng` (Media.android.kt:306) and `AndroidGoogleAuth.signOut` (AndroidGoogleAuth.kt:56); `PdfPages` in the same file uses `LocalContext` (Media.android.kt:189). Two ways to get a `Context` in one file.
- File/name mismatches: `platform/AppSuggestion.kt` contains `PublishedVersion`; `AppSuggestion.android.kt` contains `AndroidPlatform`; `InstallHints.kt` contains `BrowserFacts/WebInstallState/WebHint` plus pure functions; `Media.android.kt` (338 lines) holds five concerns: file cache, clipboard image, PDF document, PDF page list + zoom maths (`PdfZoom`, 65 lines of pure arithmetic at 239-303 that has nothing Android-specific), and "open in reader" intent.
- `LocalAppLocale.provides` on Android (AppLocale.android.kt:18-27) calls `Locale.setDefault` and `resources.updateConfiguration` **inside composition**, on every recomposition. It works, but it is a side effect in a composable body and the captured `default` (line 20) never follows a system-locale change.

### 2.2 Package layout: "data" is a grab bag; root package mixes UI and wiring

`app/data/` holds storage (`KeyValueStore`, `FileStore`, `IndexedDbStore`), repositories (`BandRepository`, `SettingsRepository`), use-case facades (`Account`, `Membership`, `Changes`, `Attachments`, `Incoming`, `DeviceMessages`), utilities (`Platform.kt` = ids/clock, `SuspendCatching.kt`, `AwaitPushed.kt`) and the DI root (`AppGraph`). The root `app/` package has `App.kt`, `Navigation.kt`, `AppDialogs.kt` and `SharedFilesDialog.kt` (385 lines of UI). Nothing is wrong per se, but nobody can guess where a new class goes, which is the "things all over the place" feeling.

### 2.3 Many parameters

Six composables take 8-13 parameters: `EventScreen` 13 (event/EventScreen.kt:154-168: 7 services + state + id + 4 callbacks), `MemberDialog` 12, `ZeroScreen` 11, `AttachmentsSection` 10, `EventsScreen` 8, `FundEntryDialog` 8. `Navigation.kt:159-236` is 80 lines of hand-wiring that passes `graph.changes, graph.api, graph.bands, graph.sync, graph.settings…` into each screen. The recent `settingsFor(...)` (SettingsWiring.kt:45-53) is a good pattern — it takes 7 arguments once and returns a typed `Settings` object — but it is used by one screen only.

### 2.4 Build config duplication and drift

- Version code and maps key are read in two build files with different precedence (§1.3). A developer who sets `kapelusz.googleMapsAndroidKey` in `local.properties` gets `AppConfig.HAS_MAPS_KEY = true` (pin button shown) but no key in the manifest placeholder (map renders blank).
- No convention plugin: Android SDK levels, toolchain and `withHostTest` are copy-pasted.
- `generateAppConfig` (composeApp/build.gradle.kts:33-82) writes Kotlin source with string templating, including a `check()` that forbids dev sign-in in releases — important logic buried in a Gradle `doLast`.

### 2.5 CI gaps

- Release artefacts are not built on PRs (§1.3); R8/shrinking and the wasm production pipeline (`BinaryenExec` tuning, composeApp/build.gradle.kts:179-193) are exercised after merge only.
- No explicit Chrome setup for wasm tests; silent skip locally.
- No dependency update automation; 46 pinned libraries, AWS SDK/Ktor/Compose move monthly.
- `./gradlew check` is assumed to run `androidHostTest`; worth confirming once in the CI log (I could not run Gradle in this review).

### 2.6 Test fakes are duplicated, and a few tests use wall-clock time

- Four fake backends: `FakeServer` (SyncEngineTest.kt:72-~190, ~120 lines, runs the real `Engine` behind Ktor `MockEngine`), `InMemoryBlobStore`/`InMemoryHistoryStore` (server/src/test, 60 lines), `FileBlobStore`/`FileHistoryStore` (localServer main, 146 lines), and `LocalServer` itself. 13 server test classes each build `Api(store, InMemoryHistoryStore(), { token -> VerifiedUser(token, null, null) }, clock = …)` by hand (ApiTest.kt:37, AttachmentTest.kt:33, CalendarTest.kt:25, DeleteBandTest.kt:32, …).
- `InMemoryStore` (a test double) lives in **main** code (data/KeyValueStore.kt:10) and ships in the app.
- Wall-clock waits: SyncEngineTest.kt:705-707 `eventually` polls with real `delay(20)` up to 5 s; :740-758 use `withContext(Dispatchers.Default) { delay(200/300) }` and the comment at :739 records a CI flake ("a CI run counted one more request"). FcmSenderTest.kt:33 `Thread.sleep(5_000)` inside the fake HTTP server — the suite always pays 5 s. LocalServerTest boots a real HTTP server per test (7×). GoogleTokenVerifierTest generates RSA keys.
- `androidHostTest` is used as "the JVM test set": SettlementTextTest.kt:50 and RejectionTextsTest.kt:13 read `strings.xml` from disk with `java.io.File`, so the Polish-wording checks never run on wasm; PdfZoomTest is there only because `PdfZoom` lives in an Android file.

### 2.7 What is not tested

- **All 47 screen files / 141 screen composables.** Only four helper functions pulled out of screens have tests (BandHint, InviteCode, EventInfoText, UndoTargets). No `compose-ui-test`, no Robolectric, no screenshot tests. `Navigation.kt` (315 lines of routing rules: artist redirects, band-switch resets, event links) is untested.
- In `app/data`: `AppGraph`, `Attachments`, `Incoming`, `DeviceMessages`, `PlacesLocator`, `EventLinkOpener`, `AwaitPushed` have no tests. All platform actuals except `PdfZoom` and `IndexedDbStore`. `androidApp` has no tests (MainActivity intent handling: join links, shared files, recents detection at MainActivity.kt:59-97).

### 2.8 Comments as documentation: drift risk

The code is unusually well commented (a KDoc every 13 lines, prose that explains *why*). The risk is the 710 pointers into 3,000 lines of planning documents:

- They are not stable anchors. `ITERATION_5 §4.2` means "bullet 2 under heading 4"; `ITERATION_3 R13` resolves to "**R13. Small fixes.**" which tells the reader nothing; `V5-M1 C2a` resolves to a code in a scope list whose description was never written down in the repo.
- They freeze history into code: a maintainer reading `Reducer.kt:462` learns that a field "was for the reminder prompt, gone since ITERATION_6 §9" — that belongs in git history, not in the reducer.
- They assume the reader has the ITERATION docs open, and that those docs keep their numbering forever. ITERATION_5 has already been re-numbered once (1.7 comes before 1.6 at lines 300/329).
- README lists only 4 of the 8 docs.
Net: for a single maintainer who wrote them, they are a memory aid; for anyone else (including you in a year) the reference carries less than the sentence around it. The sentence is usually enough.

### 2.9 Formatting and conventions have no enforcement

Unsorted imports in half the files and 827 lines over 140 chars are not harmful, but they show nothing is checked, and they create noise in every diff. Import order differences also make "move this function" diffs larger than they need to be.

---

## 3. Target design (as if built from scratch)

### 3.1 One `Platform` object, injected; capabilities are nullable members

```kotlin
// composeApp/src/commonMain/.../app/platform/Platform.kt
interface Platform {
    val kind: PlatformKind                     // ANDROID, WEB — for the two places that truly need it
    val store: KeyValueStore
    val fileCache: ByteCache
    val google: GoogleAuth
    val messages: MessageChannel?              // null on the web
    val storeUpdates: StoreUpdates?            // null on the web / debug builds
    val reload: (() -> Unit)?                  // web only
    val storeListing: String?                  // Play only
    val maps: Maps?                            // null without a key / on the web
    val pdf: PdfRenderer?                      // null on the web
    val qrScanner: QrScanner?                  // null on the web
    val clipboard: Clipboard                   // copy/paste/copyImage (copyImage may return false)
    val sharing: Sharing                       // share(text): ShareResult
    suspend fun uploadFile(url: String, headers: Map<String, String>, base64: String): Int
    suspend fun webInstallState(): WebInstallState?

    @Composable fun rememberAttachmentPicker(onResult: (List<PickResult>) -> Unit): AttachmentPicker
    @Composable fun rememberUrlOpener(): (suspend () -> String?) -> Unit
    @Composable fun SignInButton(onIdToken: (String) -> Unit, onError: (String) -> Unit, modifier: Modifier)
}
interface Maps { @Composable fun PickerDialog(...); @Composable fun PlacesMap(...) }
interface PdfRenderer { @Composable fun Pages(...); suspend fun pagePng(bytes: ByteArray, page: Int): ByteArray? }
```

- `AppGraph(platform: Platform)` replaces the three current constructor args plus the two default-arg back doors (`FileCache`, `::uploadFile`). `App()` provides `LocalPlatform`. Screens read `LocalPlatform.current.maps?.let { … }` instead of `if (mapPickerAvailable)`.
- `AndroidPlatform(app: Application)` and `WebPlatform()` are plain classes in androidMain/wasmJsMain; the activity-bound pieces (credential manager, activity-result launchers) stay `@Composable` members that use `LocalContext`.
- `expect`/`actual` disappears except `LocalAppLocale` (a Compose-resources recipe), so `-Xexpect-actual-classes` can go once that is rewritten as a plain `CompositionLocal` + a `Platform.applyLocale(tag)` member.
- Testability: `FakePlatform` in commonTest (all capabilities null, in-memory store) makes `Attachments`, `AppGraph`, `AppBanner`, `UpdateRequiredScreen` testable on the JVM.
- Delete `isAndroidApp()`. The two uses become: `platform.storeUpdates != null` (AppBanner) and `platform.kind == WEB` (Google Calendar "add by URL" works only in a browser — a genuine platform fact, keep it explicit).

### 3.2 Package layout

```
app/
  App.kt, Navigation.kt            (composition root + routes only)
  platform/   Platform.kt + interfaces + PickResult etc.   (no implementations in commonMain)
  storage/    KeyValueStore, FileStore(android), IndexedDbStore(wasm)
  session/    Session, SessionRepository, GoogleAuth, DevSignIn
  sync/       ApiClient, SyncEngine, HistoryDecoder
  bands/      BandRepository, Changes, Membership, Account, Attachments, AttachmentUploads, Incoming, PlacesLocator
  ui/         theme/, components/, model/, screens/<screen>/
  util/       Ids.kt (newId/now/today), SuspendCatching.kt
```
Rule of thumb for the ARCHITECTURE doc: `ui → bands/session/sync → storage/platform`; `shared` never imports `app`; `platform` interfaces are the only way across the platform boundary; nothing in `commonMain` names Android or the browser.

### 3.3 Screen wiring ("many parameters")

Follow the `settingsFor` pattern everywhere: one `*Wiring.kt` per screen that takes the graph + state **once** and returns a small typed object of data and callbacks; the screen composable takes that object plus navigation. Concretely for `EventScreen`: `EventScreen(deps: EventDeps, eventId: String, nav: EventNav)` where `EventDeps` is built in `Navigation.kt` from `graph` and `state`. Provide `LocalBand` (`BandState.Ready`) and `LocalGraph` composition locals for leaf components so they stop threading `state` through 4 levels. Target: no composable above 6 parameters except `Modifier`.

### 3.4 Resource naming scheme

- Key = `<area>_<element>[_<qualifier>]`, area = screen or shared component: `event_`, `cost_`, `roster_`, `settings_`, `history_` (replace `h_`), `fund_`, `sharing_` (link/text sharing) vs `share_` (member shares, or better `split_`). Shared verbs under `action_` (`action_back`, `action_save`), shared states under `status_`.
- One file per area (`strings_event.xml` …), `strings.xml` only for `action_`/`status_`/app-level.
- A test (JVM, like RejectionTextsTest) that fails on a key with no `Res.string.` reference and on a key missing in `values-pl` — cheap, prevents the 4 dead keys from recurring.

### 3.5 Testing strategy by layer

| Layer | Test kind | Location | Notes |
|---|---|---|---|
| `shared` rules | pure unit, `Scenario` builder | shared/commonTest | already good; mirror packages |
| app `bands/session/sync` | `runTest` with virtual time, `FakePlatform`, one shared `FakeServer` | composeApp/commonTest (JVM + wasm) | inject a `CoroutineDispatcher` into `SyncEngine` so no test needs `Dispatchers.Default` |
| app `ui/model` + `*Wiring` | pure unit | composeApp/commonTest | the wiring objects are where screen logic should live, so they get the tests screens cannot |
| screens | a handful of Robolectric-free Compose UI tests on the JVM via `compose.uiTest` (`runComposeUiTest`) **or** screenshot tests | composeApp/androidHostTest | start with 5 smoke tests: sign-in, zero screen, event screen opens, settings pages, update-required |
| platform impls | manual + small unit tests of pure parts (`PdfZoom` → commonTest) | androidHostTest / wasmJsTest | |
| server | `Api`-level tests through one `TestApi()` builder; in-memory stores | server/test | drop the 5-second sleep (inject the deadline) |
| localServer | 2-3 end-to-end tests | localServer/test | it reuses `Api`, so most behaviour is covered in server/test |

### 3.6 Conventions doc (what CONTRIBUTING.md / ARCHITECTURE.md should say)

1. Module map and the dependency direction (§3.2), with the one-paragraph reason for each module.
2. Platform boundary: "add a capability = add a member to `Platform`, implement twice, fake once".
3. Where a new thing goes (table: repository / use case / screen / component / model helper / resource).
4. Comment policy: comments say *why* the code is as it is, in full sentences, self-contained; no dates, no version numbers, no "was X until Y" (that is `git log`); design-doc pointers only to a stable heading and only when the sentence cannot carry the reason alone.
5. Naming: resource keys (§3.4), test names (keep camelCase sentences), `*Wiring.kt`, `*.android.kt`/`*.wasmJs.kt`.
6. Visibility: `internal` by default in composeApp (it is a library consumed by `androidApp` only); public only for what `androidApp`/`Main.kt` need.
7. Errors and logging: `runSuspendCatching` for suspend calls; never swallow silently — log through one `Log` facade (println on wasm, `android.util.Log` on Android).
8. Versioning and release: already in README/memory; link it.
9. Design docs: `docs/history/ITERATION_n.md` frozen; new decisions as short ADRs in `docs/adr/`.

### 3.7 Tooling

- `ktlint` (Gradle plugin, `.editorconfig` with `max_line_length = 140`, `ij_kotlin_imports_layout = *`), run in `check`; one formatting commit.
- `detekt` with `detekt-compose` rules (ModifierMissing, UnstableCollections, ParameterOrder …) and a baseline; fail CI on new findings only.
- Compose compiler reports (`composeCompiler { reportsDestination = … }`) uploaded as a CI artefact — cheap insight into unstable parameters in the 13-param screens.
- Konsist (works on KMP source sets) for 5 layering assertions: no `android.` import in commonMain, `shared` never imports `app`, `ui` never imports `sync` directly, etc.
- `build-logic/` convention plugins: `kapelusz.kmp`, `kapelusz.android`, `kapelusz.jvm`; one `KapeluszConfig` value source read by both `composeApp` and `androidApp`.
- Dependabot for `gradle` and `github-actions`, weekly, grouped.
- CI: PR job also runs `:androidApp:assembleRelease` (unsigned), `:composeApp:wasmJsBrowserDistribution`, `:server:shadowJar`; explicit `browser-actions/setup-chrome` step; the deploy job reuses artefacts via `actions/upload-artifact` so nothing is built twice.

---

## 4. Migration plan (ordered, each step shippable)

| # | Step | Files touched | Risk | Notes |
|---|---|---|---|---|
| 1 | **Hygiene**: untrack `PRO.md` (see §5.1), restore the `ANDROID_SERIAL` guard, delete 4 dead string keys (×2), fix orphan KDoc in `Main.kt`, add `.editorconfig`, add `.github/dependabot.yml` | ~8 | none | half a day |
| 2 | **Formatting tooling**: ktlint plugin + one `ktlintFormat` commit (129 files' imports), detekt + baseline | all .kt (mechanical) | none functionally; large diff, so a PR of its own | |
| 3 | **Build convention plugins** + single `KapeluszConfig` provider; fix the maps-key precedence mismatch | 6 build files, `settings.gradle.kts`, new `build-logic/` | low | verify with `./gradlew check` and a local `installDebug` |
| 4 | **CI**: build release artefacts on PRs, explicit Chrome, artefact hand-off to deploy | `ci.yml`, `deploy.sh` (`SKIP_BUILD` path already exists) | low | +5-8 min per PR |
| 5 | **Platform step A (non-composable part)**: introduce `interface Platform` with `kind`, `store`, `fileCache`, `google`, `messages`, `reload`, `storeListing`, `uploadFile`, `webInstallState`; `AndroidPlatform`/`WebPlatform`; `AppGraph(platform)`; `LocalPlatform`; delete `Updates.kt(+2)`, `AppSuggestion.android.kt`, `AndroidPlatform` global, the two `val` flags; replace 10 call sites | ~25 | medium (touches sign-in and sync wiring) | behaviour-preserving; `FakePlatform` + first `AttachmentsTest` land here |
| 6 | **Platform step B (composable part)**: `Maps?`, `PdfRenderer?`, `QrScanner?`, `StoreUpdates?`, `Clipboard`, `Sharing`, picker/opener/sign-in button as interface members; remove the remaining `expect`s and `-Xexpect-actual-classes` | ~30 | medium | `rememberCopy` has 7 call sites; do clipboard first, maps/pdf last |
| 7 | **File reorganisation**: split `Media.android.kt` into `FileCache.android.kt`, `Pdf.android.kt`, `Clipboard.android.kt`; move `PdfZoom` + test to common; rename `AppSuggestion.kt` → `PublishedVersion.kt`; `data/` → `storage/`, `session/`, `bands/`, `util/` | ~60 (moves) | low (IDE refactor) | do after step 6 so moves are not redone |
| 8 | **Test fixtures**: `FakeServer` → `commonTest/testing/`; `TestApi()` builder in server tests; `InMemoryStore` → commonTest; inject dispatcher into `SyncEngine`, replace real delays with virtual time; inject the FCM deadline | ~20 test files + SyncEngine ctor | medium (SyncEngine timing) | removes the known flake and ~6 s of suite time |
| 9 | **Screen wiring**: per-screen `*Wiring.kt` + `LocalBand`/`LocalGraph`; start with `EventScreen` (13 params) and `ZeroScreen` (11); then `Navigation.kt` shrinks | ~15 | medium, one screen per PR | add the first 5 Compose UI smoke tests alongside |
| 10 | **Docs & comments**: `ARCHITECTURE.md`, `CONTRIBUTING.md`, move `ITERATION_*.md` under `docs/history/`; rewrite the 710 pointer comments to self-contained sentences (keep a pointer only where a heading exists and adds something) | all .kt (text only) | none functionally; tedious | can be done gradually per touched file with a rule "a touched file loses its ITERATION pointers" |
| 11 | **Resource renaming**: scripted rename to the `<area>_` scheme, split per area, add the dead-key test | 12 xml + ~150 kt call sites | low (compile-checked) | last, because it is pure churn |

---

## 5. Noticed, not fixed

1. **`PRO.md` is committed** in `f003fcc` ("Local setup") on branch `local-dev-setup` (`git ls-files PRO.md` lists it; not on `origin/main`, and the commit is not on any remote yet). Your rule says it stays untracked; `.gitignore` does not list it either. Needs `git rm --cached PRO.md` + a `.gitignore` line before the branch is pushed.
2. `scripts/local-dev.sh:21-25`: the `ANDROID_SERIAL` guard is commented out, so `installDebug` installs on every connected device (the emulator-Google-account rule in your memory).
3. `androidApp/build.gradle.kts:20-21` reads the maps key from env/gradle.properties only, `composeApp/build.gradle.kts:18-20` also from `local.properties`: a key in `local.properties` yields `HAS_MAPS_KEY = true` with an empty manifest placeholder (blank map).
4. `androidApp/build.gradle.kts:16` default `versionCode = 1` vs `AppConfig.VERSION_CODE = 0` in composeApp/build.gradle.kts:46 — two sources of the same value.
5. `composeApp/src/androidMain/.../ui/AppLocale.android.kt:19-27`: `Locale.setDefault` and `updateConfiguration` run in the composable body on every recomposition; `default` (line 20) is captured once, so "follow the device" ignores a later system-language change until process restart.
6. `Media.android.kt:306` `pdfPagePng` reads `AndroidPlatform.context` (null before `AndroidAppGraph.get` has run) while `PdfPages` (189) uses `LocalContext` — same file, two context sources.
7. `wasmJsMain/.../Main.kt:109-113`: two KDoc blocks back to back; the join-link doc is attached to nothing and `takeJoinCodeFromUrl` (120) is undocumented.
8. `Share.android.kt:16-18` `rememberShare` always returns `SHARED`; `startActivity` on the chooser is not guarded, unlike `rememberOpenPdf` (Media.android.kt:329-334) which catches `ActivityNotFoundException`.
9. `StoreUpdates.android.kt:32` `LocalContext.current as? Activity` returns null inside a `Dialog` (context is a `ContextThemeWrapper`; `AndroidGoogleAuth.kt:77-78` knows this and uses `findActivity()`). Fine today because `UpdateRequiredScreen`/`AppBanner` are not in dialogs, but it will silently disable updates if that changes.
10. `SyncEngineTest.kt:705-707, 739-758`: wall-clock polling and 200/300 ms sleeps; the comment at 739 documents a past CI flake.
11. `server/src/test/.../FcmSenderTest.kt:33`: `Thread.sleep(5_000)` in the fake server — the server suite always takes ≥5 s for this one test.
12. `composeApp/build.gradle.kts:85-90`: wasm browser tests are silently disabled without Chrome; nothing tells a developer that `IndexedDbStoreTest` did not run. CI has no explicit Chrome setup.
13. `data/KeyValueStore.kt:10` `InMemoryStore` (test double) is in `commonMain` and ships in the app.
14. `platform/Updates.kt:26` `PLAY_PUBLIC = false` is a compile-time constant that must be flipped by a code change at the production release; README does not mention it.
15. `platform/InstallHints.wasmJs.kt:28-31` `forgetStoredDismissal()` is migration code for "earlier versions" that runs on every event-list visit forever; `androidApp/.../ApkLeftovers.kt` likewise cleans up after the pre-Play APK. Both are backward-compat code your memory says you do not want to carry; worth a removal date.
16. `AppGraph.kt:35` and `BandRepository.kt:493` log via `println`; in a release build on Android nobody sees unhandled coroutine failures or storage write failures.
17. Dead string keys: `move_down`, `notifications_debts`, `share_fixed`, `share_weight` in `values/` and `values-pl/`.
18. `README.md:67-72` lists only ITERATION_1/2/3/7; 4/5/6/8 are unlisted.
19. `shared` test files all live in the root package `com.jacekgajek.kapelusz` (29 files) rather than next to `model/domain/ops`; harmless, but it is why `grep -r "class Reducer"` finds the test far from the code.
20. `androidApp/src/main/AndroidManifest.xml:21-22` comment cites `V5-M1` — a milestone code that is defined only as a code in ITERATION_5.md:308-320.
