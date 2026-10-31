# Server review: `server/`, `localServer/`, `shared/.../api/ApiModels.kt`

Read-only review of the API code as it is on branch `local-dev-setup` (commit 11db045). All paths are relative to
`/home/jacek/IdeaProjects/band_settlement/`; `S/` below means `server/src/main/kotlin/com/jacekgajek/kapelusz/server/`,
`L/` means `localServer/src/main/kotlin/com/jacekgajek/kapelusz/local/`.

Size: `S/` is 24 files, 3114 lines; `server/src/test` 2545 lines (17 files, ~130 tests); `L/` 396 lines (5 files, 7 tests).

---

## 1. Inventory

### 1.1 Request routing

* Transport adapters build one `HttpRequest(method, path, headers, body, query)` (`S/Http.kt:4`):
  `Handler.handle` for API Gateway v2 (`S/Handler.kt:105-128`) and `LocalServer.request` for `com.sun.net.httpserver`
  (`L/LocalServer.kt:75-81`). Both parse the query string with the same 4 lines (`S/Handler.kt:113-116` vs
  `L/LocalServer.kt:83-87`) and both turn a malformed `%` escape into the same 400.
* `Api.handle` (`S/Api.kt:53`) wraps `route` with a try/catch that converts `ApiError` and `SerializationException`
  into JSON error bodies, and runs `deletions.sweep()` first.
* `Api.route` (`S/Api.kt:63-127`, **65 lines**) is the whole routing table: three special cases first (OPTIONS,
  `/calendar/{secret}.ics` by regex, `/e/{eventId}` by regex), then the app-version gate, then `authenticate`, then
  a 27-arm `when` over `(method, segments)` where each arm does its own segment matching
  (`segments.size == 4 && segments[0] == "bands" && segments[2] == "places" && segments[3] == "resolve"`).
  Path parameters are `segments[1]`, `segments[3]` etc. Body parsing (`parse(request, X.serializer())`) and the
  `installation` query parameter (`S/Api.kt:89-90`) are done in the router for the arms that need them.
* Nothing is declarative: there is no list of routes to read, print, or generate OpenAPI from. A 404 for "no route"
  and a 404 for "no such band" come from different places with different codes (`NOT_FOUND` vs `BAND_NOT_FOUND`),
  which is intentional and documented (`S/BandAccess.kt:28-30`).

### 1.2 Handler signature convention

There is no request-context object. Each handler is a method on one of 9 "handler" classes and gets **only the
pieces the router chose to pass**. Counting the 27 routed calls in `S/Api.kt:92-124`:

| Convention | Example | Count |
|---|---|---|
| `(user)` | `users.me(user)` | 4 |
| `(user, body)` | `bandHandlers.create(user, CreateBandRequest)` | 2 |
| `(user, pathParam…)` | `invites.list(user, bandId)`, `attachments.download(user, bandId, attachmentId)` | 8 |
| `(user, pathParam, body)` | `invites.create(user, bandId, CreateInviteRequest)` | 3 |
| `(user, pathParam, installation)` | `bandHandlers.delete(user, bandId, from)` | 3 |
| `(user, pathParam, body, installation)` | `bandHandlers.push(user, bandId, PushRequest, from)` | 1 |
| `(user, pathParam, rawRequest)` | `places.suggest(user, bandId, request)`, `users.picture(user, request)` | 3 |
| `(user, pathParam, rawRequest, body)` | `places.resolve(user, bandId, request, MapLinkRequest)` | 1 |
| `(user, pathParam, header)` | `bandHandlers.state(user, bandId, ifNoneMatch)` | 1 |
| `(user, pathParam, query, query, query)` | `history.history(user, bandId, month, before, months)` | 1 |
| no user (public) | `calendar.feed(secret)`, `linkPages.event(id)` | 2 |

So the same information (a query parameter) reaches a handler three ways: pre-extracted strings (`history`), the whole
`HttpRequest` (`places.*`, `users.picture`), or pre-computed by the router (`installation` → `SenderDevice`).
`users.picture` even parses its own body (`S/UserHandlers.kt:32`) while every other body is parsed by the router.
Handlers return `HttpResponse` directly (`ok(serializer, value)` / `HttpResponse(204)`), or throw `ApiError`.

Parameter counts per handler method: 1 to 5 (no method over 6). The parameter explosion the owner feels is in the
**constructors**, not the methods:

| Class | Constructor params | File |
|---|---|---|
| `Api` | 11 | `S/Api.kt:19-36` |
| `BandHandlers` | 11 | `S/BandHandlers.kt:25-37` |
| `BandDeletions` | 8 | `S/BandDeletions.kt:11-20` |
| `UserHandlers` | 7 | `S/UserHandlers.kt:10-18` |
| `InviteHandlers` | 6 | `S/InviteHandlers.kt:23-30` |
| `FcmSender` | 6 | `S/SyncMessages.kt:109-117` |
| `CalendarHandlers`, `LinkPages` | 4 | |
| `HistoryHandlers`, `PlacesHandlers`, `DeviceHandlers`, `DeviceMessenger` | 3 | |

`Api`'s constructor body (`S/Api.kt:37-50`) is a hand-written object graph of 13 objects with one cycle
(`BandAccess` ↔ `BandDeletions`) broken by a late-bound `var whenDue` (`S/BandAccess.kt:11`, set at `S/Api.kt:49`).
`clock: () -> Long` is threaded through 18 constructor signatures.

