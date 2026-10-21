# Art Split — Refined Plan

A simple, CRM-style app for settling money between band members after concerts.
It replaces the `Rozliczenia.ods` spreadsheet, which is now used only as golden test data.

Targets: **Android** and **Web (Compose/Wasm)**. The web app is fully featured and doubles as the iPhone/desktop client.
UI languages: **Polish and English**, following the device language with a manual override in Settings. Code, identifiers and docs are in English.

---

## 1. Glossary

| Term | Meaning |
|---|---|
| **User** | A Google account signed in to the app. Identified by Google `sub`. |
| **Band** | A group with its own roster, events, costs and band fund. A user may belong to several bands. |
| **Member** | An entry on a band's roster (e.g. "Alice"). Linked to **at most one** user. Within a band, user ↔ member is 1:1. Unlinked members are allowed: a lead enters their data for them. |
| **Guest** | An ad-hoc person who exists inside one event only (e.g. "Helper 2"). Has no login. Can be promoted to the roster. |
| **Participant** | A member or guest taking part in an event. Has a **share weight**. |
| **Holding** | Money a participant **collected** for an event (hat, door, ticket service…). |
| **Cost** | Money a member spent on behalf of the band. It belongs to the **member**, not to an event. |
| **Refund** | Part (or all) of a cost repaid out of a specific event's income. |
| **Transfer** | Money handed from one party to another (participant ↔ participant, or ↔ band fund). |
| **Band fund** | Shared band money ("Kasa zespołowa"), physically held by the **treasurer**. |
| **Treasurer** | A band **setting**: which member holds the band fund. It is **not a role** and grants no permissions. |

---

## 2. Settlement logic (domain core)

All money is stored as `Long` minor units (grosze). Never use floating point. There is **one currency per band**, chosen when the band is created (default PLN).

### 2.1 Event inputs
- **Participants:** members and/or guests, each with an integer **share weight** in percent (default `100`).
  - `150` = a 1.5× share.
  - `0` = takes part but gets no share, e.g. a helper who only collected money.
- **Holdings:** a list of *(participant, amount, source, note?)*.
  - Source is one of `TICKETS_ONLINE | DOOR | HAT | MERCH | CONTRACT | SPONSORSHIP | OWN_CONTRIBUTION | OTHER`.
  - **Event income = sum of holdings.** There is no separate income table.
- **Band fund amount** (`fundAmount`, may be negative):
  - Positive: taken off the top and handed to the treasurer.
  - Negative: the fund covers part of the event, paid out by the treasurer.
  - Disabled until a treasurer is set.
- **Refunds:** a list of *(costId, amount)*. Only costs of **participants of this event** may be refunded here.

### 2.2 Computation
```
refundsTotal = Σ refunds.amount
net          = income − fundAmount − refundsTotal
share(p)     = net × weight(p) / Σ weights          (exact rational)
payout(p)    = floor(share(p)) + leftover(p) + Σ refunds of p's costs in this event
```
- **Rounding:** each share is rounded down. The leftover grosze (`net − Σ floor(share)`) are handed out one at a time, largest fractional remainder first, ties broken by roster order (guests last). This guarantees `Σ payout = income − fundAmount`.
- **Σ weights = 0 with net > 0:** settlement is blocked ("nobody has a share").
- **net < 0 (loss):** settlement is **blocked**. A banner shows "Loss: X zł — fix before settling" with three fixes:
  1. **Defer** some refunds (fully or partly). The deferred part stays pending on the cost.
  2. Add income, e.g. a lead adds an `OWN_CONTRIBUTION` holding.
  3. Use a negative band-fund amount (cover from the fund).

### 2.3 Balances, transfers, status
- **Target** for each party:
  - Participant: `payout(p)`.
  - Band fund (held by the treasurer): `fundAmount`.
- **Current balance** = collected − sent + received, counting only **settled** (recorded) transfers.
- **Suggested transfers:** greedy matching of the largest surplus to the largest deficit until everyone hits their target. This gives ≤ n−1 transfers. They are recomputed live from current balances and never stored.
  - When the treasurer holds the surplus personally, the fund transfer shows as "Alice: keep 100 zł for band fund [Confirm]".
