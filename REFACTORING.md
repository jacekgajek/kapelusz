# Refactoring Kapelusz as if it had been built from a known specification

Date: 2026-10-31. Reviewed at commit 11db045 (`local-dev-setup`, since merged to `main` as e0377a3 together with the
server route table). This is a plan and a review, not a change: no application code was modified by it. Six independent code reviews (UI, app core, shared, server, cross-cutting, and a spec extracted from the
ITERATION documents) fed this report; their full text with every file:line citation is under
[`docs/refactoring/`](docs/refactoring/). Read this file first; open an appendix when you want the evidence.

Constraints honoured throughout: the data model and the JSON stored in S3 do not change; no new features and no bug
fixes are part of the refactoring (what was noticed is listed in §8); no backward compatibility is carried; no DI or
view-model framework; no push notifications.

---

## 1. Diagnosis in one page

The complaint "chaotic, things all over the place, many parameters, many `remember`s" is accurate, and it is
**structural, not cosmetic**. The code is well commented and feature-correct, the domain is pure and shared, and the
tests that exist are good. What is missing is a layer between the services and the screens, and a single owner for
each kind of state.

| Symptom | Measured | Root cause |
|---|---|---|
| Screens are long and hard to follow | 186 `remember {}` + 20 `rememberSaveable` + 37 `remember(keys)`, 255 `mutableStateOf`, 85 `LaunchedEffect`; RosterScreen 22 state cells, EventScreen 11 effects | Every screen does four jobs in one composable: fetch and derive data, hold form and dialog state, enforce rules, render. There is no presenter or form class. |
| "Many parameters" | Composables: EventScreen 13, MemberDialog 13, AttachmentsSection 13, CostScreen 10. Constructors: `Account` 8, `Attachments` 8, `ApiClient` 7, `SyncEngine` 7; server `Api` 11, `BandHandlers` 11. Data classes: `Member` 24, `Event` 23, `Band` 22, `PeriodSummary` 20, `SettlementWords` 19 | Services are drilled one by one from `AppGraph` through `Navigation.kt` into screens; classes are orchestration scripts over everything rather than owners of one thing; there is no request context on the server. The model classes are fine (they mirror the stored JSON). |
| Work is recomputed on every recomposition | `SettleScreen.kt:86` settles every active event per recomposition; `EventScreen.kt:176` settles per recomposition; `Can` built 13 times, 3 in EventsScreen alone; 43 rejection strings resolved per recomposition in every screen | `Band` is the only object handed around and has nowhere to hang a cache; no `BandView`. |
| Adding a feature touches many files | "Open a cost from a sync message" needs 7 files; a new server endpoint needs 6 edits incl. two 11-parameter constructors | Signals travel three ways (callbacks, public vars poked from outside, nullable `StateFlow`s cleared on consume); no routing table; no "band changed" hook. |
| The same rule in several places | fund on/off (`treasurerMemberId == null`) in ~17 places; "artist sees only own events" 6×; invite-role checks in reducer + server + app; two different accent-folding tables | Rules live in the reducer's 241-line `when`, in `Permissions`, in `domain/`, and in `composeApp/ui/model` (~320 lines of pure band logic). |
| Hard to learn where things go | `app/data/` holds storage, repositories, use cases, utilities and the DI root; `ui/model` holds domain queries, formatting, a composable text builder and a permission facade; `domain/` holds money maths, rule predicates and URL parsers | No stated layering; packages grew by iteration. |
| Nothing inside a screen is testable | 0 of 141 screen composables tested; `Navigation.kt` untested; only pure helpers pulled out of screens have tests | Logic is inside composable bodies. |
| Knowledge lives in 710 comment pointers to 3,033 lines of iteration docs | `ITERATION_5 §4.2` means "bullet 2 under heading 4"; `V5-M1 C2a` resolves to a code whose text was never committed | The spec was never consolidated; comments carry history instead of reasons. |

What is already right and must be kept: the shared `Engine` (permissions → reducer → limits) used identically by app
and server; the server never re-implements permissions; S3 ETag optimistic concurrency; offline-first operation sync
with holds; typed routes; `Actions`, `Can`, `Settings`/`settingsFor`, `HistoryController`, `ShareEditorState` as seeds
of the target design; `GoldenTest`/`InvariantTest`; black-box server tests through `Api.handle` with no mocking
library; `localServer` reusing `Api`.

---

## 2. Target architecture

This is the shape the code would have if written today from the consolidated spec
([`docs/refactoring/06-spec-from-iterations.md`](docs/refactoring/06-spec-from-iterations.md)). Each sub-section
names the concept, why it exists, and what it replaces. Sketches are illustrative; names are proposals.

### 2.1 Layering (all modules)

```
shared     model → ops → history → engine        (what an operation does, enforced once)
           model → settlement → rules → engine   (money maths, predicates, permissions)
           view   = memoised projections of a Band (BandView, EventView, Capabilities, summaries)
           text   = pure text utilities (names, phone, account, places, links)
app        platform ← storage ← session/sync/bands ← usecases ← runtime (AppState) ← ui
server     store ← <resource>/{Records, Handlers} ← BandChanged hook ← Router/Call ← Api ← lambda | localServer
```

Rules a reader can check: `shared` never imports `app`; nothing in `commonMain` names Android or the browser; `ui`
talks to use cases and read-only flows, never to `ApiClient` or `SyncEngine`; a screen composable has at most three
parameters.

### 2.2 Shared: slice the reducer, add a view layer, one home for rules

Serialization stays byte-identical: every `@Serializable` leaf already carries an explicit `@SerialName`, so classes
can move between packages and gain intermediate sealed parents without touching JSON.

- **Operation groups.** `sealed interface EventOp : Operation`, `CostOp`, `FundOp`, `RosterOp`, `PairOp`, `LineupOp`,
  `HoldingOp`, `TransferOp`, `AttachmentOp`, `BandOp`. `Operation.kt` (391 lines) becomes ten files.
- **Per-aggregate reducers.** `Reducer.apply` becomes a ~60-line dispatcher over the groups; each group has an
  `internal object XxxReducer` with one exhaustive `when` over *its* ops and its own validations. The 854-line file
  becomes ~11 files of 50–120 lines; the compiler still forces coverage when an op is added. Shared validation
  vocabulary (`validName`, `validShare`, the one `ID` regex also used by `Limits`) in `Validation.kt`; the
  pre/post guards (archived-event check, fund-needs-treasurer, `tidy`, `updatedAt` stamp) in `Guards.kt`.
- **`history/` package** for what is stored or sent: `OpEnvelope`, `BandChange`/`BandPart`/`BandSettings`,
  `Rejection`, `RecordedRejection`, `KapeluszJson`. This removes the `model → ops` and `domain → ops` cycles.