### 1.3 Authentication / authorization flow

1. `Api.route` checks the app version from the `appVersion` query parameter (`S/Api.kt:80,133-137`): `minAppVersion <= 0`
   disables the check (tests, localServer); `0` is always allowed ("dev build"); otherwise `>= MIN_APP_VERSION` (12).
   Done before authentication; skipped for OPTIONS, `/calendar/*.ics` and `/e/*`.
2. `Api.authenticate` (`S/Api.kt:139-151`): `Authorization: Bearer` → if the token starts with `as1.` it is a
   Kapelusz session (`SessionTokens.verify`, HMAC-SHA256 with a key stored in the bucket, `S/Sessions.kt:49-59`),
   else a Google ID token (`GoogleTokenVerifier.verify`, Nimbus JWT against Google JWKS, `S/Auth.kt:48-61`).
   Then "signed out everywhere" (`SessionRevocations.isValid`, a per-user `sessionsValidFrom` in `users/{id}.json`,
   cached 60 s per instance). Result: `VerifiedUser(userId, email, name, picture, sessionSince, issuedAt)`.
3. Authorization per band is in `BandAccess` (`S/BandAccess.kt`): `loadForMember` (404 `BAND_NOT_FOUND` for non-members
   and strangers alike, 404 `BAND_DELETED` for former members of a deleted band), `loadForLeader` (403). Role checks
   beyond that are inline in handlers: `S/BandHandlers.kt:148` (owner), `S/BandHandlers.kt:174` (not owner),
   `S/AttachmentHandlers.kt:21` (artist+), `S/PlaceSearch.kt:273` (lead+), `S/BandHandlers.kt:195` (leader for raw).
4. Operation-level permissions are **not re-implemented**: every state change goes through the shared
   `Engine.apply`/`applyBatch` (`shared/.../ops/Engine.kt:30,46`), which runs `Permissions.check` + `Reducer` +
   `Limits.violation`. Server-initiated changes use `Actor.System` (delete `S/BandHandlers.kt:153`, leave `:177`,
   invite accept `S/InviteHandlers.kt:91`); client pushes use `Actor.User(userId)` (`S/BandHandlers.kt:89`). The server
   adds its own checks around the engine: op-id uniqueness, one client id per push, device cap, undo cap, band-size cap
   via the `check` callback (`S/BandHandlers.kt:68-101`), and it **replaces the client's claimed undo content with the
   recorded one** (`HistoryHandlers.recordedUndo`, `S/HistoryHandlers.kt:117-131`). So yes, it re-validates; it never
   trusts the client's view of permissions.

### 1.4 Storage abstractions

| Interface | Methods | Prod | Local | Test |
|---|---|---|---|---|
| `BlobStore` (`S/BlobStore.kt:33-60`) | `get, put(ifMatch/ifNoneMatch), delete, deleteAll, list, exists, presignPut, presignGet` | `S3BlobStore` + `TimedBlobStore` wrapper | `FileBlobStore` (`L/FileBlobStore.kt`) | `InMemoryBlobStore` (`server/src/test/.../InMemoryBlobStore.kt`) |
| `HistoryStore` (`S/HistoryStore.kt:23-31`) | `newestFirst(band, from, to): Sequence`, `put(band, records)`, `deleteBand` | `DynamoHistoryStore` | `FileHistoryStore` | `InMemoryHistoryStore` (test source set) |
| `GeocodeCache` (`S/GeocodeCache.kt:19-30`) | `get(keys)`, `put(answers)` | `DynamoGeocodeCache` | `InMemoryGeocodeCache` (lives in **main**) | same |
| `WebIndex` (`S/LinkPages.kt:13`) | `html()` | `S3WebIndex` | none (null) | lambda |
| `PlaceSearch` (`S/PlaceSearch.kt:32-47`) | 5 methods | `GooglePlaceSearch` | `OfflinePlaceSearch` | anonymous fake in `PlacesTest` |
| `MessageSender` (`S/SyncMessages.kt:47`) | `ready`, `send` | `FcmSender` | none (null) | `FakeSender` |
| `TokenVerifier` (`S/Auth.kt:27`) | `verify`, `pictureOf` | `GoogleTokenVerifier` | `DevTokenVerifier` | lambda |
| `StorageKeys` (`S/Storage.kt:11-55`) | key layout, `safe(id)` | — | — | — |

Consistency: the three `BlobStore` implementations agree on precondition semantics (`ifMatch` on a missing object →
`PreconditionFailed` in all three: `S/BlobStore.kt:85`, `L/FileBlobStore.kt:44`, `InMemoryBlobStore.kt:16`), which is good
and tested (`LocalServerTest.theFileStoreWritesConditionally`). Inconsistencies:

* The in-memory `HistoryStore`/`BlobStore` live in `server/src/test`, but `InMemoryGeocodeCache` lives in `server/src/main`
  so that `localServer` can use it (`L/LocalServer.kt:9,32`). `localServer` cannot use the in-memory blob/history stores
  for the same reason, so it has its own file-backed ones.