- **Manual transfers:** any party may hand money to any other party (e.g. Dave gives all hat money to Alice so that Alice does the distribution). Suggestions recompute afterwards.
- **Settle** on a suggested transfer records it as a transfer. **Part…** records only part of it (e.g. the cash someone has on them, at most the suggested amount); the remainder stays suggested. **Settle** next to a member records all of that member's pending suggested transfers. **Settle All** records every suggested transfer of the event.
- **Status**, always derived and never set by hand:

  | Status | When |
  |---|---|
  | `DRAFT` | no holdings yet |
  | `LOSS` | net < 0 |
  | `OPEN` | some party's balance ≠ target |
  | `SETTLED` | every balance == target (exact, integer grosze) |

  Archiving is a separate `archived` flag (hidden from the list by default), not a status, so an archived event still shows whether it was settled.

- Editing a settled event (late cost, changed share…) reopens it as `OPEN` and produces compensating suggestions. Recorded transfers are **never rewritten**.

### 2.4 Costs and amortization
- **Cost fields:** member, amount (required), date (default today), optional "incurred for" event, optional category (`TRAVEL | ACCOMMODATION | EQUIPMENT | FOOD | OTHER`), optional description, up to **5 attachments** (images and/or PDFs).
- **Outstanding** = amount − Σ refunds assigned across all events and the fund. This prevents refunding a cost twice.
- **Paid** = Σ refunds in **settled** events + fund refunds. The owner sees a status based on this: `PENDING` (nothing assigned), `REFUND_PLANNED` (assigned, not paid yet), `PARTLY_REFUNDED`, or `REFUNDED`. If a settled event reopens, its refunds count as planned again.
- Refunds are always 100% of what you choose to refund. There are no refund percentages; to refund less, enter the refundable amount.
- **Defaults** when a lead opens an event's Refunds section:
  - Costs **incurred for this event** are pre-included in full.
  - Outstanding costs of **this event's participants** from earlier, plus costs not tied to any event, are listed as "Pending from earlier", oldest first, with one-tap *Include*.
  - Any included refund can be lowered (partial) or removed (deferred).
- A member's own cost becomes **locked** for that member once any part is refunded. A lead can still edit it, but never below the refunded amount.
- The band fund can refund a pending cost directly via a fund entry linked to the cost (lead+).

### 2.5 Band fund
- **Balance** = Σ settled fund transfers from events + Σ manual fund entries.
- **"Not yet handed over"** = Σ event `fundAmount` − Σ settled fund transfers.
- The balance **may go negative** (the treasurer covered the difference); the UI shows it in red. Nothing blocks it.
- **Manual entries:** description + signed amount (e.g. "Mic −400", "Refund Dave's train −322" linked to the cost). Lead+ only.
- **The whole feature is disabled until an admin picks the treasurer.** Until then the UI shows "Admin must choose who holds the band fund", with a shortcut for admins.

### 2.6 Golden tests
Each case covers a **different behaviour**. Real cases come from `Rozliczenia.ods` and `Rozliczenia DV.ods`; the synthetic ones cover edge cases the sheets don't have.

| # | Behaviour under test | Source | Input | Expected |
|---|---|---|---|---|
| G1 | Even split + refunds of two members' costs; a roster member not taking part | Rozliczenia / Oakfield Jazz Club 2026-09-13 | DOOR 1000.00 held by Dave; costs Carol 30.00, Dave 322.00 (incurred for this event); participants Alice, Bob, Carol, Dave, Frank @100 (Eve absent) | payouts Alice 129.60, Bob 129.60, Carol 159.60, Dave 451.60, Frank 129.60; 4 suggested transfers from Dave |
| G2 | Guest @0 who collected money; several holders; absent member | Rozliczenia / Millbrook Harvest Fair | DOOR 2000.00 held 500 each by Alice, Bob, Carol, guest Helper 2; participants Alice, Bob, Carol, Eve @100, Helper 2 @0 | payouts 500.00 ×4, Helper 2 0.00; single suggestion Helper 2 → Eve 500.00 |
| G3 | Multiple income sources with notes; income fully consumed by one member's refund → SETTLED with no transfers | DV / Carried over | CONTRACT 1870.00 + SPONSORSHIP 200.00 ("sponsorship via Grace"), both held by Carol; Carol refund 2070.00; 5 members @100 | net 0; Carol payout 2070.00, others 0.00; status SETTLED immediately |
| G4 | **Amortization:** one cost repaid in parts across events | DV / Carried over + Southgate Park + Grand Hotel gala | Carol cost 4970.00; refunds 2070.00 (G3), 1000.00 (CONTRACT 1000.00), 1900.00 (MERCH 400.00 + CONTRACT 1500.00) | after event 1: PARTLY_REFUNDED, outstanding 2900.00; after 2: 1900.00; after 3: REFUNDED, all three events SETTLED |
| G5 | Whole income to band fund, collected by a non-treasurer | DV / Four Lanterns pub | HAT 710.00 held by Dave ("hat at Four Lanterns"); fundAmount 710.00; treasurer Carol | net 0, payouts 0; suggestion Dave → Band fund (Carol) 710.00 |
| G6 | Treasurer collected the fund money themselves | DV / Elmwood village fete (adapted) | CONTRACT 813.00 held by Carol; fundAmount 813.00; treasurer Carol | suggestion "Carol: keep 813.00 for band fund"; after both G5 and G6 settle, fund balance 1523.00 |
| G7 | Single holder owes several members; a member sitting out; OPEN until settled | DV / 2023-09 Riverside Arts Night | CONTRACT 1200.00 held by Carol; participants Carol, Grace, Dave, Ivan @100 (Heidi absent) | payouts 300.00 each; suggestions Carol → Grace/Dave/Ivan 300.00; OPEN → Settle All → SETTLED |
| G8 | Rounding of leftover grosze | synthetic | net 100.00, 3 participants @100 | 33.34, 33.33, 33.33 (extra grosz to first in roster order) |
| G9 | Unequal weights | synthetic | net 1000.00, A @150, B @100, C @100 | 428.57, 285.72, 285.71 (leftover grosz: B and C tie on remainder, B wins by roster order) |
| G10 | Loss blocks settlement; deferral fixes it | synthetic | income 100.00, refund 150.00 | LOSS, Settle disabled; defer 50.00 → net 0, settleable, cost outstanding 50.00 |
| G11 | Manual hand-over between holders, then recompute | G2 + transfer | in G2, Carol hands 500.00 to Alice | suggestions recomputed; still ends SETTLED with Σ balances = Σ payouts |
| G12 | Late edit reopens a settled event | G1 + edit | after Settle All, add Bob cost 50.00 refunded here | status OPEN; compensating suggestions only; recorded transfers unchanged |