- **`rules/`**: `Permissions` indexed by op group (drops 70 import lines and a 29-branch `when`), `isLead` moved out of
  the reducer into `OpContext`, and **`Capabilities`** (today's app `Can`) so the app, server and shared tests ask
  the same object. Predicates the UI needs become plain functions here: `FundRules.isOn(band)`,
  `Visibility.canSee(band, me, event)`, `RoleChanges.refusal(band, member, role)`; the reducer, server and app call those
  instead of re-deriving them.
- **`view/`: `BandView`** built once per band state, immutable, with indexed lookups and memoised settlements:

  ```kotlin
  class BandView(val band: Band) {
      fun member(id: Id): Member?; fun cost(id: Id): Cost?; fun event(id: Id): EventView?
      fun settlement(event: Event): Settlement          // computed at most once per view
      val settledEventIds: Set<Id> by lazy { … }
      val costStatuses: CostStatuses by lazy { … }
      val fund: FundTotals by lazy { … }
      fun forUser(userId: String): MemberView            // me, role, visible events, Capabilities
  }
  ```

  `Summaries`, `PeriodSummary`, `EventSearch.searchableSummaries` and `ShareTargets` move here from
  `composeApp/ui/model` (they take a `Band` and return data; ~320 lines; their tests move with them). The app's
  `BandState.Ready` carries a `BandView`; screens stop calling `SettlementCalculator.settle` and stop building `Can`.
- **`settlement/`** keeps the maths (`SettlementCalculator`, `SettleTogether.split`, `CostLedger`, `BandFund` totals,
  `MemberShares`, `Payees`); **`text/`** keeps `Names`, `BlikPhone`, `BankAccount`, `Places`, `EventLinks`,
  `PlayedPlaces`, with one accent-folding table shared with search.
- **Safety net first**: a `WireFormatTest` that pins the JSON of a full `Band`, one envelope per operation type, and a
  `HistoryEntry` with a `BandChange`, plus an op-log replay golden. Nothing pins the stored format today; the golden
  tests guard arithmetic only.

### 2.3 App core: one app state, one sync state machine, intents instead of flags

- **`AppState`** is the one thing `App()` switches on:

  ```kotlin
  sealed interface AppState {
      data object Loading; data object UpdateRequired
      data class SignedOut(val everywhere: Boolean)
      data class SignedIn(val session: Session, val band: BandState)   // BandState: NoBand / Ready(view, me) / Unreadable
  }
  ```

  It replaces the `if/else` over `updateRequired`, `session`, `bandState`, `signedOutEverywhere` in `App.kt`.
- **No cycles, no forward references.** `createSession` moves to a tiny `SessionApi` so `TokenProvider` (today's
  `SessionRepository`) no longer depends on the full `ApiClient`, which depends on it. `ApiClient` exposes
  `signals: SharedFlow<ApiSignal>` (`UpdateRequired`, `SessionsRevoked`) instead of callbacks; `SyncScheduler`
  exposes `events` (`BandCreated`, `LostAccess`, `Rejected`, `Stuck`) and `DeviceRegistration` *observes* them
  instead of being called from six places. Then `AppGraph`/`AppRuntime` declares its objects in dependency order.
- **`ApiError`** sealed (`Http`, `NeedsSignIn`, `SessionsRevoked`, `UpdateRequired`, `Unreachable`, `Unreadable`)
  so the six `e.code == ApiErrors.X` string comparisons become one mapping.
- **`BandStore`** extracted from `BandRepository` (537 lines): persistence, backups, dirty/flush/retry, the key scheme,
  the "unreadable → backup → report" policy. The repository keeps memory state, replay/rebase, holds, and the
  `StateFlow<BandState>`, and launches no coroutines. One `JsonSlot<T>(store, key, serializer)` helper replaces the
  five copies of "read key, runCatching decode, getOrNull". Legacy migrations (`migrateUnscopedKeys`,
  `adoptLegacyBand`, ~45 lines) go, per the no-backward-compat rule.
- **Explicit sync state machine.** `SyncEngine` (439 lines) splits into `SyncRound` (the protocol: push, uploads, pull,
  band list, returning a `SyncReport`; testable with the existing `FakeServer` and no time control) and
  `SyncScheduler` (owns `SyncState`, the loop, backoff, drain, the wake channel). `start/stop/pauseAfterSending/
  resumeAfterSignIn/syncAsked/bandChanged` become `send(event)`; a pure `reduce(state, event) → (state, effect)`
  is unit-tested as a table. The five places that use `_status` as a guard collapse into `is Blocked`; `messaged`
  stops being a public var.
- **`AppIntent` + `IntentInbox` + `Navigator`** replace `Incoming` (seven nullable `StateFlow`s, a plain var, four
  writers of `navigatedForBand`, `signIns/handledSignIns`, `shownBandId` with a `"-"` sentinel):

  ```kotlin
  sealed interface AppIntent {
      data class JoinWithCode(val code: String)
      data class OpenEvent(val eventId: Id, val bandId: Id? = null, val section: EventSection? = null, val carry: Carry? = null)
      data class ShareFiles(val files: SharedFiles)
  }
  ```

  One `IntentRouter` composable at app level is the only consumer: it waits for prerequisites, resolves the band
  (today's `linkedEvent`), and executes one navigation command through `Navigator`, which is the only object that
  both opens bands and navigates. Section and "carry" (files for a new cost, the undo-attach snackbar) become
  **route arguments** of `EventRoute`/`CostRoute`, so the destination reads its own arguments and the `resumed` /
  "is it mine?" checks in `EventScreen`, `CollectedSection`, `CostScreen` disappear.
- **Use cases** (`SignIn`, `SignOut`, `OpenBand`, `CreateBand`, `JoinBand`, `LeaveBand`, `Edit`) replace `Account`,
  `Membership`, `Changes`, `EventLinkOpener`, the `openBand` lambda in `App.kt` and the two `createBand` sites.
  Each takes ≤3 collaborators and runs in the app scope by construction (the `scope.async {}.await()` trick lives in
  one base helper). Screens get narrow interfaces (`InvitesApi`, `PlacesApi`, `HistoryApi`, `CalendarApi`) instead of
  the whole `ApiClient`; no screen gets `SyncEngine`.
- **`AppRuntime`** (renamed `AppGraph`): wiring in dependency order, `load()`, `onForeground/onBackground/onHidden`,
  `syncInBackground()`. `MainActivity`, wasm `Main.kt` and `BackgroundSyncWorker` call only these.

### 2.4 UI: a session object, presenters, one message channel, typed navigation

- **`BandSession`** built once per `Ready` band, provided to screens instead of drilling `graph.*` + `state`:
  `view: BandView`, `me`, `can: Capabilities`, `money: MoneyFormat` (currency + language, so leaves take `Money`
  only), `actions`, `messages`, the use cases and narrow APIs. `EventContext` becomes `@Stable` and built once.
- **Per-screen `ViewModel`** (`androidx.lifecycle.ViewModel`, multiplatform; decision 2): scoped to the navigation
  back-stack entry with `viewModel()` inside `composable<Route>`, holding its state in `mutableStateOf`/`StateFlow`,
  exposing an immutable `UiState` plus intent functions, one sealed `dialog` cell instead of N booleans,
  `SavedStateHandle` for the route and for drafts that must survive process death, `viewModelScope` for its own
  work, **no Compose UI imports**, and a test. A screen composable then has three parameters `(session, route, nav)`,
  no `remember { mutableStateOf }` except purely visual toggles, and maps `UiState` to components.
  `settingsFor`/`Settings` is already close to this pattern; `CostViewModel`, `FundEntryViewModel`,
  `RosterViewModel` + `MemberViewModel`, `EventViewModel`, `EventsViewModel` follow it. The worked `CostScreen`
  rewrite (10 → 3 parameters, `save()` testable; written as a plain class, the ViewModel version is the same code
  inside `ViewModel`) is in [`docs/refactoring/01-ui.md` §3.4](docs/refactoring/01-ui.md). Why ViewModel over a
  hand-rolled presenter: it is the documented default for Compose, navigation scopes it for free, `SavedStateHandle`
  and `viewModelScope` replace today's `rememberSaveable` and `scope.async {}.await()` tricks, and the project
  already resolves `org.jetbrains.androidx.lifecycle` through navigation-compose.
- **`Messages` + `MessageHost` + typed `UiMessage`** (`Rejected(reason)`, `Text(res, args)`, `Undoable(…)`) replace
  the twelve per-screen `SnackbarHostState`s, the `Actions.dialogs`/`DialogNotice` registry, `AttachmentsSection.inDialog`,
  `MemberDialog.message`, `PaymentRow.copied`, the seven `.replace("%1$s", …)` sites and the string hoisting before
  every click handler. A `ModalMessages` wrapper renders posts inline inside a dialog.
- **Typed cross-screen results**: `EventSection` public in `Routes.kt`, `EventRoute(eventId, section?)`,
  `CostRoute(costId, forEventId, returnTo)`, typed `CostSaved` results; the `savedStateHandle` string keys and the
  section-name strings matched against a private enum go.
- **Per-screen navigator interfaces** (`CostNav`, `EventNav`, `TabNav`) implemented once by `AppNavigator`; tab screens
  stop taking `onSettings/onRoster` just to forward them to the top bar.
- **One permission style**: `Capabilities` only; raw `Permissions.isAllowed`, `role == Role.X`, `isArtist` and the
  `"probe"` literals outside `Can.PROBE` disappear from `ui/`.
- **Component catalogue**: `AppScaffold` (one width constant instead of five), `SegmentedChoice` (five hand-rolled
  copies), `AmountRow` (five variants), `CostRow` (four), `PartyAvatar` (five copies of the guest/not-in-app/photo
  choice), `BusyButton`/`rememberTask` (seven copies of working/failed + launch), `FormDialog`, `EmptyState`;
  `BackTopBar` in Summary and Played instead of raw `TopAppBar`s.
- **Packages**: `ui/model` splits into shared `view/` (pure, moved), `ui/format/` (money, dates, settlement text) and
  `ui/text/` (composables that return strings); `Common.kt` (563 lines) splits into `Actions.kt`, `MoneyInput.kt`,
  `Dialogs.kt`, `Buttons.kt`, `People.kt`; `SharedFilesDialog` leaves the root package; `EventTab` leaves `data/`.

### 2.5 Platform: one injected interface instead of 24 expect/actuals in five shapes

Twelve of the 24 actuals exist only to say "not on this platform", and common screens branch on `isAndroidApp()`,
`storeListing() == null`, `mapPickerAvailable`, `pdfInViewer`. Replace with one injected `Platform`:

```kotlin
interface Platform {
    val kind: PlatformKind; val store: KeyValueStore; val fileCache: ByteCache; val google: GoogleAuth
    val messages: MessageChannel?; val storeUpdates: StoreUpdates?; val reload: (() -> Unit)?; val storeListing: String?
    val maps: Maps?; val pdf: PdfRenderer?; val qrScanner: QrScanner?; val clipboard: Clipboard; val sharing: Sharing
    suspend fun uploadFile(url: String, headers: Map<String, String>, base64: String): Int
    @Composable fun rememberAttachmentPicker(onResult: (List<PickResult>) -> Unit): AttachmentPicker
    @Composable fun SignInButton(onIdToken: (String) -> Unit, onError: (String) -> Unit, modifier: Modifier)
}
```

`AndroidPlatform(app)` and `WebPlatform()` are plain classes; `LocalPlatform` for composables; `FakePlatform` in
`commonTest` makes `Attachments`, `AppRuntime`, `AppBanner`, `UpdateRequiredScreen` testable on the JVM. The three
Android globals (`AndroidPlatform.context`, `AndroidGoogleAuth.activity`, `AndroidAppGraph.instance`) and the two
default-constructor-argument back doors (`FileCache`, `::uploadFile`) go. `Media.android.kt` (338 lines, five
concerns incl. 65 lines of pure zoom maths) splits; `PdfZoom` and its test move to common.

### 2.6 Server: a request context, a route table, a document layer, one "band changed" hook

- **`Call`** (request, verified user, named path params, `installation`, `now`, `body()`, `param()`, `query()`) so every
  handler is `fun x(call: Call): HttpResponse`; today the same query parameter reaches handlers three different ways.
- **`Route` table** (method, pattern, `PUBLIC`/`USER`, handler) and a 30-line `Router` replacing the 27-arm `when`
  with hand-written segment matching; it can be printed and tested in one loop (every `USER` route without a token
  → 401, below the minimum version → 426, unknown band → 404).
- **`Document<T>`** over `BlobStore` (`read`, `update { }` with conflict retry and "unchanged → no write", `create`,
  `delete`) replacing the ten hand-written read–decode–modify–conditional-put sites (`retryOnConflict` 33×).
- **Repositories split from handlers**: `UserRecords` out of `UserHandlers` (used by six classes), `InviteCodes`,
  `HistoryLog`, `EventPointers`, `AttachmentFiles`. `BandHandlers` drops from 11 constructor parameters to 4.
- **`BandChanged.after(...)`** holds the ordered post-commit side effects (invites, pointers, files, deletion list,
  history, sync message, user band lists) once; `push`, `delete`, `leave`, `accept`, `deleteNow` call it instead of
  carrying partial copies.
- **`ServerConfig`** read in one place (`Handler`/`LocalServer` main) instead of `System.getenv` in nine; in-memory
  stores move to `main` so `localServer` can use them (`--memory`), with `NoopMessageSender` and `FileWebIndex` so
  nothing is `null` in any wiring; `ApiSupport` helpers public so `localServer` stops re-implementing query parsing
  and error JSON. One `ApiHarness` for the 13 test files that each carry their own `call/parse/setup/push`.

### 2.7 Conventions and tooling

- `ARCHITECTURE.md` with the module map, dependency direction, "where a new thing goes" table, platform boundary rule,
  comment policy, visibility (`internal` by default in `composeApp`), error/logging conventions.
- `SPEC.md` as the living functional spec (outline in the appendix), `DECISIONS.md` with a status column, `BACKLOG.md`
  for the "not now" items; `ITERATION_*.md` frozen under `docs/history/`; new decisions as short ADRs.
- Comment policy: say *why* in a self-contained sentence; no dates, no version history, no "was X until Y"; a doc
  pointer only to a stable heading and only when the sentence cannot carry the reason. Apply gradually: a touched file
  loses its `ITERATION_n §x` pointers.
- ktlint + `.editorconfig` (one formatting commit: 129 files have unsorted imports), detekt with the Compose rule set
  and a baseline, Compose compiler reports as a CI artefact, five Konsist layering assertions, `build-logic/`
  convention plugins with one `KapeluszConfig` (today the version code and Maps key are read in two build files with
  different precedence), Dependabot, CI building the release artefacts on PRs with an explicit Chrome setup.
- Resource keys `<area>_<element>[_<qualifier>]`, one file per area, `history_` instead of `h_`, a JVM test that fails
  on a key with no reference or missing in `values-pl` (four dead keys today).

---

## 3. Roadmap: ordered, shippable steps

Each row is one PR that compiles, passes `./gradlew check`, and leaves the app working. Effort: S ≤ half a day,
M = one to two days, L = three days or more, for a human who knows the code. Dependencies are explicit; phases can
interleave where no arrow connects them. Steps are numbered for reference in the decisions below.

### Working rules during the refactor (decided 2026-10-31)

- The CI `deploy` job (AWS deploy and Google Play upload) is commented out until the refactor ends; `main` is tested
  but not shipped. Uncomment it as it was to resume.
- One PR at a time, never stacked: branch from `main`, implement, independent review, fix, push, wait for CI green,
  merge, then the next branch.
- Every refactoring PR bumps `kapelusz.versionName` by one patch.
- Nothing from §8 is fixed on the way; all of it goes to `BACKLOG.md` and is revisited after the refactor.
- The infrastructure in `~/IdeaProjects/my-aws` is read-only reference material and is never changed.

### Phase F: formatting and static analysis (one PR, before Phase 0; decision 6)

| # | Step | Effort |
|---|---|---|
| F.1 | `.editorconfig` (`max_line_length = 140`, official Kotlin style, import layout `*`), the ktlint Gradle plugin wired into `check`, and one `ktlintFormat` commit (129 files have unsorted imports) | S |
| F.2 | detekt with the Compose rule set and a baseline, so only new findings fail CI | S |
| F.3 | `.github/dependabot.yml` for Gradle and GitHub Actions, weekly, grouped | S |

### Phase 0: safety nets and hygiene (all S)

| # | Step | Why first |
|---|---|---|
| 0.0 | This PR: the plan, the appendices, the paused `deploy` job, `PRO.md` untracked and ignored (it is on `main` since #124; its content stays in history) | done 2026-10-31 |
| 0.1 | Restore the `ANDROID_SERIAL` guard in `scripts/local-dev.sh` | `installDebug` otherwise installs on every connected device |
| 0.2 | `WireFormatTest` + fixtures in `shared/commonTest` (full `Band` JSON, one envelope per operation type, `HistoryEntry` with `BandChange`, op-log replay golden) | the only thing that proves "stored shape unchanged" through every later step |
| 0.3 | Server `ApiHarness`; migrate the 13 test files to it. Deferred to just before Phase 5 (decision 1: server work after the app) | every server step then has one harness to extend |
| 0.4 | Delete dead code: `awaitPushed`, `LOCAL_USER_ID`, `settingsForBackground`, the legacy migrations and their two tests, four dead string keys; fix the three doubled doc comments; replace the nine hand-written `CancellationException` rethrows with `runSuspendCatching` | behaviour-neutral, shrinks what the next steps touch |

### Phase 1: shared (the foundation the UI needs)

| # | Step | Depends on | Effort | Risk |
|---|---|---|---|---|
| 1.1 | Split `Operation.kt` into per-aggregate files with the intermediate sealed interfaces | 0.2 | S | low (0.2 proves discriminators) |
| 1.2 | Split `Reducer.kt` into per-aggregate reducers + `Validation.kt` + `Guards.kt`; mechanical cut-and-paste | 1.1 | M | low (225 tests + replay golden) |
| 1.3 | `history/` package; move `Rejection`, `OpEnvelope`, `BandChange`, `KapeluszJson`; `isLead` into `OpContext`; `Rejecting` helpers internal | 1.2 | S | low (imports only outside shared) |
| 1.4 | `Permissions` by group; `Capabilities` in shared (from app `Can`, with `CanTest`); `Engine.validateNew`; one `RoleChanges.refusal` predicate used by reducer, server and app | 1.3 | M | low-medium |
| 1.5 | `BandView`/`EventView` in `view/`; settle-calling helpers take the view; `CostLedger.paid/unpaid/status` deleted; reducer settles once per state | 1.2 | M | medium (an immutable view per band instance; the one trap is reusing a view after mutating `band` inside a loop) |
| 1.6 | App adopts the view: `BandState.Ready(view, me)`; `remember(view)`; move `Summaries`, `PeriodSummary`, `EventSearch`, `ShareTargets` and their tests to shared; one accent-folding table | 1.5 | M | medium (largest diff, mechanical for screens) |
| 1.7 | Package tidy (`domain/` → `settlement/`, `rules/`, `text/`); tests into mirrored sub-packages with explicit actors where roles matter | 1.6 | S | low, noisy |
| 1.8 | Typed ids (`value class PartyId`, `MemberId`, `EventId`, `CostId`), JSON-identical (decision 7) | 1.7 | M | medium (many signatures) |

### Phase 2: UI (the felt pain; 2.1–2.3 are mechanical and give most of the payoff)

| # | Step | Depends on | Effort | Risk |
|---|---|---|---|---|
| 2.1 | `Messages` + `MessageHost` + typed `UiMessage`; `Actions` keeps its API | — | M | low-medium (undo hold timing must stay identical; keep `UndoStack`) |
| 2.2 | `BandSession` + navigator interfaces; `Navigation.kt` passes `(session, route, nav)`; `TopBarState` callbacks folded into the session; screen bodies unchanged | 1.6 preferred (so the session carries `view` and `can`), works without | M | low |
| 2.3 | Typed cross-screen results: `EventSection` in `Routes.kt`, `CostReturn`/`CostSaved`, no `savedStateHandle` strings | 2.2 | S | low |
| 2.4 | Pure logic out of screens into shared `view/`/`rules/` with tests: settle-screen queries, hand-over queries, `keepsForFund`, cost payer/event choices, roster grouping, cost status text rules, `HistoryUndo`; delete raw permission checks and `"probe"` literals | 1.4, 1.6 | M | low (pure moves; screens lose 30–40 % of lines) |
| 2.5 | Component catalogue (`AppScaffold`, `SegmentedChoice`, `AmountRow`, `CostRow`, `PartyAvatar`, `BusyButton`, `FormDialog`, `EmptyState`, `LocalMoneyFormat`) | 2.2 | M | low |
| 2.6 | ViewModels, one PR each: `CostViewModel`, `FundEntryViewModel`, `RosterViewModel` + `MemberViewModel`, `EventViewModel` (pager, sections, `@Stable` context built once), `EventsViewModel`; `HistoryController` becomes `HistoryViewModel`; `lifecycle-viewmodel-compose` added | 2.1–2.4 | L total (M each) | medium (behaviour parity, rotation, web back) |
| 2.7 | Sealed dialog cells in the remaining sections; one `rememberResolved(dialog)` helper replaces the eight `LaunchedEffect(Unit) { onDismiss() }` guards | 2.6 | S | low |
| 2.8 | `SettingsViewModel` on `BandSession`; refusals routed through `Messages` (today discarded) | 2.6 | S | low |
| 2.9 | Package reorganisation (`ui/model` split, `Common.kt` split, `SharedFilesDialog` moved) | 2.8 | S | trivial, last because churn-only |

### Phase 3: app core

| # | Step | Depends on | Effort | Risk |
|---|---|---|---|---|
| 3.1 | Callbacks → flows (`ApiClient.signals`, `SyncEngine.events`); `messaged` no longer a public var | 0.4 | S | low |
| 3.2 | `SessionApi` breaks the sessions↔api cycle; `AppGraph` in dependency order, no forward references | 3.1 | S | low |
| 3.3 | `AppState` sealed; `App.kt` switches on it; `updateRequired`/`signedOutEverywhere` private; `AppStateTest` | 3.2 | S | low |
| 3.4 | `BandStore` extracted; `JsonSlot`; repository launches no coroutines; `StorageKeys` | 0.4 | M | medium (the write-pass identity check moves verbatim) |
| 3.5 | `SyncRound` + `SyncScheduler` behind the existing façade (all 32 `SyncEngineTest`s unchanged), then `SyncState` + `reduce()` + `send(event)`, then drop façade methods; `SyncStateTest` table; inject a dispatcher so tests use virtual time | 3.1 | L | medium-high (biggest behavioural surface; the façade sub-step is the net) |
| 3.6 | `IntentInbox` + `IntentRouter` + `Navigator`; `Incoming`, `navigatedForBand`, `signIns/handledSignIns`, `shownBandId` deleted; section/carry as route args; `IntentRouterTest`; manual smoke list (join link signed out/in, event link to another band, share to new/existing cost, undo-attach, rotate during each) | 2.3, 3.3 | L | high (UI-visible on two platforms; own PR) |
| 3.7 | Use cases replace `Account`/`Membership`/`Changes`/`EventLinkOpener`/`openBand`; narrow API interfaces for screens | 3.6 | M | medium (wide, mechanical) |
| 3.8 | `DeviceRegistration` as a reaction to (session, band count, token); six explicit `register()` calls removed | 3.7 | S | low-medium |
| 3.9 | `AppRuntime` lifecycle API; `MainActivity`, `Main.kt`, `BackgroundSyncWorker` stop reaching into internals | 3.8 | S | low |

### Phase 4: platform and build

| # | Step | Depends on | Effort | Risk |
|---|---|---|---|---|
| 4.1 | `build-logic/` convention plugins; one `KapeluszConfig`; fix the Maps-key precedence mismatch; CI builds release artefacts on PRs with explicit Chrome | 0.5 | M | low |
| 4.2 | `Platform` step A (non-composable members: store, cache, google, messages, reload, listing, upload, install state); `AppRuntime(platform)`; `LocalPlatform`; `FakePlatform` + first `AttachmentsTest`; Android globals deleted | 3.2 | M | medium (touches sign-in and sync wiring) |
| 4.3 | `Platform` step B (maps, pdf, QR, store updates, clipboard, sharing, picker, sign-in button); remaining `expect`s and `-Xexpect-actual-classes` removed | 4.2 | M | medium (`rememberCopy` has seven call sites; clipboard first, maps/pdf last) |
| 4.4 | File reorganisation of `platform/` and `data/` → `storage/`, `session/`, `bands/`, `util/`; `PdfZoom` to common (**done**: `data/` dissolved into `storage/ auth/ bands/ attachments/ devices/ settings/ runtime/ util/` after §3.1, `Account` joined the existing `auth/` rather than a new `session/`; `PdfZoom` and its test in common `ui/components`) | 4.3 | S | low |
| 4.5 | Test fixtures: `FakeServer` to `commonTest/testing/`, `InMemoryStore` out of `main`, virtual time in `SyncEngineTest`, injected FCM deadline (drops a 5 s sleep) (**done**: the fake server answers on `Dispatchers.Unconfined`, so the three tests that polled real time run on `runTest`'s clock; the FCM deadline was already injected, the fake server's slow answer now waits for the test's end instead of 5 s) | 3.5 | M | medium |
| 4.6 | Five Compose UI smoke tests on the JVM (`runComposeUiTest`): sign-in, zero screen, event opens, settings pages, update-required (decision 10) (**done**, in the browser rather than on the JVM: the app has no JVM target and the Android host tests have no Activity; `wasmJsTest/.../ui/SmokeTest.kt`; it runs where the web tests run, under a headless Chrome, and is skipped where there is none. Found and fixed a regression of 3.6: the web's navigation has no type for an enum route argument, so `EventRoute.section` is carried by name) | 2.6 | M | low |

### Phase 5: server

| # | Step | Depends on | Effort | Risk |
|---|---|---|---|---|
| 5.1 | `ApiSupport` public; shared `parseQuery`/`error`/OPTIONS between Lambda and local adapters | 0.3 | S | none |
| 5.2 | `Document<T>`; convert the ten read–modify–write sites | 5.1 | M | low |
| 5.3 | Repositories split from handlers (`UserRecords`, `InviteCodes`, `HistoryLog`, `EventPointers`, `AttachmentFiles`) | 5.2 | M | low |
| 5.4 | `BandChanged.after(...)`; pin the side-effect order with a test first ("message before forgetting devices") | 5.3 | M | medium |
| 5.5 | `Call` + `Route` table + `Router`; `RoutesTest` pinned before the rewrite. **Done**: route table d0d9e11; `Call(request, user, params, installation)` and `fun handler(call: Call)` for every signed-in route, `Route`/`Router` as data (no `now` on `Call`: handlers keep their `clock`, read where they need it) | 5.3 | M | medium (trailing slash, HEAD) |
| 5.6 | `ServerConfig`; in-memory stores to `main`; `NoopMessageSender`, `FileWebIndex`, `--memory` (**done**: `Config.kt`, env read only in `Handler` and `LocalServer.main`, `NoWebIndex`/`NoopMessageSender` so nothing is null in a wiring, AWS region passed in); packages per resource and the multi-concern files split (**done**, 5.6b: `store/ auth/ users/ bands/ history/ invites/ attachments/ calendar/ places/ links/ devices/ messaging/ lambda/`, root keeps `Api`, `Router`, `Http`, `ApiSupport`, `ServerConfig`; tests stay in the root test package) | 5.5 | M | none |

### Phase 6: documentation

| # | Step | Depends on | Effort |
|---|---|---|---|
| 6.1 | `BACKLOG.md` first, with every §8 item (decision 12); then `SPEC.md` seeded from [`docs/refactoring/06-spec-from-iterations.md`](docs/refactoring/06-spec-from-iterations.md) (its Part 1 and Part 3), `ARCHITECTURE.md`, `DECISIONS.md`, `BACKLOG.md`; `ITERATION_*.md` to `docs/history/`; README updated (**done**: the four documents at the root, the iterations under `docs/history/`, README's Documents section) | — | L |
| 6.2 | Comment rewrite, gradually ("a touched file loses its pointers"); resource key renaming to `<area>_` with the dead-key test (decision 9), last (**in progress**: the key check `checkStringKeys` runs in `check`, no dead keys were left; every `ITERATION_n §x` pointer outside the history and the four documents is the short citation `In §x` that `SPEC.md` cites inline, so a search there finds the behaviour; a mapping to spec sections by citation frequency was tried and dropped, a third of a sample pointed at the wrong section) | 6.1 | L spread over time |

**Recommended order** if one person does this sequentially: 0 → 1.1–1.6 → 2.1–2.4 → 3.1–3.4 → 2.5–2.6 → 3.5–3.7 →
4.2–4.3 → 5 → the rest. After 0, 1.1–1.6 and 2.1–2.4 the code already reads as "screens are thin, logic is in shared,
one state per band" and the `remember`/parameter complaints are mostly gone; 2.6 and 3.5–3.6 are the two expensive,
riskier blocks and each deserves its own review.

---

## 4. Decisions taken (2026-10-31)

| # | Question | Decision |
|---|---|---|
| 1 | Order and scope | The recommended order (Phase F, 0, 1.1–1.6, 2.1–2.4, 3.1–3.4, 2.5–2.6, 3.5–3.7, 4.2–4.3, 5, rest). Server work continues after the app; the route table already landed. |
| 2 | Presenter mechanism | `androidx.lifecycle.ViewModel` (multiplatform), one per screen, scoped to the navigation entry. There had been no conscious decision for hand-wiring; for a new project ViewModel is the documented default. |
| 3 | Where `Capabilities` lives | `shared/rules/`. |
| 4 | `BandView` | In `shared/view/`. |
| 5 | Legacy migrations | Delete (`adoptLegacyBand`, `migrateUnscopedKeys`, `ApkLeftovers`, web `forgetStoredDismissal`); every user is on 1.33 or newer. |
| 6 | Formatting and static analysis | Yes: ktlint, detekt with a baseline, `.editorconfig`, Dependabot, as a separate PR before Phase 0 (Phase F). |
| 7 | Typed ids | Yes, value classes (step 1.8). |
| 8 | Documentation split | Yes: `SPEC.md`, `ARCHITECTURE.md`, `DECISIONS.md`, `BACKLOG.md`; `ITERATION_*.md` become history under `docs/history/`. |
| 9 | Resource key renaming | Yes (step 6.2). |
| 10 | Compose UI smoke tests | Yes (step 4.6). |
| 11 | Version bumps | A single patch bump per refactoring PR. |
| 12 | Noticed-not-fixed items | All to `BACKLOG.md`; revisited after the refactor. |

## 5. What a feature costs after the refactor (two worked examples)

**"Open a specific cost from a sync message."** Today: an `Incoming` flow, a writer in `SyncMessagingService`, an entry
in `MainActivity`/`Main.kt`, a `LaunchedEffect` in `Navigation.kt` with the `navigatedForBand` dance and a `hasStored`
guard, a `resumed` check in the destination, a clear-on-sign-out line in `Account`, a sessionStorage mirror on the web:
seven files. After: `AppIntent.OpenCost(costId, bandId)` (one line), one `when` branch in `IntentRouter`, one `Navigator`
method; the platform entry points already call `intents.offer(...)`.

**"A new server endpoint `GET /bands/{id}/members/{memberId}/summary`."** Today: a `when` arm with hand-written
segment matching, a handler method, possibly a new constructor parameter threaded through `Api` and `BandHandlers`,
a copy of the role-check snippet, a copy of the read–decode snippet, a test file with its own harness. After: one
`Route` line, one `fun summary(call: Call)` using `call.param("memberId")` and `BandView`, one test on `ApiHarness`.

---

## 6. Risks and how each step is protected

- **Stored JSON** (the hard constraint): `WireFormatTest` (0.2) runs on every PR; the shared steps are moves and
  wrappers, never field changes; kotlinx collects sealed subclasses recursively so intermediate interfaces are
  invisible to JSON.
- **Settlement arithmetic**: `GoldenTest` G1–G12, `InvariantTest`, `SettleTogetherTest`; the reducer split is
  cut-and-paste; `BandView` memoisation cannot change results, only when they are computed.
- **Sync behaviour**: 32 `SyncEngineTest`s stay green against the façade before the internals change (3.5); the
  state machine gets its own table test.
- **UI behaviour**: presenters are introduced one screen per PR with the existing screen as the reference; the
  intent router (3.6) is the one UI-visible step and ships with a manual smoke list on both platforms.
- **Server**: all steps are black-box tested through `Api.handle`; the route table and the side-effect order are
  pinned by tests written *before* the rewrite (5.4, 5.5).

---

## 7. Numbers to watch (before → target)

| Metric | Now | Target after Phases 1–3 |
|---|---|---|
| Composables with > 6 parameters (excl. `Modifier`) | 11 | 0 |
| `remember { mutableStateOf }` in `ui/screens` | ~255 cells | visual toggles only (≈ 30) |
| `LaunchedEffect` in `ui/screens` | 85 | ≈ 20 (loads and focus) |
| Settlement calls per recomposition | 2 unmemoised + per-row | 0 (all via `BandView`) |
| Places where "fund is on" is derived | ~17 | 1 (`FundRules.isOn`) |
| Permission check styles in `ui/` | 3 | 1 |
| Mutable cross-component signals + globals in the app core | 21 + 6 | `AppState`, `SyncState`, `IntentInbox` |
| Largest file | `Reducer.kt` 854 | ≤ 300 lines anywhere |
| Screen logic under test | 0 of 141 composables | every presenter/form |
| Constructor parameters (`Account`, `Attachments`, server `Api`, `BandHandlers`) | 8, 8, 11, 11 | ≤ 4 each |

---

## 8. Noticed, not fixed

Possible bugs, races and gaps seen while reading. None are fixed here. Grouped by module, deduplicated across the six
reviews; the full lists with context are in the appendices. Items marked ⚠ look user-visible.

### 8.1 Hygiene

- `PRO.md` was tracked from f003fcc and reached `main` through #124; untracked and ignored in step 0.0 (its content
  remains in the repository history).
- `scripts/local-dev.sh:21-25` has the `ANDROID_SERIAL` guard commented out, so `installDebug` installs on every
  connected device.

### 8.2 UI (`composeApp/.../app/ui`)

1. ⚠ `CostScreen.kt:153`: shared photos for a new cost are consumed inside `remember {}` and are not saveable; an
   Android rotation of the form loses them.
2. ⚠ `EventDialogs.kt:102-111`: one Save can emit `UpdateEvent` + `SetEventAgenda`, so History shows two lines and
   Undo needs two taps.
3. ⚠ `SettingsWiring.kt:61-63` + `FundSettings.kt:101-103`: rejections are discarded; a refused `SetTreasurer` silently
   flips the switch back.
4. ⚠ `RosterScreen.kt:277` + `Common.kt:433`: `TextInputDialog` always dismisses after confirm, so a refused `AddMember`
   loses the typed name.
5. `EventScreen.kt:184`: `tabs.associateWith { rememberLazyListState() }` calls a composable in a loop; when the tab
   list shrinks (role becomes Artist while open) the remaining tabs take the wrong scroll states.
6. `HistorySection.kt:42` passes `me?.userId.orEmpty()` where `HistoryScreen.kt:109` passes `state.userId`; for an
   unlinked user the pending undo line is attributed to user `""`.
7. `SettleScreen.kt:86-111`, `EventScreen.kt:176,250,270`, `HistoryScreen.kt:162`, `Common.kt:200`,
   `Navigation.kt:261`: settlement, `Can` and 43 rejection strings recomputed per recomposition (performance).
8. `EventsScreen.kt:161`: event summaries (settles every visible event) run on the main thread on every band change.
9. `RosterScreen.kt:462,605`: `MemberDialog.message` is never auto-cleared; stale messages stack.
10. `RosterScreen.kt:562-570`: client pre-checks duplicate server rules with their own copies of the rejection texts.
11. `AttachmentsSection.kt:162,218`: `viewable` computed twice; editing one opens the viewer on the wrong index.
12. `WebInstallHint.kt:43`: process-global mutable state survives sign-out/sign-in.
13. `JoinDialog.kt:84`: a second join link arriving while the dialog is open changes `initialCode` but not `code`.
14. `CostScreen.kt:165`: after the payer list shrinks to one *other* member, `paidBy` can point at someone not offered
    and the dropdown shows a blank label.
15. `BandFundScreen.kt:448-450`: after a partial failure among several payers, editing the amount before retrying can
    double-post the entries that succeeded.
16. `ParticipantsSection.kt:182-188`: `ChangeLineup` is dispatched from a `LaunchedEffect` one frame after the dialog
    closes, only to resolve its message; a fast Back could drop it.
17. `App.kt:175-189`: `TopBarState` is a new object on every sync-status tick, recomposing every tab's top bar.

### 8.3 App core (`composeApp/.../app/{data,sync,auth}`)

1. ⚠ `BandRepository.kt:206`: `_state.value as BandState.Ready` unchecked; `readLocal` sets `currentBandId` and
   `Unreadable` together, so a dispatch from a still-visible screen in that window throws.
2. ⚠ `Account.kt:70-75`: sign-out clears only `pendingOpenEvent`; `sharedFiles`, `sharedHandover`, `sharedAttached`,
   `pendingEventSection` survive and can be delivered to the next account's band.
3. `Account.kt:96`: `pictureSent` never reset on sign-out; a second account in the same process never sends its photo.
4. `Session.kt:96-118` + `ApiClient.kt:189`: `refreshLock` is held across Google UI and the network exchange; every API
   request waits on it.
5. `EventLinkOpener.kt:26` + `SyncEngine.kt:264,285`: the foreground event-link flow uses `syncAll`, whose background
   presentation hides `NEEDS_SIGN_IN`; the user sees "offline" instead of "sign in again".
6. `SyncEngine.kt:154-173`: `bandChanged` launches a round that `stop()` does not cancel; it can run with the Android
   app in the background.
7. `SyncMessaging.android.kt:60-62`: `onNewToken` in a cold process registers nothing until the next `onStart`.
8. `PlacesLocator.kt:64`: the only persisted key without a user id; a second account inherits the first one's cache.
9. `BandRepository.kt:434,439`: every change launches a new `flush()` coroutine; a burst queues N flushes.
10. `MainActivity.kt:21`: `hasUnsentWork()` reads `bands.locals`, not cleared on sign-out; a background sync can be
    scheduled for a signed-out user (exits early).
11. `AttachmentUploads.kt:100`: the whole base64 file is held in memory for the upload; no size guard on this path.
12. `AppGraph.kt:38,50,56`: forward references inside lambdas; safe only while nothing invokes them during construction.
13. `BandStore.flush` (3.4) captures the user once but reads the repository's objects live: a `load()` of another user
   while a write is suspended could write the new user's data under the old keys (pre-existing shape). Same family:
   `loseAccess`/`startOver` remove a file outside `writeLock`, so an in-flight write can re-create it. Fix: check the
   user per write and run `remove` under the lock, once the sync state machine (3.5) settles.

### 8.4 Shared

1. ⚠ `SettleTogether.kt:142-143`: with two artists paired, the leftover goes to an artist and `validateArtistsPaid`
   refuses the whole transfer; a pair of two artists can never settle a surplus (`canPair` allows two artists).
2. ⚠ `SettleTogether.kt:52-54`: `tidy` drops an invitation when the invitee has any other pending one, so two
   concurrent invitations to the same person cancel both silently.
3. `SettleTogether.kt:73-76`: `keepSettled` does not stamp `updatedAt`, unlike every other event mutation.
4. `Reducer.kt:286-289` + `Undo.kt:115`: a no-op operation (e.g. same weight again) stamps `updatedAt` and thereby
   blocks a later undo of an earlier change on that event.
5. `Reducer.kt:323`: "not yourself; leave instead" reported as `MEMBER_HAS_HISTORY`; `Reducer.kt:122,294`: missing
   pair reported with code `CONFLICT` and reason `NOT_FOUND`.
6. `Reducer.kt:606-661`: `settleSuggestions` settles the same event three times per suggestion; `recordTogether`
   twice; `SettlementCalculator.kt:90,187`: `parties` computed twice (performance).
7. `CostLedger.kt:30-35`: `paid/unpaid/status` each settle the whole band (O(costs × events × settle)).
8. `SettlementCalculator.kt:139,155`: with percentages at exactly 100 % including the fund's, rounding leftovers go to
   participants only; the fund never gets its leftover grosze (probably intended; worth stating).
9. `Limits.kt:52-59`: relies on `!==` list identity; a future early-return no-op would skip limit checks.
10. `Limits.kt:38` vs `Reducer.kt:264`: the attachment-id regex twice with different rejection reasons.
11. `Permissions.kt:137`: a viewer who was a participant before becoming a viewer is refused `AddHolding` by role,
    consistent but a hole in "viewers take no part" (`Viewers.mayBecome` checks only unsettled events).
12. `Engine.kt:75`: more than 20 rejected ops in one lost response come back as plain duplicates with no reason.
13. `Names.kt:13-14` vs app `EventSearch.kt:56-75`: two divergent accent-folding tables (sort and search disagree on
    `ß`, `ā`, `ø`, `ő`).

### 8.5 Server

1. ⚠ `InviteHandlers.kt:48-53`: the invite is written before the band's code list; if `updateCodes` exhausts its
   retries the invite stays valid for 72 h but is invisible to `list`, `revokeFor` and `deleteNow`.
2. `BandAccess.kt:18-23` + `LinkPages.kt:57`: any read, including anonymous `/e/{id}` and calendar fetches, can execute
   a full band deletion in-request; the `deleteIfDue=false` convention is the only thing preventing a self-deadlock
   from the `writers` pool.
3. `BandHandlers.kt:123-124`: a failed history append is logged and dropped with no later repair; a DynamoDB outage
   leaves permanent gaps undo cannot see.
4. `BandHandlers.kt:78,83`: the band is loaded twice per push (two S3 reads even without undos); `:43,46`:
   `Band.create` built twice.
5. `UserHandlers.kt:66-73`: `rememberPicture` repeats every band update on each conflict retry.
6. `HistoryHandlers.kt:48-56`: history pages are fetched before the membership check; `reader` is a JVM-wide pool of 3.
7. `Handler.kt:146,151`: hard-coded production bucket/table fallbacks; a misconfigured environment silently talks to
   production.
8. `Api.kt:133-137`: `appVersion=0` is always accepted, so the minimum-version gate is advisory (fine for forcing
   updates, not for retiring a format).
9. `Sessions.kt:104`: the revocation cache grows without bound (unbounded in `localServer`).
10. `places/PlacesHandlers.kt` (`locations`): `LocalDate.now()` instead of the injected clock; untestable with fixed clocks.
11. `LinkPages.kt:92-96`: `inParallel` blocks on each future with no timeout.
12. `ApiModels.kt:50`: `ApiErrors.BAND_TOO_LARGE` is in the contract but never sent.
13. `LocalServerTest.kt:134` sends `?v=0` while the parameter is `appVersion`; passes only because local has no minimum.
14. `devices/DeviceRegistry.kt` (`prune`): lazily pruned band ids can keep accepting registrations for a user who left their last band.
15. `BandChanged.kt:46-47`: `pointers.pointTo`/`forget` are the only consequences not in `runCatching`; a DynamoDB
    failure there answers the push with 500 although the band is saved, so the device pushes the batch again (all
    duplicates).
16. `BandChanged.kt:59-65`: the sync message goes to an unlinked user's devices before `removeBandFromUser`; a device
    that reacts at once can still see the band in `GET /me` (it learns `band_not_found` from the band itself anyway).

### 8.6 Platform, build, tests

1. `androidApp/build.gradle.kts:20-21` vs `composeApp/build.gradle.kts:18-20`: a Maps key in `local.properties` yields
   `HAS_MAPS_KEY = true` with an empty manifest placeholder (blank map); default version code 1 vs 0.
2. `AppLocale.android.kt:19-27`: `Locale.setDefault` and `updateConfiguration` run in the composable body on every
   recomposition; a later system-language change is ignored until restart.
3. `Media.android.kt:306` reads `AndroidPlatform.context` (null before the graph exists) while `PdfPages` uses
   `LocalContext` in the same file.
4. `Share.android.kt:16-18`: `rememberShare` always reports `SHARED`; the chooser `startActivity` is unguarded.
5. `StoreUpdates.android.kt:32`: `LocalContext.current as? Activity` is null inside a `Dialog`.
6. `SyncEngineTest.kt:705-707,739-758`: wall-clock polling and sleeps with a documented CI flake;
   `FcmSenderTest.kt:33`: a 5 s sleep in every run.
7. `composeApp/build.gradle.kts:85-90`: wasm browser tests silently skip without Chrome; CI has no explicit setup.
8. `KeyValueStore.kt:10`: `InMemoryStore` (a test double) ships in the app.
9. `platform/Updates.kt:26`: `PLAY_PUBLIC = false` must be flipped by hand at the production release; README is silent.
10. `InstallHints.wasmJs.kt:28-31` and `ApkLeftovers.kt`: migration code that runs forever; both are backward-compat
    code the no-backward-compat rule says not to carry.
11. `AppGraph.kt:35`, `BandRepository.kt:493`: `println` logging; in a release build on Android nobody sees unhandled
    coroutine failures or storage write failures.
12. Dead string keys `move_down`, `notifications_debts`, `share_fixed`, `share_weight`; README lists only four of the
    eight iteration documents.

---

## 9. Appendices (in `docs/refactoring/`)

| File | Content |
|---|---|
| [`01-ui.md`](docs/refactoring/01-ui.md) | UI inventory with counts, the smells with file:line, the worked `CostScreen` rewrite, the nine-step UI migration, 30 noticed items |
| [`02-app-core.md`](docs/refactoring/02-app-core.md) | Class map, every behaviour-driving flag with writers/readers, lifecycle as it runs, the `AppState`/`SyncState`/`IntentInbox` design, ten-step migration |
| [`03-shared.md`](docs/refactoring/03-shared.md) | Package graph, public API surface actually used, reducer organisation, duplicated rules, the `BandView`/`Capabilities`/per-aggregate reducer design, ten-step migration |
| [`04-server.md`](docs/refactoring/04-server.md) | Routing and handler conventions, storage abstractions, concurrency, the `Call`/`Route`/`Document`/`BandChanged` design, nine-step migration |
| [`05-crosscutting.md`](docs/refactoring/05-crosscutting.md) | expect/actual inventory, build and CI, resources, tests, comment policy, tooling, eleven-step migration |
| [`06-spec-from-iterations.md`](docs/refactoring/06-spec-from-iterations.md) | The consolidated functional spec (21 feature groups with citations and superseded blocks), 19 technical decisions in force, a ~90-item regression checklist, the accuracy of each ITERATION document, and the proposed `SPEC.md`/`ARCHITECTURE.md` outlines |
