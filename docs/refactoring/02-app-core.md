# Review: non-UI app layer (`composeApp/.../app/{data,sync,auth}`, `App.kt`, `Navigation.kt`, platform entry points)

Scope read in full: AppGraph, Account, Membership, Changes, Incoming, DeviceMessages, Attachments, AttachmentUploads, AwaitPushed, EventLinkOpener, FileStore, KeyValueStore, IndexedDbStore, PlacesLocator, SettingsRepository, SuspendCatching, Platform, BandRepository, SyncEngine, ApiClient, HistoryDecoder, BackgroundSyncWorker, SyncMessaging.android, Session, AndroidGoogleAuth, WebGoogleAuth, DevSignIn, Main.kt (wasm), AndroidAppGraph, MainActivity (androidApp), App.kt, Navigation.kt, AppDialogs.kt, SharedFilesDialog.kt (consumer view), and the tests SyncEngineTest, BandRepositoryTest, SessionRepositoryTest.

Numbers for orientation: the common app layer (data+sync+auth) is 2 532 lines in 23 files; the UI is 13 182 lines. `AppGraph.kt` has been touched in 47 of 505 commits, `Navigation.kt` in 74, `App.kt` in 56, `ApiClient.kt` in 30, `SyncEngine.kt` in 29, `BandRepository.kt` in 26 — i.e. the wiring and the two composition roots churn far more than the domain-ish classes, which is the measurable symptom of "adding a feature means touching the hub".

---

## 1. Inventory

### 1.1 Classes and what each owns

| Class (file) | Lines | Owns (flows / mutable vars / locks) | Constructor inputs |
|---|---|---|---|
| `AppGraph` (data/AppGraph.kt) | 115 | `updateRequired: MutableStateFlow<Boolean>` :41; `loaded` :69, `loadLock: Mutex` :70, `backgroundUser` :71; the app `scope` (SupervisorJob + Dispatchers.Main) :35 | store, google, http, messageChannel |
| `SessionRepository` (auth/Session.kt) | 192 | `_session: MutableStateFlow<Session?>` :72; `refreshLock` :74; `lastBackgroundRefresh` :77; `lastExchangeFailure` :80 | store, google, clock, `exchange` lambda (→ ApiClient) |
| `ApiClient` (sync/ApiClient.kt) | 274 | stateless, but 2 outbound callbacks `onUpdateRequired` :84, `onSessionsRevoked` :86 and the inbound `token` lambda :94 | http, baseUrl, appVersion, onUpdateRequired, onSessionsRevoked, installation, token (7) |
| `BandRepository` (data/BandRepository.kt) | 537 | `_state: BandState` :85; `_index: BandIndex?` :88; `locals: MutableMap` :91; `dirty: MutableSet` :92; `indexDirty` :93; `userId` :94; `writeLock` :95; `lastOpId` :196; `retrying` :497 | store, scope, idGenerator, clock |
| `AttachmentUploads` (data/AttachmentUploads.kt) | 143 | `lock` :34; `userId` :35; `_pending: MutableStateFlow<List<PendingUpload>>` :36; `attaching: MutableSet` :50 | store, api, bands, put |
| `PlacesLocator` (data/PlacesLocator.kt) | 70 | `lock` :33; `known: MutableMap?` :34 | store, api |
| `SyncEngine` (sync/SyncEngine.kt) | 439 | `_status: SyncStatus` :69; `_rejections: SharedFlow` :72; `_lostAccess: SharedFlow` :77; `_stuck: Map<Id,StuckPush>` :82; `runLock` :90; `wakeUp: Channel` :91; `loop: Job?` :92; `drain: Job?` :93; **public `var messaged`** :99 | api, bands, scope, interval, uploads, clock, onBandCreated (7) |
| `DeviceMessages` (data/DeviceMessages.kt) | 49 | `registration: Job?` :21 | channel, sessions, api, sync, scope |
| `Changes` (data/Changes.kt) | 47 | none | bands, sync |
| `Incoming` (data/Incoming.kt) | 61 | 7 nullable `MutableStateFlow`s + 1 plain `var` (see §1.3) | — |
| `Account` (data/Account.kt) | 108 | `signedOutEverywhere` :30; `_signIns` :32; **public `var handledSignIns`** :38; `pictureSent` :96 | scope, sessions, api, bands, uploads, sync, messages, incoming (8) |
| `Membership` (data/Membership.kt) | 63 | none | scope, api, bands, sync, messages |
| `Attachments` (data/Attachments.kt) | 108 | none | bands, uploads, api, sync, scope, dispatch, download, cache (8) |
| `SettingsRepository` | 82 | `_settings` :55; `writeLock` :56 | store, scope |
| `AndroidGoogleAuth` (object) | 98 | global `activity: WeakReference<Activity>` :10 | — |
| `WebGoogleAuth` (object) | 134 | global `pending: ((String)->Unit)?` :52 + JS globals | — |
| `AndroidAppGraph` (object) | 26 | global `instance: AppGraph?` :6; also writes `AndroidPlatform.context` :12 | — |

### 1.2 Who calls whom (text dependency graph)