---

## 3. Roles and permissions

Roles form **one cumulative axis**. Every user in a band is linked to a member and has exactly one role.

| Action | Viewer | Member | Lead | Admin | Owner |
|---|---|---|---|---|---|
| See all events, finances, band fund, history | ✔ | ✔ | ✔ | ✔ | ✔ |
| Add/edit/delete **own** costs + attachments (locked once partly refunded) | – | ✔ | ✔ | ✔ | ✔ |
| Record **own** holdings in events they take part in | – | ✔ | ✔ | ✔ | ✔ |
| Record/settle transfers **they send or receive**, "Hand over money" | – | ✔ | ✔ | ✔ | ✔ |
| Create/edit/archive/delete events, participants, guests, shares, refunds, fund amount, fund entries | – | – | ✔ | ✔ | ✔ |
| Edit anyone's costs/holdings, Settle / Settle All on any event | – | – | ✔ | ✔ | ✔ |
| Edit roster, roles, treasurer, invites, unlink users, band history | – | – | – | ✔ | ✔ |
| Delete band, transfer ownership | – | – | – | – | ✔ |

- The **treasurer** counts as the band fund's party: a treasurer with the Member role may record and settle transfers to or from the band fund, like their own.
- There is exactly **one owner**: the band's creator. The owner is always an admin, so there is always at least one admin. Ownership transfer goes to an admin, and the former owner stays an admin.
- An admin cannot demote the owner. The last-admin rule is satisfied automatically.
- Permissions are enforced **on the server** (shared code) and mirrored in the UI: actions you can't perform are hidden.
- The event list has an **"Only my events"** filter. It is remembered per user and off by default.

---

## 4. Screens and UX

### 4.1 Navigation
Mobile uses a bottom nav and web uses a side rail. Tabs are shown only if the role can use them.

1. **Events** (home)
   - A **"Continue: <last edited event>"** card at the top, and a **"+ New event"** button (lead+).
   - Each row shows: name, date, location, participant avatars, status chip, and **your** number ("you get 129,60 zł" / "you hand over 451,60 zł").
   - Filters: "Only my events", show archived.
2. **My costs** (member+)
   - Your costs with Pending / Partly / Refunded filters.
   - A big **"+ Add cost"** button that opens a fast form: amount first, everything else optional, attachment from camera/gallery/file.
   - Leads see a **"Paid by"** dropdown (default: themselves) to add a cost for another member.
   - Leads can switch the list between **Mine** and **All members** (each row then shows who paid).
3. **Settle** (member+)
   - Every pending suggested transfer where you're sender or receiver, across events, each with **Settle**.
   - **"Hand over money"**: pick a party; the amount defaults to your whole balance in that event and can be edited.
   - For leads, a second section lists all `OPEN`/`LOSS` events.