* `HistoryStore` is not a "blob store with a different shape": it is keyed `(bandId, sortKey)` with range queries, and the
  grouping of records back into pushes (`#000` suffix) is done by `HistoryHandlers.pushes` (`S/HistoryHandlers.kt:85-95`),
  not by the store.
* "Record" types are split: `UserRecord, JoinAttempts, InviteCodes, DeletedBand, StoredCalendar, CalendarOwner, StoredInvite`
  in `S/Storage.kt`; `StoredDevice(s)` in `S/Devices.kt`; `ScheduledDeletions` in `S/BandDeletions.kt`; `EventPointer`
  in `S/LinkPages.kt`; `ServiceAccount` in `S/SyncMessages.kt`; session `Claims`/`StoredKey` private in `S/Sessions.kt`.
* There is no typed "document" layer: every reader does `store.get(StorageKeys.x(id))?.let { decode(X.serializer(), it.content) }`
  (20 occurrences of `.let { decode(`), and every writer does the read–modify–conditional-put dance by hand
  (`retryOnConflict` appears 33 times; the exact snippet `ifMatch = blob?.etag, ifNoneMatch = blob == null` 7 times:
  `S/Sessions.kt:118`, `S/UserHandlers.kt:70,95`, `S/Devices.kt:64`, `S/InviteHandlers.kt:148,168`, `S/CalendarHandlers.kt:30`,
  `S/BandDeletions.kt:59`; plus the `ifMatch = blob.etag` only variants at `S/UserHandlers.kt:111,129`, `S/BandAccess.kt:57`).

### 1.5 Error / response conventions

* `ApiError(status, code, message)` thrown anywhere → `error(status, code, message)` JSON (`S/ApiSupport.kt:12,41`).
  Helpers `busy()`, `badRequestIf()`. Codes are the `ApiErrors` constants in the shared contract
  (`shared/.../api/ApiModels.kt:48-137`). `ApiErrors.BAND_TOO_LARGE` (413) is declared in the contract but never sent
  by the server (the server rejects individual ops with `LIMIT_REACHED` instead, `S/BandHandlers.kt:99`).
* `ok(serializer, value, headers)` → 200 JSON; `HttpResponse(204)`; 304 with `etag`; HTML/ICS built by hand.
* `ApiError`, `ok`, `error`, `parse`, `encode`, `decode`, `retryOnConflict` are all `internal` (`S/ApiSupport.kt`), so
  `localServer` re-implements the error body (`L/LocalServer.kt:115-119`, `Reply.error`) and duplicates query parsing.
* Non-fatal failures are logged with `System.err.println` (21 sites) inside `runCatching` (68 sites). There is no
  logger abstraction; `Handler` prints one `TIMING` line per request (`S/Handler.kt:101`).
* 500s: `Handler.handle` and `LocalServer.serve` each catch `Exception` and log a stack trace.

### 1.6 Tests

* All server tests are black-box through `Api.handle(HttpRequest(...))` with `InMemoryBlobStore` + `InMemoryHistoryStore`
  and a lambda `TokenVerifier` (`{ token -> VerifiedUser(token, null, null) }` – tokens are user ids). 24 `Api(...)`
  constructions across 13 test files; each file has its own private `call(...)`, `parse(...)`, `setup()`, `push(...)`
  helpers (e.g. `ApiTest.kt:39-57`, `HistoryAndUndoTest.kt:50-106`, `DeleteBandTest.kt:34-56`): the same 20-30 lines
  of harness repeated ~10 times.
* Fakes: `FakeSender` (`SyncMessagesTest.kt:27`), anonymous `PlaceSearch` (`PlacesTest.kt:30`), a flaky `BlobStore by store`
  delegate (`ApiTest.kt:263`), `webIndex = { index }` (`LinkPagesTest.kt:30`). No mocking library. Good.
* Unit-level tests exist for pure parts: `GoogleTokenVerifierTest`, `FcmSenderTest` (against a local HTTP server),
  `HandlerTest` (gateway payload + gzip), `AppVersionTest`.
* `LocalServerTest` boots the real HTTP server on port 0 and also tests `FileBlobStore`/`FileHistoryStore` directly.
* Cross-cutting rules (e.g. "every band route needs membership") are tested only where a specific test happens to
  check a 404; there is no table-driven "every route × unauthenticated → 401" test.

### 1.7 Metrics

* Functions over 50 lines: **2** — `Api.route` (65, `S/Api.kt:63-127`) and `BandHandlers.push` (72, `S/BandHandlers.kt:67-138`).
  Over 30: `PlacesHandlers.locations` (33), `FcmSender.send` (30). The code is otherwise made of short functions.
* Handler methods with more than 6 parameters: **0** (max is 5: `HistoryHandlers.history`).
  Constructors with more than 6: **4** (`Api` 11, `BandHandlers` 11, `BandDeletions` 8, `UserHandlers` 7).