```
AppGraph ──builds──> settings, sessions, bands, api, uploads, places, sync, messages, changes, incoming, account, membership, attachments
   sessions ──exchange lambda──> api.createSession          (AppGraph.kt:38, forward reference to a val declared at :42)
   api      ──token lambda────> sessions.validToken          (AppGraph.kt:45)      <── CYCLE sessions <-> api
   api      ──onSessionsRevoked> account.signOutRevoked      (AppGraph.kt:50, forward ref to :60)
   api      ──onUpdateRequired─> AppGraph.updateRequired     (AppGraph.kt:48)
   sync     ──onBandCreated────> messages.register           (AppGraph.kt:56, forward ref to :57)  <── CYCLE sync <-> messages
   messages ──writes───────────> sync.messaged               (DeviceMessages.kt:35,42)
   uploads  ──reads────────────> bands.local                 (AttachmentUploads.kt:77)
   sync     ──uses─────────────> bands (14 methods), uploads (3), api (6)
   account  ──uses─────────────> sessions, api, bands, uploads, sync, messages, incoming
   membership ─uses────────────> api, bands, sync, messages
   changes  ──uses─────────────> bands, sync
   attachments ─uses───────────> bands, uploads, api, sync, changes::dispatch
   AppGraph extension fns: awaitPushed (dead, 0 callers), linkedEvent (EventLinkOpener.kt:22 → sessions, bands, sync)

UI consumers of graph fields (count of `graph.x` references in app/ + ui/):
   incoming 18, bands 16, sync 12, settings 8, changes 8, account 8, api 5, sessions 3, membership 3, attachments 3, updateRequired 1, places 1
Screens that take the whole ApiClient: EventScreen, RosterScreen, HistoryScreen, EventFields, HistorySection, EventsScreen, SettingsWiring (7)
Screens that take the whole SyncEngine: EventScreen, HistoryScreen, HistoryController, HistorySection (4) — only HistoryController actually calls it (syncNow, :84)
Platform entry points: MainActivity (onStart/onStop/intents) and wasm Main.kt (visibilitychange, URL) both poke graph.sync, graph.sessions, graph.messages, graph.incoming directly.
```

### 1.3 The app's "state": every flag/flow that drives behaviour

| State | Declared | Written | Read |
|---|---|---|---|
| `sessions.session` | Session.kt:72 | Session.kt:83,174,180 | App.kt:68,104; AppGraph.kt:80,85,98; Account.kt:27,91,104; DeviceMessages.kt:30,44; EventLinkOpener.kt:23; MainActivity:12; Main.kt:23 |
| `bands.state: BandState` (Loading/NoBand/Unreadable/Ready) | BandRepository.kt:85 | :132,140,145,161,175,214,283,323,332,352 | App.kt:67,112-124,161-192; Navigation.kt; SharedFilesDialog |
| `bands.index` | :88 | `updateIndex` :428 (10 call sites) | App.kt:101; SyncEngine.kt:167,247 |
| `updateRequired` | AppGraph.kt:41 | AppGraph.kt:48 (ApiClient callback) | App.kt:76 |
| `sync.status: SyncStatus` (7 values) | SyncEngine.kt:69 | :178,189,209,227,264,270,275 | App.kt:102 (ZeroScreen, TopBar); SyncEngine.kt:139,163,200,202,244 (as guards) |
| `sync.stuck` | :82 | :339,405,408 | App.kt:103; Navigation.kt:511; SyncEngine.kt:138,236,353,356 |
| `sync.messaged` (public var) | :99 | DeviceMessages.kt:35,42 | SyncEngine.kt:117 |
| `sync.loop / drain` (Jobs) | :92-93 | start/stop/pauseAfterSending | `bandChanged` :155 uses `loop?.isActive` as "app on screen" |
| `account.signedOutEverywhere` | Account.kt:30 | :50,:92 | App.kt:81 |
| `account.signIns` + `account.handledSignIns` | :32,:38 | :51; Navigation.kt:351 | Navigation.kt:346-352 |
| `account.pictureSent` | :96 | :105 | :103 |
| `incoming.pendingJoinCode` | Incoming.kt:14 | MainActivity:61; Main.kt:27; App.kt:153,156 | App.kt:145; Main.kt:29 (mirrors to sessionStorage) |
| `incoming.pendingEventLink` | :17 | MainActivity:62; Main.kt:34; App.kt:222,233 | App.kt:205; Main.kt:36 |
| `incoming.pendingOpenEvent` | :20 | App.kt:217; Navigation.kt:359; SharedFilesDialog.kt:384; Account.kt:74 | Navigation.kt:356 |
| `incoming.pendingEventSection` | :23 | Navigation.kt:446; SharedFilesDialog.kt:383; EventScreen.kt:211 | EventScreen.kt:202 |
| `incoming.navigatedForBand` (plain var) | :29 | App.kt:120,214; Navigation.kt:368; SharedFilesDialog.kt:362 | App.kt:119 |
| `incoming.sharedFiles` | :32 | MainActivity:47; SharedFilesDialog.kt:117 | SharedFilesDialog.kt:112 |
| `incoming.sharedHandover` | :35 | MainActivity:43; SharedFilesDialog.kt:372; Navigation.kt:362; EventScreen.kt:218; Incoming.kt:43 | EventScreen.kt:213; CollectedSection.kt:175; CostScreen.kt:153 |
| `incoming.sharedAttached` | :38 | SharedFilesDialog.kt:375; EventScreen.kt:228 | EventScreen.kt:223 |
| `uploads.pending` | AttachmentUploads.kt:36 | :135 | AppGraph.kt:114; Attachments.kt:40; SyncEngine.kt via bandsWithUploads |
| `AppGraph.loaded / backgroundUser` | :69,:71 | :86,:101 | :75,:77,:95,:109 |
| UI-local but behaviour-driving | `shownBandId` App.kt:113 (rememberSaveable, with sentinel `NO_BAND="-"` :196); `openError` :108; `signingInAgain` :125; `joining` :146; wasm `Phase` Main.kt:4; `loaded` MainActivity:75 |
| Global singletons | `AndroidAppGraph.instance`; `AndroidGoogleAuth.activity`; `AndroidPlatform.context`; `WebGoogleAuth.pending`; `globalThis.kapeluszDb`, `globalThis.kapeluszGis`, `globalThis.artSplitOnCredential` |

That is 21 pieces of cross-component mutable state plus 6 globals, with no single place that says what combination is legal.

### 1.4 Lifecycle, as it actually runs

**Cold start (Android)**: `MainActivity.onCreate` → `AndroidAppGraph.get` constructs the graph (all 13 objects, synchronously) → `takeJoinCode/takeSharedFiles` write into `incoming` *before anything is loaded* → `setContent` → `LaunchedEffect { graph.load() }` → `AppGraph.load()` (AppGraph.kt:74): `settings.load`, `sessions.load`, `bands.load(userId)`, `uploads.load(userId)`, `sync.start()`, `messages.register()`. Meanwhile `onStart` (MainActivity:8) has already run `sync.start()` guarded by `session != null` (false on first start, so a no-op) and `messages.register()` (no-op, same guard).