4. **Band fund** (everyone sees it, lead+ edits it).
5. **⚙ Cog** in the top bar: band switcher, Settings (language), profile, sign-out, and **Admin** (admin+).

The viewer sees Events and Band fund.

### 4.2 Event screen
- **Header:** name, date, location, notes, status chip.
- **Participants:** avatar chips, **pre-selected from the last event**, tap to toggle. "+ Guest" sits alongside. "Adjust shares" reveals the weights (default 100).
  - Removing a participant who has holdings, transfers or refunds is blocked ("move or delete their entries first").
- **Collected money:** a list of holdings with + Add.
- **Refunds:** included costs, plus "Pending from earlier" (§2.4).
- **Band fund amount** (if a treasurer is set).
- **Summary table:** each participant's share, refunds, payout, current balance, delta, and a per-member **Settle** button.
- **Suggested transfers** with **Settle** each, plus **Settle All**. Disabled while `LOSS`.
- **Recorded transfers.**
- **History** of this event.
- **Delete:** only if the event has no recorded transfers; otherwise **Archive**. Deleting removes its refunds, so those costs become pending again.

### 4.3 Zero screen and invites
- A user with no bands sees **Create band** (name + currency → they become the owner, and a member entry for them is created) or **Join band**.
- **Invite** (admin only):
  - Created on an *unlinked* member, with a chosen role (default Member).
  - Produces a **6-character code** from the alphabet `23456789ABCDEFGHJKMNPQRSTUVWXYZ`. It is valid for **72 h** and single use.
  - A new code for the same member revokes the old one. Admins see active codes and can revoke them.
  - The share sheet sends: "Join <Band> in Art Split: code ABC123 — https://artsplit.jacekgajek.com/join/ABC123".
- **Join link** behaviour:
  - Android with the app installed: an App Link opens the app.
  - Otherwise: the web app opens, signs in with Google, and joins in the browser. The page offers "Get the Android app" (latest APK).
  - Typing the code manually also works.
- The confirmation screen reads: "You'll join *Band* as *Alice* (Member)".
- **Brute-force protection:** 10 failed code attempts per user per hour, plus API throttling.
- An admin can **unlink** a user from a member (e.g. lost account), so the member can be invited again.

### 4.4 Admin
- **Roster:** add, rename, reorder (the order is used for tie-breaking), archive. Hard delete is allowed only if the member never took part in anything.
- **Roles, invites, unlink, treasurer, transfer ownership** (owner only), and band-wide history.

### 4.5 Attachments
- Up to 5 per cost, images and/or PDFs.
- **Images** are compressed on the device: longest edge 1600 px, JPEG quality ~80, EXIF/GPS stripped.
- **PDFs** are uploaded as-is, max 10 MB, and open in the system viewer or a new browser tab.
- Uploads go through **presigned PUT URLs** in the background. The file is stored locally first and shows an "uploading" indicator until it's sent.
- Anyone who can see the cost can see its attachments. Deletion removes the S3 object; bucket versioning keeps it for 30 days.

### 4.6 History (audit log)
- Every accepted operation is appended server-side to the band's log.
- It is shown as human-readable entries ("Alice recorded transfer Dave → Bob 129,60 zł · 2 h ago") per event and band-wide.
- Read-only. No undo in v1.

---

## 5. Architecture

```
┌──────────────┐  ops batch / state   ┌─────────────────────┐   JSON    ┌──────────────────────────┐
│ Android app  │ ───────────────────► │ API Gateway HTTP API │ ────────► │ Lambda art-split-api     │
│ Web (Wasm)   │ ◄─────────────────── │ (throttled)          │           │ JVM21 arm64 SnapStart    │
└──────┬───────┘  Google ID token     └─────────────────────┘           │ :shared reducer+perms    │
       │                                                                 └────────────┬─────────────┘
       │ presigned PUT/GET (attachments)                                              │
       └─────────────────────────────► S3 jacekgajek-art-split-data ◄─────────────────┘
```

### 5.1 Gradle modules (`com.jacekgajek.artsplit`)
```
shared/      KMP (android, wasmJs, jvm). commonMain:
               model/   Band, Member, Guest, Event, Participant, Holding, Cost, Attachment,
                        Refund, Transfer, FundEntry, Invite (kotlinx.serialization)
               ops/     sealed Operation + reducer: apply(state, op, actor): Result<State>
               domain/  SettlementCalculator, status derivation, transfer suggestions
               auth/    Permissions.check(actor, op): Allowed | Denied(reason)
             commonTest: golden + property tests (Σ payout invariant, rounding, transfers)
composeApp/  KMP Compose Multiplatform library (android, wasmJs executable), commonMain UI,
             PL/EN string resources, navigation, view models, sync engine, local store
androidApp/  Android application shell (MainActivity, manifest, signing) depending on composeApp
server/      JVM Lambda: Handler, GoogleAuth, Storage, Routes (no web framework)
```