* Duplicated snippets: read-decode (20×), conditional put with `blob?.etag`/`blob == null` (7× exact, 10× variants),
  `bands.loadForMember(user, bandId)` + `band.memberOfUser(user.userId)!!.role.atLeast(...)` + `throw ApiError(403...)`
  (4×: `S/BandAccess.kt:36-37`, `S/BandHandlers.kt:147-148`, `S/AttachmentHandlers.kt:20-21`, `S/PlaceSearch.kt:272-273`),
  `runCatching { history.append(user, band, bandId, listOf(OpEnvelope(opId, "server", 0, now, op)), emptyMap()) }`
  (3×: `S/BandHandlers.kt:160,186`, `S/InviteHandlers.kt:102`), `runCatching { messenger?.bandChanged(...) }` (5×),
  query-string parsing (2×, server vs local), `Band.create(...)` called twice with identical args
  (`S/BandHandlers.kt:43,46`), Dynamo "retry unprocessed items with back-off" loop (3×: `S/HistoryStore.kt:88-93`,
  `S/GeocodeCache.kt:67-82,105-110`).

---

## 2. Problems

### 2.1 Mixed concerns per file

* `S/UserHandlers.kt` is both an HTTP handler (`me`, `picture`, `session`, `revokeSessions`) and the **user-record
  repository** used by six other classes (`loadUser`, `pictureOf`, `addBandToUser`, `removeBandFromUser`,
  `forgetIfNoBands`, `requireRoomForBand`). That is why `BandHandlers`, `InviteHandlers`, `BandDeletions`, `CalendarHandlers`,
  `DeviceHandlers` all take `users: UserHandlers`.
* Same pattern: `InviteHandlers` exposes `revokeFor`/`loadCodes` for `BandHandlers`/`BandDeletions`;
  `HistoryHandlers` is 80% service (`append`, `recordedUndo`, `deleteBand`, paging) and 20% handler;
  `AttachmentHandlers.deleteRemoved` and `LinkPages.pointTo/forget` are post-push hooks called from `BandHandlers.push`.
* `S/SyncMessages.kt` (248 lines) holds 4 things: the `SyncMessenger` interface, `DeviceMessenger` (fan-out logic),
  `FcmSender` (an HTTP client with OAuth), `StoredServiceAccount` (a config loader).
  `S/PlaceSearch.kt` (287): interface + Google client + `PlacesHandlers`. `S/LinkPages.kt`: `WebIndex` + `S3WebIndex`
  + `EventPointer` + `LinkPages`. `S/Devices.kt`: 4 data classes + registry + handlers. `S/Sessions.kt`: token minting
  + revocation cache. `S/BlobStore.kt`: interface + S3 + `CallTimer` + `TimedBlobStore`.
* `Api.companion` (`S/Api.kt:153-194`) is a grab bag of 14 constants for invites, history, bands, devices, and
  `isGooglePhoto()`; `ApiSupport.retryOnConflict` reaches back into `Api.MAX_ATTEMPTS`.
* Configuration is read from `System.getenv` in 9 places spread over `Handler.companion`, `CalendarHandlers.companion`
  (`WEB_URL`, `S/CalendarHandlers.kt:62` – also read in tests), `S3BlobStore.region()`, `DynamoHistoryStore.defaultClient()`,
  `L/LocalServer.kt:160-161`, `localServer/build.gradle.kts:24-28`.

### 2.2 Duplicated plumbing per handler (what you type for a new endpoint today)

To add e.g. `GET /bands/{id}/members/{memberId}/summary` you touch:

1. `shared/.../api/ApiModels.kt` – response type (fine, that is the contract).
2. `S/Api.kt` – a new `when` arm with hand-written segment matching, plus the `parse(...)` if there is a body.
3. A handler class – constructor already has what you need? If not: add a parameter there, and
4. `S/Api.kt:37-50` – pass the extra dependency in the wiring (and, if the handler is used by `BandHandlers`/`BandDeletions`,
   their constructors too: that is how `BandHandlers` got to 11).
5. Inside the handler: `bands.loadForMember(user, bandId)` → role check (copy from one of the 4 sites) → read-decode → `ok(...)`.
6. A test file with its own `call/parse/setup` copy.
7. If it writes a band-related object: remember `invites.revokeFor`, `linkPages.forget`, `attachments.deleteRemoved`,
   `deletions.note`, `history.append`, `messenger?.bandChanged`, `users.removeBandFromUser` — the post-change hooks are
   an unordered list in `push` (`S/BandHandlers.kt:106-135`), partly repeated in `delete`/`leave`/`accept`/`deleteNow`
   with different subsets. Nothing enforces that a new mutation path runs them.

### 2.3 Server vs localServer

* `localServer` genuinely **reuses** `Api` (one line, `L/LocalServer.kt:30-33`) — that is the right shape. What it
  duplicates is the transport edge, because the shared helpers are `internal`: query parsing (`L/LocalServer.kt:83-87`
  = `S/Handler.kt:113-116`), error JSON (`Reply.error` = `ApiSupport.error`), the 500 catch-all, the OPTIONS 204
  (also in `Api.route:66` – so OPTIONS is answered twice-over), CORS headers that API Gateway adds in prod.
* It also owns a mini "S3" (`FileRoutes`, `L/LocalServer.kt:123-155`) for presigned URLs; reasonable, but it is wired
  by string prefix `/_files/` known to both `FileBlobStore.presignPut` and `LocalServer.serve`.
* Naming: server says `HttpResponse`, local says `Reply`; server `TokenVerifier` implementations are `GoogleTokenVerifier`
  / `DevTokenVerifier` (fine); stores are `S3BlobStore`/`FileBlobStore`/`InMemoryBlobStore` but
  `DynamoHistoryStore`/`FileHistoryStore`/`InMemoryHistoryStore` and `DynamoGeocodeCache`/`InMemoryGeocodeCache`/(none):
  the in-memory implementations are inconsistently placed (test vs main) rather than inconsistently named.