**Cold start (web)**: `Main.kt:20` builds the graph, registers `visibilitychange`, seeds `incoming` from the URL/sessionStorage, mirrors `incoming` flows back into sessionStorage with two `MainScope().launch`, then `TabLock.acquire` → `graph.load()` → maybe `pauseAfterSending()`.

**Background worker**: `BackgroundSyncWorker.doWork` → `AndroidAppGraph.get` (may construct a second-ever graph in a fresh process) → `loadForBackground()` (AppGraph.kt:94: same loads but no `sync.start`, remembers `backgroundUser`) → `sync.syncAll()`. If the activity then starts in the same process, `load()` sees `backgroundUser != null` and skips the loads.

**Foreground sync loop** (`SyncEngine.start` :102): `while(true) { syncNow(); wait(interval | backoff) or wakeUp; delay(DEBOUNCE) }`. `syncNow` (:197) under `runLock`: early-return if status is NEEDS_SIGN_IN/UPDATE_REQUIRED; for each band to push + the current band: `syncBand` = push → uploads → pull; then `/me` band list; `classify(Throwable) → SyncStatus`. Web hidden tab: `pauseAfterSending` (:131) cancels the loop and starts a second `drain` loop with its own backoff.

**Sign-in** (`Account.onSignedIn` :45, runs in app scope via `scope.async{}.await()`): `bands.load`, `uploads.load`, `sessions.signedIn` (this flips `session` → UI swaps to `BandContent`), `signedOutEverywhere=false`, `_signIns++`, `sync.start()`, `sync.resumeAfterSignIn()`, `messages.register()`.

**Sign-out** (`Account.signOut` :70): `sync.stop`, `messages.unregister`, `sessions.signOut`, `incoming.pendingOpenEvent=null`. Revoked-by-server path: `ApiClient.text/checkRevoked` → `onSessionsRevoked` → `scope.launch { account.signOutRevoked() }` → sets `signedOutEverywhere` and calls `signOut(tellServer=false)`. Note `bands.locals` and `uploads` are never cleared on sign-out; they are replaced by the next `load(userId)`.

**Band switch**: four independent writers can open a band: `BandContent.openBand` (App.kt:126, resets nav itself), `EventLinkOpening` (App.kt:213, sets `navigatedForBand` first), `Navigation`'s `pendingOpenEvent` effect (Navigation.kt:367), and `SharedFilesDialog.deliver` (:360). A fifth effect, `BandContent`'s `LaunchedEffect(readyBandId)` (App.kt:114), watches `bands.state` and resets navigation *unless* `navigatedForBand` matches — i.e. the flag exists only to tell that effect "someone else already navigated". Sign-in also resets navigation twice: `key(session?.userId) { rememberNavController() }` (App.kt:71) gives a new controller per account, and the `signIns`/`handledSignIns` counter (Navigation.kt:346-353) resets it again for the same account.

---

## 2. Problems

### 2.1 Wiring: forward references and cycles in `AppGraph`
- `sessions` (:38) captures `api` (:42) in a lambda; `api` captures `sessions` (:45) and `account` (:50, declared at :60); `sync` (:56) captures `messages` (:57). The code works only because every lambda runs after construction — a comment at :37 admits it. Nothing stops a future constructor from calling `validToken` eagerly and getting an NPE (Kotlin will happily compile a `null` backing field read).
- Three different mechanisms are used for the same thing, "a lower layer tells a higher one something happened": callback lambdas (`onUpdateRequired`, `onSessionsRevoked`, `onBandCreated`), public `var`s poked from outside (`sync.messaged`, `account.handledSignIns`, `incoming.navigatedForBand`), and `SharedFlow`s (`rejections`, `lostAccess`). A reader has to learn all three.
- `Account` takes 8 collaborators, `Attachments` 8, `ApiClient` 7, `SyncEngine` 7. That is the "many parameters" complaint, and the cause is that these classes are orchestration scripts over everything rather than owners of one thing.

### 2.2 Responsibilities split oddly
- `BandRepository` (537 lines) does five jobs: in-memory band state + replay (`dispatch`, `replay`, `rebase`), the hold/withdraw Undo protocol (`release`, `withdraw`, `holds`), persistence with dirty-tracking and retry (`persist`, `flush`, `writeChanged`, `retryFlush`, :428-507), crash-recovery and backups (`recoverBands`, `readLocal` backup, `keepUnsent`), and *two generations of data migration* (`migrateUnscopedKeys`, `adoptLegacyBand`, :380-419) that the memory note "no backward compatibility" says can go. It also launches its own coroutines (`scope.launch { flush() }` at :434 and :439, one per change).
- `SyncEngine` (439 lines) mixes the scheduler (loop, backoff, debounce, drain, wake channel: :90-191) with the protocol (push/pull/create/list/leave/discard: :287-421) and with the status presentation rules (`shown`, `backgroundShown` :235, :285). `leave` (:351) and `discardStuck` (:335) are user actions living in the sync engine because they need `runLock`.
- `Account`, `Membership`, `Changes` are thin "use case" scripts, but the same kind of script also lives as `AppGraph` extension functions (`linkedEvent`, `awaitPushed`), inside `App.kt` (`openBand` :126-139 does hasStored/open/download/resetNav/requestSync), inside `Navigation.kt` (`NewBandRoute` :474 does createBand+requestSync+resetTo, duplicating `ZeroScreen.onCreate` App.kt:170), and inside `SharedFilesDialog.deliver` (:357). The rule "where does an operation go" is not visible.
- `Incoming` is a bag of 8 unrelated handoffs (links, share sheet, form drafts, snackbar undo) whose only commonality is "nullable StateFlow read by whoever gets there first".
- `AttachmentUploads` depends on `BandRepository` (reads `local(bandId).pending`/`base`, :77-80) to decide whether to upload — a queue that inspects another store's internals.
- `DeviceMessages` is told to `register()` from five places (AppGraph.load :87, Account :54, Membership :26, :53, MainActivity :14, SyncMessagingService :61, SyncEngine via onBandCreated) because the server only keeps devices for users with ≥1 band. The *rule* ("register whenever signed in and band count went 0→1 or token changed") is spread across callers instead of being one reaction to state.