**Libraries:** Compose Multiplatform, Navigation Compose, lifecycle-viewmodel, kotlinx.serialization, kotlinx.datetime, Ktor client, and Coil 3 (images). Server: AWS SDK for Java v2 (`s3` only, `UrlConnectionHttpClient`), nimbus-jose-jwt. There is no DI framework; wiring is done by hand. Use the latest stable versions at implementation time.

**Web target:** `wasmJs`. It needs WasmGC browsers: Chrome/Edge 119+, Firefox 120+, Safari 18.2+.

### 5.2 Sync (operation-based, offline-first)
- Every user action becomes an `Operation` with `clientId` (per install), a monotonic `seq`, an `opId`, a timestamp and the actor.
- Ops carry **entity ids** (UUIDv7, generated on the client) and **patch semantics** (only the changed fields).
- **Client:** `displayedState = pendingOps.fold(serverState, reducer)`. The UI never waits on the network.
  - Android persists to app-private files; the web persists to IndexedDB.
- **Push:** `POST /bands/{id}/ops` with the pending batch. The server does:
  1. Load `state.json` with its ETag.
  2. For each op: skip it if `seq ≤ lastSeq[clientId]` (idempotency); otherwise check permissions and apply the reducer.
  3. Conditional `PUT` with `If-Match` (retry ≤5 on a race).
  4. Append accepted ops to the log.
  5. Return the new state, version, and a list of rejected ops with reasons.
- **Rejected ops** are dropped on the client, with a snackbar: "Your change to X was rejected: <reason>".
- **Pull:** on app open, after every push, and every 30 s while in the foreground. `If-None-Match` → `304`.
- **Conflicts:** last write wins per field/entity, in server-received order. There is no other conflict resolution.

### 5.3 API
All endpoints require `Authorization: Bearer <Google ID token>`. The server verifies the signature (Google JWKS, cached), `iss`, `exp`, `email_verified`, and that `aud ∈ GOOGLE_CLIENT_IDS`. The user key is `sub`.

| Method & path | Purpose |
|---|---|
| `GET /me` | profile + band list |
| `POST /bands` | create band (caller = owner) |
| `GET /bands/{id}/state` | state + version (ETag) |
| `POST /bands/{id}/ops` | push op batch |
| `GET /bands/{id}/log?month=yyyy-MM` | history |
| `POST /bands/{id}/invites` / `DELETE /bands/{id}/invites/{code}` | create / revoke invite |
| `GET /invites/{code}` / `POST /invites/{code}/accept` | preview / accept invite |
| `POST /bands/{id}/attachments` | returns `{attachmentId, putUrl}` |
| `GET /bands/{id}/attachments/{attachmentId}` | returns a short-lived GET URL |

Band management ops (roles, treasurer, unlink, ownership) go through the same `/ops` endpoint.

### 5.4 S3 layout (`jacekgajek-art-split-data`)
```
users/{googleSub}.json                    profile + bandIds
bands/{bandId}/state.json                 full band state incl. lastSeq per clientId
bands/{bandId}/log/{yyyy-MM}/{millis}-{uuid}.json   one object per accepted push
bands/{bandId}/attachments/{uuid}.{jpg|pdf}
invites/{code}.json                       bandId, memberId, role, expiresAt, createdBy
```

### 5.5 Auth on clients
- **Android:** Credential Manager + Google ID (`GetGoogleIdOption` with `serverClientId` = web client ID) gives an ID token. The client re-requests it silently (auto-select) when it is about to expire.
- **Web:** Google Identity Services (One Tap / button) gives an ID token, refreshed via auto-select.
- ID tokens live about 1 h. On a `401` the client refreshes once and retries.

### 5.6 Lambda performance
- JVM 21 with **SnapStart** on published versions, called via the `live` alias.
- **Priming** in a CRaC `beforeCheckpoint` hook: build the S3 client, fetch JWKS, run a dummy serialization and a settlement pass.
- Lean dependencies (no Spring/Ktor server/DI); target jar ≤ 15 MB.
- `JAVA_TOOL_OPTIONS=-XX:+TieredCompilation -XX:TieredStopAtLevel=1`, arm64, 1024 MB, no VPC, one function for all routes.
- Expected cold start is ~300–600 ms, and the offline-first UI hides it. GraalVM native image is the fallback if needed.