* `localServer` cannot run with `MessageSender`/`WebIndex` at all (null), so `/e/*` link pages and sync messages are
  untestable locally; a `NoopSender`/`FileWebIndex` would cost 10 lines each.

### 2.4 Places where one endpoint means edits in many files

Already covered in 2.2; the worst offenders are the two 11-parameter constructors and the implicit post-mutation hook
list. Also `Handler.beforeCheckpoint` (`S/Handler.kt:165-185`) hard-codes a priming request to `/me`; any auth change
needs that updated too.

### 2.5 Concurrency / consistency

* **Band state**: optimistic concurrency via S3 ETags. Every writer does `load → Engine → put(ifMatch = etag)` inside
  `retryOnConflict` (5 attempts, then 409 `BUSY`). `S3BlobStore.put` maps 412/409/404-on-ifMatch to `PreconditionFailed`
  (`S/BlobStore.kt:85`). This is sound and tested (`ApiTest.concurrentWriteIsRetried`).
* **History append**: **no** optimistic concurrency and none needed: each push becomes records with unique keys
  `yyyy-MM/<millis>-<uuid>.json#NNN` (`S/HistoryHandlers.kt:140,150`), written as one DynamoDB transaction (≤100 items,
  ≤3.5 MB, chunked otherwise; `S/HistoryStore.kt:60-79`). Ordering is by server wall clock; two Lambda instances with
  skewed clocks can interleave pushes out of true order, which only affects display order and `before` paging.
  The append happens **after** the band put succeeded and is wrapped in `runCatching` (`S/BandHandlers.kt:123`): a failed
  append loses history silently (logged). Likewise an undo (`recordedUndo`) reads history before the retry loop, so a
  retried batch uses the undo content read before the conflicting write — fine, because history entries are immutable.
* **Side effects after the commit point** (`S/BandHandlers.kt:106-135`) run inside the `retryOnConflict` lambda but after
  the `put`, so they are never retried; each is `runCatching`-isolated. Consistency is therefore "eventual by
  idempotent repair": `finishJoin` is re-run on a retried accept (`S/InviteHandlers.kt:81`), `requireRoomForBand` prunes
  stale band ids lazily (`S/UserHandlers.kt:82-83`), `me` filters bands the user is no longer in (`S/UserHandlers.kt:22-24`).
* **Thread pools inside a Lambda**: `HistoryHandlers.reader` (3 threads), `LinkPages.writers` (8), `DeviceMessenger.readers`
  (cached), virtual threads in `locations`. Deadlock avoidance is by convention: `BandAccess.load(deleteIfDue = false)`
  exists because a `pointTo` running on the `writers` pool could otherwise trigger `deleteNow → forget → writers.submit().get()`
  (`S/BandAccess.kt:15-17`, `S/LinkPages.kt:77`). That is a trap for the next person who calls `bands.load` from a pool thread.
* `localServer` runs 8 request threads against stores that are `@Synchronized` per call, so cross-object races are the
  same as in prod (ETags) — good, it exercises the real retry paths.

### 2.6 Versioning / min-app-version

* Enforced by the `appVersion` query parameter only (`S/Api.kt:133-137`), with `0` and any value ≥ `MIN_APP_VERSION`
  accepted. It is a cooperation gate (a client can send `appVersion=0`), not a security boundary; that is fine for its
  purpose (forcing updates) but worth stating in the code. The constant lives in `Api.companion` with a long comment on
  how to raise it (`S/Api.kt:154-164`); `scripts/deploy.sh:20-28` refuses to deploy a lower version code than live.
  Tests pass `minAppVersion = 0` by default and `LocalServerTest` sends `v=0`? No: it sends `?v=0`
  (`LocalServerTest.kt:134`), which is **not** the parameter name (`appVersion`); it only works because the local
  server has no minimum. Harmless, but misleading.
* There is no API version in the path or a header; the response shapes are versioned implicitly by `MIN_APP_VERSION`.

---

## 3. Target design (as if from scratch, same storage formats)

### 3.1 One request context, one handler shape

```kotlin
/** Everything a handler may want, built once per request by the router. */
class Call(
    val request: HttpRequest,          // method, path, headers, query, body
    val user: VerifiedUser,            // null only for the two public routes, which get a different type (see below)
    val params: Map<String, String>,   // path parameters by name, from the route pattern
    val installation: SenderDevice?,   // the `installation` query parameter, validated
    val now: Long,                     // clock() read once per request
) {
    fun <T> body(serializer: KSerializer<T>): T      // = parse(request, serializer)
    fun param(name: String): String                  // throws 400 when missing
    fun query(name: String): String?
    fun header(name: String): String?
}

typealias Handler = (Call) -> HttpResponse
typealias PublicHandler = (HttpRequest, Map<String, String>) -> HttpResponse
```

Handlers become `fun state(call: Call): HttpResponse` and read what they need from `call`; the "how many parameters"
question disappears, and query/header/body access is uniform.

### 3.2 A routing table instead of a `when`