### 2.3 Imperative flags as inter-component signals
- `incoming.navigatedForBand` (4 writers, 1 reader) exists only to suppress one `LaunchedEffect` in `App.kt`. It is a plain `var` on a shared object read from a composable effect: not snapshot-aware, not thread-safe, and the comment at Navigation.kt:365-366 explains a subtle ordering requirement ("First: … Only when it switches: …") that only holds if every writer follows the same dance.
- `account.handledSignIns` is a UI-private "already consumed" marker stored on a data-layer object so it survives recomposition. Same pattern as `shownBandId` (App.kt:113) with a sentinel string `"-"`.
- `sync.messaged` is written by `DeviceMessages` and read inside the loop's wait computation; it is state of the *scheduler* owned by someone else.
- The nullable-StateFlow-as-event pattern (`pendingX.value = ...; consumer sets it back to null`) appears 7 times. Every consumer needs `resumed` checks (EventScreen.kt:201, 215, 223; CollectedSection.kt:177) and "is it still mine?" filters (`eventId == …`, `bandId == …`) because any composable might observe it; `takeSharedFiles` (Incoming.kt:41) is a one-shot read disguised as a getter. Clearing on sign-out is partial: `signOut` clears `pendingOpenEvent` only (Account.kt:74); `sharedFiles/Handover/Attached/pendingEventSection` survive a sign-out.

### 2.4 Concurrency
- Everything relies on `Dispatchers.Main` single-threadedness (comment BackgroundSyncWorker.kt:10). That is a legitimate design, but it is implicit: `BandRepository` has no lock around `locals/dirty` yet `flush()` has a mutex; `SyncEngine` has `runLock`; `SessionRepository` has `refreshLock`; `AttachmentUploads` has `lock`; `PlacesLocator` has `lock`. Five mutexes, each protecting a different notion, none documented against the "main-only" rule.
- `SessionRepository.validToken` (Session.kt:96) holds `refreshLock` across `google.refresh()` (may show UI, 15 s timeout on web) and the network `exchange`. Every API request takes this lock first (ApiClient.kt:189), so one slow renewal serialises all traffic.
- Scopes: app scope (`AppGraph.scope`), `rememberCoroutineScope` in `BandContent`/screens, `lifecycleScope` in MainActivity, two bare `MainScope().launch` in Main.kt:28,35 that are never cancelled, `scope.async{}.await()` in `Account.onSignedIn`/`Membership.leaveBand` to escape the caller's scope. The reason ("the screen leaves composition and would cancel us") is right, but it is re-discovered at every call site rather than being a rule of the API (e.g. "use cases always run in the app scope and return a Deferred/Result").
- `SyncEngine.bandChanged` (:154) checks `loop?.isActive` as a proxy for "app on screen", then launches an unbounded coroutine that survives `stop()`.

### 2.5 Error handling conventions
- Three conventions coexist: throw (`ApiException`, `NeedsSignInException`, `LeaveBlocked`, `SyncProtocolException`), `Result` via `runSuspendCatching` (Attachments.url, PlacesLocator, DeviceMessages, EventLinkOpener), and status enums (`SyncStatus`, `Rejection?`). `ApiClient` errors are a flat `(status: Int, code: String, message)` and callers string-compare `e.code == ApiErrors.X` in 6 places (SyncEngine.kt:298,312,363; AttachmentUploads.kt:97; Attachments doc :103). "Update required" and "sessions revoked" are *side channels* (callbacks fired from inside `text()` :228-230) rather than return values, so the exception the caller sees and the global state change happen in different places.
- `catch (e: CancellationException) { throw e } catch (e: Throwable)` is hand-written 9 times (BandRepository :490, SyncEngine :221,255,272,293,307, Attachments :53, Session :128, AttachmentUploads via runSuspendCatching) even though `runSuspendCatching` exists for that.
- `ApiClient.deleteBand/leaveBand/revokeInvite` (:106-115, :152-155) express "throw on non-2xx" as `if (!2xx) parse(response, ErrorResponse.serializer())`, relying on `parse→text` throwing; `text(send(...))` already does exactly that two lines up (:67).

### 2.6 Persistence layering
- `KeyValueStore` is the only abstraction; everything above it builds its own key scheme, JSON (de)serialisation, backup naming (`-backup-<clock>`, `-unsent-<clock>`), and "unreadable → fall back" policy: `BandRepository` (:109-114, :346-355, :303-306), `SessionRepository` (:83), `SettingsRepository` (:61-66), `AttachmentUploads` (:43-45), `PlacesLocator` (:37). Five copies of "read key, runCatching decode, getOrNull", three of "write backup copy first".
- Three stores with different semantics hide behind the same interface: `FileStore` (fsync + atomic rename, Dispatchers.IO), `IndexedDbStore` (one transaction per op, reconnect logic in JS), `InMemoryStore`. The Android `ByteCache`/`FileCache` for attachment bytes (platform/Media.kt) is a fourth, separate storage path. Nothing says which keys exist; the list is spread over 6 companion objects.
- `PlacesLocator` key `"kapelusz-places"` (:64) is not user-scoped while every other key is.