---

## 6. Infrastructure and delivery

### 6.1 Terraform (`my-aws/terraform/art_split.tf`, already written, **applied manually by Jacek**)
- `jacekgajek-art-split-data`: private, versioned (noncurrent versions expire after 30 d), CORS for presigned GET/PUT from the web origin and `http://localhost:8080`.
- `jacekgajek-art-split-web` + CloudFront (OAC, SPA fallback to `index.html`) + ACM (us-east-1) + Route53 A/AAAA for `artsplit.jacekgajek.com`.
- Lambda `art-split-api`:
  - Created with a placeholder package. `ignore_changes` covers the code, so CI owns the code.
  - Alias `live`, also ignored, so CI moves it.
  - IAM role scoped to the data bucket; log group with 14 d retention.
  - Env: `DATA_BUCKET`, `GOOGLE_CLIENT_IDS`, `JAVA_TOOL_OPTIONS`.
- API Gateway **HTTP API** → alias `live`. It has CORS for the web origin and dev origin, and throttling (burst 50, rate 20/s).
- GitHub **OIDC provider** + role `art-split-github-deploy`. It trusts only `repo:jacekgajek/band_settlement:ref:refs/heads/main` and may only update/publish the Lambda, move the alias, write the web bucket, and invalidate CloudFront.
- **Outputs:** `art_split_deploy_role_arn`, `art_split_api_url`, `art_split_web_bucket`, `art_split_cloudfront_distribution_id`, `art_split_web_url`.

### 6.2 GitHub Actions
- **`ci.yml`** (PRs and main): `./gradlew check` (shared tests on JVM + wasmJs, server tests, Android lint/unit tests), plus builds of the Android debug APK and the wasm distribution.
- **`deploy.yml`** (push to `main`, after CI passes):
  1. `./gradlew :server:shadowJar :composeApp:wasmJsBrowserDistribution :composeApp:assembleRelease`
  2. `aws-actions/configure-aws-credentials` using OIDC (`role-to-assume: ${{ vars.AWS_DEPLOY_ROLE_ARN }}`).
  3. **API:** `update-function-code` → `wait function-updated` → `publish-version` (SnapStart snapshot) → `update-alias live`. Rollback = move the alias back.
  4. **Web:**
     - `aws s3 sync … --delete`
     - re-upload `*.wasm` with `--content-type application/wasm`
     - upload `/.well-known/assetlinks.json`
     - invalidate `/index.html`, `/.well-known/*`
  5. **Android:** a signed release APK published to a GitHub Release (tag `v<versionName>`). The web join page links to `releases/latest`.
- **`scripts/deploy.sh`:** the same steps, using a local AWS profile.
- **Android updates:** on start, the app checks the GitHub Releases API for the latest tag. If it is newer, it shows "New version available → Download". The Play Store (closed testing) may come later.

### 6.3 Secrets and variables to configure (Jacek)
Set them in Repo → Settings → Secrets and variables → Actions, or with `gh secret set` / `gh variable set`.

| Name | Kind | How to obtain |
|---|---|---|
| `AWS_DEPLOY_ROLE_ARN` | Variable | `terraform output art_split_deploy_role_arn` |
| `AWS_REGION` | Variable | `eu-central-1` |
| `API_URL` | Variable | `terraform output art_split_api_url` |
| `WEB_BUCKET` | Variable | `terraform output art_split_web_bucket` |
| `CLOUDFRONT_DISTRIBUTION_ID` | Variable | `terraform output art_split_cloudfront_distribution_id` |
| `GOOGLE_WEB_CLIENT_ID` | Variable | Google Cloud Console → APIs & Services → Credentials → OAuth client "Web application". JS origins: `https://artsplit.jacekgajek.com`, `http://localhost:8080` |
| `ANDROID_KEYSTORE_BASE64` | Secret | `keytool -genkeypair -v -keystore artsplit.jks -alias artsplit -keyalg RSA -keysize 4096 -validity 10000`, then `base64 -w0 artsplit.jks`. **Back up the .jks.** |
| `ANDROID_KEYSTORE_PASSWORD` | Secret | from keytool |
| `ANDROID_KEY_ALIAS` | Variable | `artsplit` |
| `ANDROID_KEY_PASSWORD` | Secret | from keytool |