```kotlin
class Route(val method: String, val pattern: String, val auth: Auth, val handler: Handler)
enum class Auth { PUBLIC, USER }   // PUBLIC also skips the app-version gate

val routes = listOf(
    Route("GET",    "/me",                              USER, users::me),
    Route("POST",   "/bands",                           USER, bands::create),
    Route("GET",    "/bands/{bandId}/state",            USER, bands::state),
    Route("POST",   "/bands/{bandId}/ops",              USER, bands::push),
    Route("GET",    "/bands/{bandId}/log",              USER, history::page),
    Route("GET",    "/bands/{bandId}/places/{placeId}", USER, places::details),
    Route("GET",    "/calendar/{secret}.ics",           PUBLIC, calendar::feed),
    Route("GET",    "/e/{eventId}",                     PUBLIC, links::event),
    …
)
```

A 30-line `Router` matches `{name}` segments (the regex constraints for `secret`/`eventId`/`code` move into
`call.param` validators or `StorageKeys.safe`), answers OPTIONS/HEAD generically, runs the version gate and
`authenticate` for `USER` routes, and builds the `Call`. The table can be printed, tested in one loop
("every USER route without a token → 401"), and is the obvious thing to edit for a new endpoint.

### 3.3 One file per resource, split into handler + repository

```
server/.../server/
  Http.kt            HttpRequest/HttpResponse, Call, ApiError, ok/error/parse  (public, not internal)
  Router.kt          Route, Router, authenticate, appSupported
  Api.kt             class Api(deps: Dependencies) { val routes = …; fun handle(request) }   (~60 lines)
  Dependencies.kt    class Dependencies(store, history, verifier, clock, placeSearch?, geocode?, webIndex?, messages?, config)
  Config.kt          data class ServerConfig(minAppVersion, maxBandBytes, webUrl, …) — the only place env is read (in Handler/LocalServer main)
  store/
    BlobStore.kt, HistoryStore.kt, GeocodeCache.kt       interfaces only
    Documents.kt     typed read/modify helpers (below)
    StorageKeys.kt
    s3/ S3BlobStore.kt, DynamoHistoryStore.kt, DynamoGeocodeCache.kt, CallTimer.kt
    memory/ InMemoryBlobStore.kt, InMemoryHistoryStore.kt, InMemoryGeocodeCache.kt     (in MAIN, used by tests and local)
  users/   UserRecords.kt (repository), UserHandlers.kt, Sessions.kt, SessionRevocations.kt
  bands/   BandAccess.kt, BandHandlers.kt, BandDeletions.kt, BandChanged.kt (post-mutation hooks, see 3.5)
  history/ HistoryLog.kt (append/recordedUndo/paging), HistoryHandlers.kt
  invites/ InviteCodes.kt (repository), InviteHandlers.kt
  attachments/, calendar/, places/, links/, devices/, messaging/   — same split: *Store/*Records + *Handlers
  auth/    TokenVerifier.kt, GoogleTokenVerifier.kt
  lambda/  Handler.kt (API Gateway adapter + SnapStart priming only)
```

### 3.4 A typed document layer over `BlobStore` (removes 10 copies of the retry dance)

```kotlin
/** A JSON object at a fixed key with a known type; all the read–modify–write boilerplate lives here. */
class Document<T>(private val store: BlobStore, private val key: String, private val serializer: KSerializer<T>) {
    fun read(): Pair<T, String>?                       // decoded + etag
    fun get(): T? = read()?.first
    /** Read, change, conditional write; retried on conflict; `change` returning the same value writes nothing. */
    fun update(exhausted: () -> Unit = { throw busy(...) }, change: (T?) -> T?): T?
    fun create(value: T): String                       // ifNoneMatch
    fun delete()
}
// usage
fun addBandToUser(userId: String, bandId: String) =
    users.record(userId).update { r -> (r ?: UserRecord(userId)).let { if (bandId in it.bandIds) it else it.copy(bandIds = it.bandIds + bandId) } }
```

`SessionRevocations.revoke`, `UserHandlers.{rememberPicture,addBandToUser,removeBandFromUser,forgetIfNoBands}`,
`DeviceRegistry.update`, `InviteHandlers.{reserveJoinAttempt,updateCodes}`, `CalendarHandlers.subscription`,
`BandDeletions.note`, `BandAccess.setMemberPicture` all collapse into one-liners on a `Document`. The stored JSON is
byte-for-byte what it is today.

### 3.5 One place for "a band changed"

```kotlin
/** Everything that must follow a committed change of a band's state, in one place, each step isolated. */
class BandChanged(private val invites: InviteCodes, private val links: EventPointers, private val attachments: AttachmentFiles,
                  private val deletions: BandDeletions, private val history: HistoryLog, private val users: UserRecords,
                  private val messenger: SyncMessenger?) {
    fun after(user: VerifiedUser, before: Band, after: Band, accepted: List<OpEnvelope>, changes: Map<String, BandChange>, from: SenderDevice?)
}
```

`push`, `delete`, `leave`, `accept` and `deleteNow` call `bandChanged.after(...)` and lose their copy of the list.
`BandHandlers` drops from 11 constructor parameters to 4 (`store`, `bands`, `users`, `bandChanged`) and `push`
to ~35 lines.

### 3.6 Storage: one interface family, three wirings