### 2.7 The sync "state machine" is implicit
`SyncStatus` has 7 values but it is a *display* value, not the engine's state. The real state is the product of: `loop` (null/active), `drain` (null/active), `_status` (used as a guard in 5 places: :139,:163,:200,:202,:244), `_stuck`, `messaged`, `backoff` (a local variable inside the loop :106), and whether `syncNow` is currently holding `runLock`. Transitions are scattered: `resumeAfterSignIn` flips NEEDS_SIGN_IN→IDLE (:178), `syncAsked` sets SYNCING (:189), `syncNow` sets `shown(result)` (:227), `syncAll` sets `backgroundShown` (:264), `discardStuck` edits `_stuck` (:339). There is no function you can read to answer "given state S and event E, what happens". The 32 tests in `SyncEngineTest` are the only specification, and they test through time-advancing coroutines rather than a transition table.

### 2.8 Duplication and naming
- Create band + requestSync + reset nav: App.kt:170 and Navigation.kt:475-477.
- "Opened band must not reset nav": App.kt:214, Navigation.kt:368, SharedFilesDialog.kt:362 (three copies of the two-line dance).
- Clear-on-consume boilerplate for each `incoming` flow (7 sites).
- Doc comments that are orphaned leftovers: BandRepository.kt:260-261 (two consecutive `/** */` on `unlisted`), :308-309 (two on `forgetDeleted`), AttachmentUploads.kt:48-49 (two on `attaching`); `takeJoinCodeFromUrl` doc sits on `takeEventLinkFromUrl` (Main.kt:79-83).
- Dead code: `AppGraph.awaitPushed` (0 callers), `BandRepository.LOCAL_USER_ID` (0 readers), `AppGraph.settingsForBackground` (0 callers), legacy migrations `migrateUnscopedKeys`/`adoptLegacyBand`/`UNSCOPED_INDEX_KEY`/`LEGACY_KEY` (≈45 lines, contradicted by the "no backward compat" rule).
- Naming: `Attachments` is injected as `fileStore` (Navigation.kt:428, SharedFilesDialog.kt:111); `SettingsRepository` as `preferences` (EventScreen.kt:159); `SessionRepository` exposed as `graph.sessions` but `account.session`; `sync` is both a package and a field; `Changes.dispatch` vs `BandRepository.dispatch`; `onBandList`/`onCreated`/`onPushed`/`onPulled` on the repository read like callbacks but are plain methods.
- Spec references (`ITERATION_3 R9`, `V5-M1 A5c`, …) appear in 60+ comments; they justify *why* but the code does not carry the *what* in a discoverable place.

### 2.9 Why adding a feature touches many files (worked example)
"Open a specific cost from a push message" today needs: an `Incoming` flow (data), a writer in `SyncMessagingService` (androidMain), a `MainActivity`/`Main.kt` entry, a `LaunchedEffect` consumer in `Navigation.kt` with the `navigatedForBand` dance and a `bands.hasStored` guard, a `resumed` check in the destination screen, a clear-on-sign-out line in `Account.signOut`, and possibly a sessionStorage mirror in `Main.kt`. Seven files, none of which is "the place where navigation intents live".

---

## 3. Target design (as if built from scratch)

Keep: the data model, `Engine`, `KeyValueStore` as the lowest layer, Compose Navigation, no DI framework, main-thread-only mutation. Change: who owns state, how signals travel, and that there is one state machine for the app and one for sync.

### 3.1 Package layout (all under `com.jacekgajek.kapelusz.app`)

```
runtime/        AppRuntime (wiring + lifecycle), AppState (sealed), AppLifecycle events
auth/           Session, SessionStore, TokenProvider, GoogleAuth (+ platform impls)
api/            KapeluszApi (HTTP only), ApiError (sealed), ApiSignal (flow), HistoryDecoder
bands/          LocalBand, BandIndex, BandStore (persistence), BandRepository (memory+flows), HoldPolicy
sync/           SyncState (sealed) + SyncEvent (sealed) + reduce(), SyncScheduler (effects), SyncRound (protocol), SyncReport
attachments/    UploadQueue, AttachmentFiles (facade: attach/remove/bytes)
usecases/       SignIn, SignOut, JoinBand, LeaveBand, DeleteBand, CreateBand, OpenBand, OpenEvent   (one file each, one `suspend operator fun invoke`)
intents/        AppIntent (sealed), IntentInbox, IntentRouter (UI side)
devices/        DeviceRegistration (reacts to state, is not "called")
settings/       AppSettings, SettingsStore
platform/       (as today)
```

### 3.2 Main classes and their single responsibility

```kotlin
// runtime/AppState.kt — the ONE thing App() switches on
sealed interface AppState {
    data object Loading : AppState
    data object UpdateRequired : AppState
    data class SignedOut(val everywhere: Boolean) : AppState
    data class SignedIn(val session: Session, val band: BandState) : AppState   // BandState as today: NoBand / Ready / Unreadable
}

// runtime/AppRuntime.kt — replaces AppGraph. Only wiring, lifecycle, and derived state. No business logic.
class AppRuntime(store: KeyValueStore, google: GoogleAuth, http: HttpClient, channel: MessageChannel?, scope: CoroutineScope) {
    val state: StateFlow<AppState>                      // combine(api.signals, sessions.session, bands.state)
    val intents: IntentInbox
    val sync: SyncScheduler
    val useCases: UseCases                              // SignIn, SignOut, JoinBand, … built here, each takes ≤3 collaborators
    suspend fun load()                                  // once per process
    fun onForeground() / fun onBackground() / fun onHidden()   // the only lifecycle API platforms call
    suspend fun syncInBackground(): Boolean             // for the worker
}
```

Dependency order with **no forward references and no cycles**:

```
KeyValueStore
 └─ SessionStore(store)                              persists Session only
 └─ SessionApi(http, baseUrl)                        createSession(bearer) — no token provider
 └─ TokenProvider(sessionStore, google, sessionApi)  validToken(refused) — today's SessionRepository logic
 └─ KapeluszApi(http, baseUrl, tokenProvider)        every other endpoint; exposes `signals: SharedFlow<ApiSignal>` (UpdateRequired, SessionsRevoked) instead of callbacks
 └─ BandStore(store)                                 read/write/backup LocalBand & BandIndex; owns key scheme; flush/dirty/retry
 └─ BandRepository(bandStore)                        memory + StateFlow<BandState>; dispatch/replay/rebase/hold; NO coroutine launching (marks dirty, store flushes)
 └─ UploadQueue(store, api)                          pending files; takes "is attachment on server?" as a function argument, not the repository
 └─ SyncRound(api, bands, uploads)                   suspend fun run(plan: SyncPlan): SyncReport — pure protocol, no status flow
 └─ SyncScheduler(round, scope, clock)               owns SyncState, the loop, backoff; exposes `state`, `events: SharedFlow<SyncEvent>`
 └─ DeviceRegistration(channel, api, scope)          collects (session, bandCount>0, tokenChanged) and registers; nobody calls it
 └─ UseCases(…)                                      each use case gets only what it needs
 └─ AppRuntime                                       combines into AppState
```

The `sessions ↔ api` cycle disappears because the one endpoint that must not use the token provider (`createSession`) lives in its own tiny `SessionApi`. The `sync ↔ messages` cycle disappears because `SyncScheduler` emits `SyncEvent.BandCreated` and `DeviceRegistration` observes it.

### 3.3 Explicit sync state machine

```kotlin
sealed interface SyncState {
    data object Stopped : SyncState                                   // app not on screen (Android) / never started
    data class Idle(val last: Outcome, val backoff: Duration) : SyncState
    data class Running(val reason: Reason) : SyncState                // Reason: Scheduled, Requested, UserTapped, Message(bandId)
    data class Draining(val backoff: Duration) : SyncState            // web tab hidden: send what waits, then Stopped
    data class Blocked(val why: Block) : SyncState                    // Block: NeedsSignIn, UpdateRequired, NoSession
}
sealed interface SyncEvent { Start; Stop; Hide; Wake(reason); RoundDone(report: SyncReport); SignedIn; SignedOut; UpdateRequired }

// pure, unit-testable without coroutines
fun reduce(state: SyncState, event: SyncEvent, messaged: Boolean, interval: Duration): Pair<SyncState, Effect?>
// Effect: RunRound(plan) | ScheduleWake(after) | Nothing

class SyncScheduler(round: SyncRound, scope: CoroutineScope, clock: () -> Long) {
    val state: StateFlow<SyncState>
    val events: SharedFlow<SyncEvent>            // Rejected(op), LostAccess(band), Stuck(band), BandCreated(id)
    fun send(event: SyncEvent)                   // the only entry point; start/stop/hide/requestSync become events
    val display: StateFlow<SyncStatus>           // derived from state + stuck, for the icon — today's enum, computed in one place
}
```

What this buys: `pauseAfterSending`, `resumeAfterSignIn`, `syncAsked`, `bandChanged`, `start`, `stop` are all `send(event)`; the five status guards collapse into `is Blocked`; "stuck" is part of the `SyncReport`, not a side flow; `messaged` is an input to `reduce`, not a public var. `SyncRound` is the testable protocol: push/create/upload/pull/list for a `SyncPlan(bands, pullCurrent)` returning `SyncReport(perBand: Map<Id, Outcome>, lost: List<LostBand>, rejections, stuck)`. The existing `FakeServer` tests the round directly with no time control at all.

### 3.4 One-shot UI events instead of nullable StateFlows and flags

```kotlin
sealed interface AppIntent {
    data class JoinWithCode(val code: String) : AppIntent
    data class OpenEvent(val eventId: Id, val bandId: Id? = null, val section: EventSection? = null, val carry: Carry? = null) : AppIntent
    data class ShareFiles(val files: SharedFiles) : AppIntent
}
/** What travels with OpenEvent into the destination: files for a new cost/collected entry, or an "undo attach" snackbar. */
sealed interface Carry { data class NewCostDraft(files); data class NewCollectedDraft(files); data class Attached(ids, failed) }

class IntentInbox {
    /** Durable until consumed (survives sign-in, is persisted by the web into sessionStorage). At most one per kind. */
    val pending: StateFlow<List<AppIntent>>
    fun offer(intent: AppIntent)
    fun take(intent: AppIntent)                   // consumer acknowledges; idempotent
    fun clearOnSignOut()
}
```