**Google sign-in setup (one-time, free, ~15 min, no billing account needed):**
1. Open https://console.cloud.google.com, sign in with your normal Google account, and accept the terms.
2. Project picker → **New project** → name it `Art Split`.
3. **Google Auth Platform → Get started** (older UI: "OAuth consent screen"):
   - App name `Art Split`, support email = yours, Audience **External**, contact email = yours.
   - Request only the default scopes (`openid`, `email`, `profile`). These are non-sensitive, so no Google verification is needed.
   - **Audience → Publish app**, so any Google account can sign in. Otherwise every bandmate must be added as a test user.
4. **Clients → Create client → Web application:**
   - Authorized JavaScript origins: `https://artsplit.jacekgajek.com` and `http://localhost:8080`.
   - No redirect URIs.
   - Copy the client ID: it goes into the `GOOGLE_WEB_CLIENT_ID` variable and into `art_split_google_client_ids`.
5. **Clients → Create client → Android** (do this twice):
   - Package `com.jacekgajek.artsplit`.
   - First client: the SHA-1 from `keytool -list -v -keystore artsplit.jks` (release builds).
   - Second client: the SHA-1 from `keytool -list -v -keystore ~/.android/debug.keystore -storepass android` (local debug builds).
   - These clients only register the app's identity. Their IDs are not used in code.
6. In `my-aws/terraform/art_split.tf`, set `art_split_google_client_ids = ["<web client id>"]` and re-apply.

The Android app requests ID tokens with the **web client ID** as `serverClientId`, so all tokens (web and Android) have the same `aud` and the server needs only that one ID.

---

## 7. Milestones and task checklist

Workflow:
- One branch + PR per milestone (`m1-domain-core`, …). CI must be green to merge.
- Each checkbox is ticked **in the commit that completes it**.
- Commit messages reference task IDs, e.g. `feat(shared): settlement rounding [M1.3]`.
- Plan changes go into the Decision log (§8).

### M0 — Infrastructure (applied by Jacek)
- [x] M0.1 Write `my-aws/terraform/art_split.tf` (+ `archive` provider), passing `terraform fmt` and `validate`
- [x] M0.2 Jacek: `terraform init -upgrade && terraform plan && terraform apply` in `my-aws/terraform`
- [x] M0.3 Jacek: Google sign-in setup (§6.3: project, consent screen, web + 2 Android clients), put the web client ID into `art_split_google_client_ids`, re-apply
- [x] M0.4 Jacek: generate the keystore and set the GitHub secrets/variables (§6.3)

### M1 — Domain core (`:shared`)
- [x] M1.1 Gradle KMP skeleton (`shared`, `composeApp`, `androidApp`, `server`), version catalog, `ci.yml`
- [x] M1.2 Model + serialization
- [x] M1.3 SettlementCalculator: shares, rounding, refunds, fund amount, loss detection
- [x] M1.4 Balances, suggested transfers, status derivation
- [x] M1.5 Costs: outstanding amount, refund eligibility (participants only), deferral
- [x] M1.6 Operations + reducer (patch semantics, idempotency by clientId/seq)
- [x] M1.7 Permissions matrix
- [x] M1.8 Golden tests (§2.6) + invariant tests (Σ payout, transfers converge to SETTLED)

### M2 — Local-only app (Android + Web)
- [x] M2.1 App shell: navigation, tabs, PL/EN resources, theme
- [x] M2.2 Local store (Android files / web IndexedDB) + pending-op queue
- [x] M2.3 Roster (local) and event list with status, "your number", Continue card
- [x] M2.4 Event screen: participants/guests/shares, holdings, refunds, summary
- [x] M2.5 Transfers: suggestions, Settle, per-member Settle, Settle All, Hand over money
- [x] M2.6 My costs + fast add-cost form (no attachments yet)
- [x] M2.7 Settle tab; archive/delete event rules

### M3 — Backend and sync
- [x] M3.1 `:server` handler, routing, Google token verification
- [x] M3.2 S3 storage with ETag conditional writes, `/state`, `/ops`, op log
- [ ] M3.3 SnapStart priming, shadow jar, `deploy.yml` (API + web), `scripts/deploy.sh`
- [ ] M3.4 Google sign-in on Android and Web
- [ ] M3.5 Sync engine (push/pull/backoff/rejections UI)
- [ ] M3.6 Zero screen: create band, `/me`, band switcher

### M4 — Collaboration
- [ ] M4.1 Role-aware UI (tab and action visibility), "Only my events" filter
- [ ] M4.2 Invites: create/revoke/accept, `/join/{code}` web route, Android App Links + assetlinks.json
- [ ] M4.3 Admin: roster, roles, unlink, ownership transfer
- [ ] M4.4 History views (event + band)