Keep `BlobStore`, `HistoryStore`, `GeocodeCache` as they are (they are already the right seams), but:

* move all three in-memory implementations to `server/src/main` (a `memory` package; they are tiny and have no deps),
* give `localServer` the choice `--memory` vs files (the file ones stay in `localServer`),
* add `NoopMessageSender` and `FileWebIndex(path)` so nothing is `null` in any wiring,
* `TimedBlobStore` stays a decorator applied only in `lambda/Handler`.

`localServer` then becomes: a `JdkHttpServerAdapter` (request/response conversion, CORS) + `FileRoutes` + a `main` that
builds `Dependencies(FileBlobStore, FileHistoryStore, DevTokenVerifier, OfflinePlaceSearch, InMemoryGeocodeCache,
FileWebIndex?, NoopMessageSender, ServerConfig(minAppVersion = 0))` and calls `Api(deps).handle`. The adapter shares
`parseQuery`, `error(...)` and the OPTIONS answer with the Lambda adapter via a public `HttpAdapters.kt`.

### 3.7 Tests

One `ApiHarness` in `server/src/test` (`api`, `call()`, `parse()`, `createBand()`, `push()`, `invite()`, `now`),
used by every test file instead of 10 private copies; plus one table-driven `RoutesTest` over `api.routes`
(401 without token, 426 below min version, 404 for unknown band for every `/bands/{bandId}/…` route).

---

## 4. Migration plan (ordered, each step shippable and green on its own)

| # | Step | Files touched | Risk | Tests |
|---|---|---|---|---|
| 1 | **Make the helpers public and share the transport edge.** Drop `internal` from `ApiSupport.kt`; add `parseQuery(raw)` and reuse it + `error()` in `LocalServer`; delete `Reply.error`. | `S/ApiSupport.kt`, `S/Handler.kt`, `L/LocalServer.kt` | none | existing; `LocalServerTest` + `HandlerTest.malformedQueryStringIsABadRequest` cover it |
| 2 | **Test harness.** Add `server/src/test/.../ApiHarness.kt`; migrate test files one at a time to it (mechanical). | 13 test files | none (tests only) | all carry over unchanged in intent |
| 3 | **`Document<T>` layer.** Add `S/Documents.kt`; convert the 10 read–modify–write sites one by one, keeping the same keys and serializers. | `S/Sessions.kt`, `S/UserHandlers.kt`, `S/Devices.kt`, `S/InviteHandlers.kt`, `S/CalendarHandlers.kt`, `S/BandDeletions.kt`, `S/BandAccess.kt` | low: behaviour change only if a conversion forgets the "unchanged → no write" rule; the `flaky` store test and `SessionTest`/`LeaveBandTest` cover the retries | existing |
| 4 | **Split repositories out of handlers.** `UserRecords` (from `UserHandlers`), `InviteCodes` (from `InviteHandlers`), `HistoryLog` (from `HistoryHandlers`), `EventPointers` (from `LinkPages`), `AttachmentFiles`. Handlers keep only HTTP methods. | those 5 files + `S/Api.kt` wiring + `S/BandHandlers.kt`, `S/BandDeletions.kt` imports | low: pure moves; constructor lists shrink | existing |
| 5 | **`BandChanged` hook.** Extract the post-commit block of `push` into `BandChanged.after`; call it from `delete`, `leave`, `accept`, `deleteNow`. This is the only step that changes behaviour: `delete`/`leave` currently skip `linkPages`/`attachments` steps (correct, nothing changes there) — guard with `if (before.events != after.events)` etc. so the union is a no-op for them. | `S/BandHandlers.kt`, `S/InviteHandlers.kt`, `S/BandDeletions.kt`, new `S/BandChanged.kt` | **medium**: ordering of side effects matters for sync messages ("message before forgetting devices", `S/BandHandlers.kt:187-189`); keep the order from `push` and add a test asserting it | `SyncMessagesTest`, `LeaveBandTest`, `DeleteBandTest`, `InviteAndHistoryTest` cover every path |
| 6 | **`Call` + routing table.** Add `Call`, `Route`, `Router`; rewrite `Api.route` as a table; change handler signatures to `(Call)`. Do it in one PR but mechanically: each arm becomes one `Route` line and one signature change. | `S/Api.kt`, `S/Http.kt`, new `S/Router.kt`, every `*Handlers.kt` | medium: path-matching regressions (e.g. trailing slash on `/e/{id}/`, `HEAD` on feed/link); add `RoutesTest` first with the current behaviour pinned (status per route for anonymous/old/member/stranger) | the table test is new; the rest unchanged |
| 7 | **Config object.** `ServerConfig` built in `Handler.companion` and `LocalServer.main`; remove `System.getenv` from `CalendarHandlers`, `S3BlobStore`, `DynamoHistoryStore`. | `S/Handler.kt`, `S/CalendarHandlers.kt`, `S/BlobStore.kt`, `S/HistoryStore.kt`, `L/LocalServer.kt`, `localServer/build.gradle.kts` | low | `CalendarTest` stops depending on the env default |
| 8 | **Packages + in-memory stores to main.** Move files into `store/`, `users/`, `bands/`… packages; move `InMemory*` to main; add `NoopMessageSender`, `FileWebIndex`; give `localServer` a `--memory` flag. | all of `S/`, `L/`, test imports | none (moves) | unchanged |
| 9 | **Split the 4-concern files.** `SyncMessages.kt` → `messaging/{SyncMessenger,DeviceMessenger,FcmSender,StoredServiceAccount}.kt`; same for `PlaceSearch.kt`, `LinkPages.kt`, `Devices.kt`, `BlobStore.kt`. | those files | none | unchanged |