A single `IntentRouter` composable at `App` level is the *only* consumer. It waits for prerequisites (`state is SignedIn`, band loaded), resolves `OpenEvent` to a band (today's `linkedEvent`), and executes **one** navigation command:

```kotlin
class Navigator(nav: NavHostController, openBand: OpenBand /*use case*/) {
    suspend fun showEvent(bandId: Id, eventId: Id, section: EventSection?, carry: Carry?)   // open band if needed, reset, navigate with route args
    suspend fun showBand(bandId: Id)                                                          // open + reset
}
```

Because `Navigator` is the only thing that both opens bands *and* navigates, the "reset when the band changed unless someone already navigated" effect (`App.kt:114-124`) and `navigatedForBand` are deleted; the router sets `shownBandId` itself. `section` and `carry` become **route arguments** of `EventRoute`/`CostRoute` (`EventRoute(eventId, section?, carryId?)`), so `pendingEventSection`, `sharedHandover`, `sharedAttached` and all `resumed`/"is it mine?" checks in `EventScreen`, `CollectedSection`, `CostScreen` go away: the destination reads its own arguments. Sign-in resets navigation in exactly one place (new `NavHostController` per `SignedIn.session.userId` + the router replaying the URL) and `signIns/handledSignIns` is dropped.

### 3.5 Use cases replace Account/Membership/Changes/extension functions

```kotlin
class SignIn(sessions: SessionStore, bands: BandRepository, uploads: UploadQueue, sync: SyncScheduler, scope) { suspend operator fun invoke(idToken: String) }
class SignOut(sessions, sync, devices)         { suspend operator fun invoke(reason: SignOutReason) }   // User, Everywhere, Revoked
class OpenBand(bands, round)                   { suspend operator fun invoke(bandId): Result<Unit> }     // hasStored? open : download — today's App.kt:126-139
class CreateBand(bands, sync)                  { operator fun invoke(name, currency, yourName): Id }
class JoinBand(api, bands, sync)               { suspend operator fun invoke(code) }
class LeaveBand(sync)                          { suspend operator fun invoke(bandId) }
class Edit(bands, sync)                        { fun dispatch(op): Rejection?; fun hold(op, for): HeldOp?; fun release/withdraw }   // today's Changes
```

Each runs in the app scope by construction (the use case holds the scope), so the `scope.async{}.await()` trick is in one base helper, not re-explained at each site. Screens receive *only* use cases and read-only flows; no screen gets `KapeluszApi` or `SyncScheduler`. The seven screens that take `ApiClient` get small interfaces instead: `InvitesApi`, `PlacesApi`, `HistoryApi`, `CalendarApi` (all implemented by `KapeluszApi`).

### 3.6 Api errors

```kotlin
sealed interface ApiError { data class Http(status, code, message); data object NeedsSignIn; data object SessionsRevoked; data object UpdateRequired; data class Unreachable(cause); data class Unreadable(cause) }
class ApiException(val error: ApiError) : RuntimeException()
```

`SyncRound.classify` becomes a `when` over `ApiError`; the string comparisons on `ApiErrors.*` are kept in one mapping inside `api/`. `UpdateRequired`/`SessionsRevoked` are both thrown *and* emitted on `signals`, in one place (`KapeluszApi.fail(error)`).

### 3.7 Persistence

`BandStore` owns every band key, the backup naming, `flush/dirty/retry`, and the "unreadable → backup → report" policy. `SessionStore`, `SettingsStore`, `UploadQueue` use one shared `JsonSlot<T>(store, key, serializer)` helper (read-or-null-with-backup, write) so the "runCatching decode getOrNull" line exists once. Key scheme documented in one `StorageKeys` object.

---

## 4. Migration plan (each step shippable, green CI, tests carry over)

| # | Step | Files | Risk | Tests |
|---|---|---|---|---|
| 1 | **Delete dead and legacy code**: `awaitPushed`, `LOCAL_USER_ID`, `settingsForBackground`, `migrateUnscopedKeys`, `adoptLegacyBand`, `UNSCOPED_INDEX_KEY`, `LEGACY_KEY`; fix the three doubled doc comments; replace the 9 hand-written `CancellationException` rethrows with `runSuspendCatching`; make `deleteBand/leaveBand/revokeInvite` use `text(send(...))`. | BandRepository, AppGraph, AwaitPushed, ApiClient, SyncEngine, Attachments, Session | Low (behaviour-neutral; one user-visible loss: M2/M3-era data no longer adopted — allowed by the no-backward-compat rule) | `BandRepositoryTest.movesUnscopedKeysOfTheSameUser` and `adoptsTheBandCreatedBeforeSignIn` are deleted with the code; the other 15 unchanged. |
| 2 | **Callbacks → flows**: `ApiClient.signals: SharedFlow<ApiSignal>` replaces `onUpdateRequired`/`onSessionsRevoked`; `SyncEngine.events: SharedFlow<SyncEvent>` carries `BandCreated` (and later `LostAccess`, `Rejected`); `AppGraph` subscribes in `init`. Remove `sync.messaged` var in favour of `SyncEngine.setMessaged(Boolean)` (or an event). | ApiClient, SyncEngine, AppGraph, DeviceMessages | Low | `SyncEngineTest` uses default callbacks → unchanged. Add 2 small tests on the flows. |
| 3 | **Break the sessions↔api cycle**: extract `SessionApi(http, baseUrl)` with `createSession`; `SessionRepository` takes it; `ApiClient` no longer has `createSession`. Then `AppGraph` can declare in dependency order with no forward refs. | ApiClient, Session, AppGraph, SessionRepositoryTest (constructor arg) | Low | `SessionRepositoryTest` passes a fake exchange today (:70 default) → adapt the lambda to the new interface, 1-line change. |
| 4 | **Single `AppState`**: add `AppGraph.state: StateFlow<AppState>` via `combine(updateRequired, sessions.session, bands.state, account.signedOutEverywhere)`; `App.kt` switches on it; `updateRequired`/`signedOutEverywhere` become private. | AppGraph, Account, App.kt | Low; UI-visible but a pure refactor of the `if/else` at App.kt:78-85 | No existing tests; add one `AppStateTest` with `InMemoryStore` and a fake api. |
| 5 | **Extract `BandStore`** (persistence, backups, flush/dirty/retry, key scheme) out of `BandRepository`; the repository keeps memory+flows and calls `store.markDirty(bandId)`/`store.flush()`. Remove `scope` from the repository (the store owns the retry coroutine). | BandRepository → BandRepository + BandStore; SyncEngine (`bands.flush()` → `store.flush()`); AppGraph | Medium (the write-pass/identity-check logic at :446-484 must move verbatim) | `BandRepositoryTest` tests through the public API with `InMemoryStore`; constructor gains a `BandStore(store)` arg — mechanical. `aChangeMadeDuringAWriteIsWrittenToo`, `aFailedWriteIsKeptAndTriedAgain`, `flushWritesPendingChangesImmediately` become `BandStoreTest`s. |
| 6 | **Split `SyncEngine` into `SyncRound` + `SyncScheduler`** behind the existing public surface first (keep `start/stop/requestSync/syncNow/syncAll/status/stuck/rejections/lostAccess` as a façade), then introduce `SyncState` + `reduce()` inside the scheduler and route `pauseAfterSending/resumeAfterSignIn/syncAsked/bandChanged` through `send(event)`. Last, delete the façade methods the UI no longer needs. | SyncEngine → sync/SyncRound, sync/SyncScheduler, sync/SyncState; AppGraph; HistoryController (syncNow → send(Wake)) | Medium-high; this is the biggest behavioural surface | All 32 `SyncEngineTest`s run unchanged against the façade in the first sub-step — that is the safety net. Then add a `SyncStateTest` table (state × event → state) with ~20 rows; time-based tests (`withMessagesTheAppLooksForNewsEveryFiveMinutes`, `aHiddenTabSendsWhatWaitsThenStops`) move to the scheduler. |
| 7 | **`IntentInbox` + `IntentRouter` + `Navigator`**, replacing `Incoming`, `navigatedForBand`, `signIns/handledSignIns`, `shownBandId`, and the `LaunchedEffect`s in App.kt:114, 207, Navigation.kt:349, 357, EventScreen.kt:203-229, CollectedSection.kt:178, CostScreen.kt:153. `section`/`carry` become route args. MainActivity/Main.kt call `intents.offer(...)`. | Incoming (deleted), App.kt, Navigation.kt, SharedFilesDialog, EventScreen, CollectedSection, CostScreen, MainActivity, Main.kt, Account | High (UI-visible, two platforms, no tests today) | Add `IntentRouterTest` for the resolution logic (today's `linkedEvent`), pure. Manual smoke list: join link signed-out/in, event link to other band, share-to-new-cost, share-to-existing, undo-attach snackbar, rotate during each. |
| 8 | **Use cases**: fold `Account`, `Membership`, `Changes`, `EventLinkOpener`, `App.openBand`, the two `createBand` sites into `usecases/`; screens get narrow api interfaces (`InvitesApi`, `PlacesApi`, `HistoryApi`, `CalendarApi`) instead of `ApiClient`/`SyncEngine`. | Account, Membership, Changes, EventLinkOpener, Navigation.kt, SettingsWiring, EventScreen, RosterScreen, HistoryScreen, HistorySection, EventFields, EventsScreen | Medium (wide but mechanical) | `SyncEngineTest.anAttachmentsFileComesFromTheDeviceOrIsDownloadedOnce` constructs `Attachments` directly (:533) — adapt its constructor; otherwise no tests touch these classes. |
| 9 | **`DeviceRegistration` as a reaction**: it collects `(session != null, index.bands.isNotEmpty(), tokenChanged)` and registers on change; remove the 6 explicit `messages.register()` calls. | DeviceMessages, AppGraph, Account, Membership, MainActivity, SyncMessaging.android | Low-medium (server only keeps devices with a band; keep the "register on new token" path) | Add a `DeviceRegistrationTest` with a fake channel. |
| 10 | **`AppRuntime` lifecycle API** (`load/onForeground/onBackground/onHidden/syncInBackground`); MainActivity, Main.kt, BackgroundSyncWorker stop reaching into `graph.sync`/`graph.sessions`/`graph.messages`. Rename `AppGraph` → `AppRuntime`. | AppGraph, MainActivity, Main.kt, BackgroundSyncWorker, SyncMessaging.android, AndroidAppGraph | Low | None needed beyond existing. |

Order rationale: 1-4 are small and remove cycles/flags without touching the UI; 5-6 are internal to data/sync and are covered by the two big test files; 7 is the only step that changes UI behaviour and should be its own PR with manual smoke testing; 8-10 are mop-up that becomes easy once 6-7 exist.

---

## 5. Noticed, not fixed

- `BandRepository.kt:206` — `dispatch` does `_state.value as BandState.Ready` unchecked; `readLocal` sets `currentBandId` and `Unreadable` together (:351-352), so a dispatch from any still-visible screen in that window throws `ClassCastException`.
- `Account.kt:96` — `pictureSent` is never reset on sign-out; a second account signing in within the same process never gets its Google photo sent.
- `Account.kt:70-75` — `signOut` clears only `incoming.pendingOpenEvent`; `sharedFiles`, `sharedHandover`, `sharedAttached`, `pendingEventSection` survive and can be delivered to the next account's band.
- `Session.kt:96-118` — `refreshLock` is held across `google.refresh()` (interactive-capable, 15 s timeout on web) and the network exchange; every `ApiClient.send` waits on it (ApiClient.kt:189).
- `EventLinkOpener.kt:26` + `SyncEngine.kt:264,285` — the foreground event-link flow calls `syncAll`, whose `backgroundShown` deliberately hides `NEEDS_SIGN_IN`; the user sees "offline" rather than "sign in again".
- `AppGraph.kt:38,50,56` — forward references to `api`, `account`, `messages` inside lambdas; safe only as long as nothing invokes them during construction.
- `SyncEngine.kt:154-173` — `bandChanged` checks `loop?.isActive` then launches a network round that is not cancelled by `stop()`; it can run while the Android app is already in the background.
- `SyncMessaging.android.kt:60-62` — `onNewToken` in a cold process builds the graph without `load()`, so `session == null` and `register()` is a no-op; the token is only registered at the next `onStart`.
- `PlacesLocator.kt:64` — the only persisted key without a user id; a second account on the device inherits the first one's coordinate cache.
- `BandRepository.kt:434,439` — every `updateIndex`/`persist` launches a new `flush()` coroutine; a burst of N changes queues N flushes behind `writeLock`.
- `App.kt:71` and `Navigation.kt:346-353` — two mechanisms reset navigation after sign-in (new `NavHostController` per user id, and the `signIns` counter); the second is only needed for same-user re-sign-in on the web.
- `Main.kt:28,35` — two `MainScope().launch` collectors are never cancelled (harmless in a single-page app, but they are the only uncancellable coroutines in the codebase).
- `MainActivity.kt:21` — `hasUnsentWork()` reads `bands.locals`, which is not cleared on sign-out, so a background sync can be scheduled for a signed-out user (it then exits early at `loadForBackground`).
- `AttachmentUploads.kt:100` — the whole base64 file is held in memory and passed to `put`; fine for photos, but there is no size guard on this path (the picker side may have one).
- `ApiClient.kt:108,114,154` — "throw on non-2xx" is expressed by calling `parse(response, ErrorResponse.serializer())` for its side effect; it reads as if it returned something.
- `Incoming.kt:29` — `navigatedForBand` is a plain `var` read inside a `LaunchedEffect` (App.kt:119) and written from four suspend sites; correctness depends on the exact order of `bands.open` vs the effect's re-run, documented only in Navigation.kt:365-366.