### M5 — Extras
- [ ] M5.1 Band fund: treasurer setting, fund amount, fund transfers, manual entries, cost refunds from fund
- [ ] M5.2 Attachments: image compression, PDFs, presigned upload/download, offline queue
- [ ] M5.3 Signed release APK to GitHub Releases + in-app update check
- [ ] M5.4 Polish: empty states, error states, accessibility pass
- [ ] M5.5 Rename the band in Settings (admin+; `RenameBand` operation already exists)

---

## 8. Decision log
| Date | Decision |
|---|---|
| 2026-10-20 | Plan refined from PLAN.md through a Q&A session (band fund, costs-as-member-debt, transfers, roles, ops-based sync, Lambda API, invites, distribution, i18n, history). |
| 2026-10-20 | API exposed via **API Gateway HTTP API** instead of a Lambda Function URL: built-in throttling and a well-known permission model; cost is negligible. |
| 2026-10-20 | No spreadsheet importer; `Rozliczenia.ods` and `Rozliczenia DV.ods` are used only as golden test data (§2.6). |
| 2026-10-20 | Income sources extended with HAT, CONTRACT, SPONSORSHIP (seen in DV sheet). Refund percentages stay out despite the 50% block in the DV sheet: members enter the refundable part as the cost amount. |
| 2026-10-20 | Separate `androidApp` module: AGP 9 no longer supports `com.android.application` inside a KMP module, so `composeApp` is a KMP library and `androidApp` is the thin app shell. compileSdk/targetSdk 36. Toolchain: Kotlin 2.4.21, Compose MP 1.11.1 (1.12 needs compileSdk 37, which IntelliJ IDEA 2026.2 does not know yet), AGP 9.1.0 (newest supported by IntelliJ IDEA 2026.2; bump only together with the IDE), Gradle 9.8.2, JDK 21. |
| 2026-10-20 | M1: archived is a flag on the event, not an `EventStatus` (see §2.3). |
| 2026-10-20 | M1: "own cost locked once any part is refunded" refined to "locked once a refund of it sits in an event with recorded transfers, or it was refunded from the band fund". Costs incurred for an event are auto-refunded there on creation, so the literal rule would lock every cost immediately. |
| 2026-10-20 | M1: Settle / per-member Settle / Settle All are one op, `SettleSuggestions(eventId, partyId?)`, whose transfers are computed against the state it is applied to, so a stale client never records outdated amounts. Transfer ids are `<opId>-<n>`. |
| 2026-10-20 | M1: when the band fund and the treasurer both take part in an event's suggestions, they are paired first ("keep for band fund"); the rest is greedy largest-surplus to largest-deficit. |
| 2026-10-20 | M1: a member may record holdings only in events they take part in; leads may record them for any participant. Guests can be promoted to the roster (admin) with all their entries in that event re-pointed. |
| 2026-10-21 | PR #1 review: the band fund balance may go negative (shown in red, never blocked). A treasurer with the Member role acts for the band fund when recording/settling transfers. |
| 2026-10-21 | M2: `composeApp` commonTest runs as Android host (JVM) tests; the wasm browser test task is disabled (needs a headless browser, and the code under test is platform-independent). Navigation uses type-safe routes; the web build binds them to browser history. |
| 2026-10-21 | M2: until sign-in (M3) the device user is a fixed local identity (`local-user`) that owns the locally created band; all operations stay in the pending queue. The web build stores state in IndexedDB, Android in app-private files. The band fund tab and fund amount UI wait for M5.1. |
| 2026-10-21 | A cost shows as refunded only once the event refunding it is settled (or it was refunded from the band fund); until then it is "Refund planned". Assigning still counts immediately, so a cost cannot be refunded twice. |
| 2026-10-21 | Every rejection carries a stable `RejectionReason`; apps show its translated text (PL/EN), the English `message` stays for logs and server responses. A test fails if a reason has no text in a UI language. |
| 2026-10-21 | Unreadable saved band data never crashes the app or gets discarded silently: it is copied to `band-backup-<timestamp>` and the user can start over. No export UI; the backup is for manual recovery. Unreadable settings fall back to defaults. |
| 2026-10-21 | M3: history is one S3 object per accepted push (`log/{yyyy-MM}/{millis}-{uuid}.json`) instead of a monthly `.jsonl`: S3 has no append, and separate objects need no read-modify-write. Sequence numbers are tracked per user and device (`{sub}/{clientId}`). Non-members get `404` for a band, the same as a missing band. Band ids come from the client so offline-created bands keep their ids. |

## 9. Out of scope (v1)
Multi-currency, push notifications, real-time sync, undo, Play Store, iOS native app, spreadsheet import, refund percentages, PDF/CSV export.