Steps 1-4 and 7-9 are refactors with no observable change; 5 and 6 each deserve their own PR with the pinning tests
written first. The S3/Dynamo key layout and every stored JSON shape stay identical throughout; `DISASTER_RECOVERY.md`
and `scripts/deploy.sh` need no change.

---

## 5. Noticed, not fixed

Possible bugs / security / races, one line each. None are blockers; several are documented trade-offs.

1. `S/InviteHandlers.kt:48-53` — the invite object is written (line 50) before the band's code list is updated
   (line 53, a separate retry loop); if `updateCodes` exhausts its retries the invite stays valid for 72 h but
   is invisible to `list`, `revokeFor` and `deleteNow` (all iterate `loadCodes`), so a leader unlinking that member
   cannot revoke it. Writing the code list first (or tolerating a code not found when listing) closes it.
2. `S/BandHandlers.kt:78,83` — `loadForMember` is called twice per push (once before `recordedUndo`, once per attempt):
   two S3 reads on every push even without undos.
3. `S/BandHandlers.kt:43,46` — `Band.create(...)` is built twice with the same arguments; the first only feeds
   `Limits.violation`. Harmless, confusing.
4. `S/UserHandlers.kt:66-73` — `rememberPicture` iterates all the user's bands **inside** the `retryOnConflict`
   lambda, so a conflict on the user record repeats every band update (each itself a conditional write).
5. `S/HistoryHandlers.kt:48-56` — history pages are fetched from DynamoDB before the membership check; non-members
   get a 404 but still cost a query per month. Also `reader` is a JVM-wide pool of 3: in `localServer` (8 request
   threads) two concurrent 3-month requests serialise.
6. `S/BandAccess.kt:18-23` + `S/LinkPages.kt:57` — any read (`GET /me`, an anonymous `GET /e/{id}`, an anonymous
   calendar fetch) can execute a full band deletion (`deleteNow`: list, bulk delete, Dynamo batch delete, FCM
   fan-out) inside that request. By design, but the anonymous paths amplify an unauthenticated request into heavy
   work; and the `deleteIfDue=false` convention (`S/BandAccess.kt:15-17`) is the only thing keeping `pointTo` on the
   `writers` pool from deadlocking on itself.
7. `S/Api.kt:133-137` — `appVersion=0` is always accepted, so the minimum-version gate is advisory; a stale client
   that sends 0 is served. Fine for forcing updates, not for retiring a data format.
8. `S/Api.kt:147-148` — a Google token without `iat` counts as issued at 0 (`S/Auth.kt:59`), so after one
   "sign out everywhere" such a token is refused forever; Google always sets `iat`, so theoretical.
9. `S/Sessions.kt:104` — `SessionRevocations.cache` grows without bound (one entry per user seen by the instance);
   bounded in practice by Lambda lifetime, unbounded in `localServer`.
10. `S/CalendarHandlers.kt:29` — the `calendar/{secret}.json` mapping is written without `ifNoneMatch`; with a 24-byte
    random secret a collision is not a practical concern, but a leftover mapping from a failed attempt is accepted
    as a valid key for 0 bytes of work (the feed re-checks the user's current secret, so no data leak).
11. `S/Handler.kt:146` and `:151` — default bucket/table names (`jacekgajek-art-split-data`, `art-split-history`)
    are hard-coded fallbacks; a misconfigured environment silently talks to production.
12. `S/HistoryHandlers.kt:140` — history entry names use the server clock; two instances with skew interleave
    pushes out of order (display/paging only).
13. `S/BandHandlers.kt:123-124` — a failed history append is logged and dropped; the band change stands. Documented,
    but there is no later repair, so a Dynamo outage leaves permanent gaps that undo cannot see.
14. `S/BlobStore.kt:176` — `TimedBlobStore` does not time `presignPut/presignGet` (delegated); minor, the timing
    line under-reports attachment routes.
15. `shared/.../api/ApiModels.kt:50` — `ApiErrors.BAND_TOO_LARGE` is part of the contract but never produced; clients
    may have dead handling for it.
16. `localServer/src/test/.../LocalServerTest.kt:134` — sends `?v=0` while the version parameter is `appVersion`; passes
    only because the local server has no minimum.
17. `S/LinkPages.kt:92-96` — `inParallel` submits to a bounded pool and blocks on each future with no timeout; a slow
    S3 makes a push with hundreds of new events wait for all pointers (inside the Lambda time budget, but with no
    cap).
18. `S/PlaceSearch.kt:238` — `locations` uses `java.time.LocalDate.now()` instead of the injected clock; the
    "already played" cut-off is untestable with the fixed clocks the tests use.
19. `S/Devices.kt:91` — `register` keeps a device only if `loadUser(...).bandIds` is non-empty; band ids are pruned
    lazily, so a user who left their last band through a path that failed to update the record keeps receiving
    registrations (not messages: `messagedUsers()` reads the band).
