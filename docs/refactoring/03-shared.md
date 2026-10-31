# Review: `shared` module (Kapelusz / Pass the Hat) — code organisation around the reducer

Scope: `shared/src/commonMain/kotlin/com/jacekgajek/kapelusz/**` (≈4 100 lines in 29 files) and `shared/src/commonTest/**` (≈3 900 lines, 29 files, 225 tests). Constraint honoured throughout: **no change to the serialized shape** of operations, `Band` state, `BandChange` (history) or API bodies. Every proposal below is a move/split/wrap; no `@Serializable` class gains, loses or renames a field, and every polymorphic leaf already carries an explicit `@SerialName`, so packages can move freely.

All paths are under `shared/src/commonMain/kotlin/com/jacekgajek/kapelusz/` unless stated.

---

## 1. Inventory

### 1.1 Packages and files

| Package | File | Lines | Holds |
|---|---|---|---|
| `model` | `Band.kt` | 157 | `Role`, `Member`, `Band` (persisted state incl. sync bookkeeping `lastSeq`, `recentRejections`), `BandDeletion`, `RecordedRejection`, `effectiveAgendaTemplate`, `withPicture`. **Imports `ops.Rejection`** (model → ops). |
| | `Event.kt` | 175 | `Event`, `FundCut` (+`ofProfit`, `isOn`), `Guest`, `Participant`, `SharePercent`, `AgendaKind/Time/TemplateItem`, `IncomeSource`, `Holding`, `Refund`, `Transfer`. Also computed getters `Event.income/fund/awaited`. |
| | `Cost.kt` | 89 | `AttachmentOwner` (sealed, SerialNames `cost/holding/event`), `Band.attachmentsOf/allAttachments`, `Cost`, `CostCategory`, `Attachment(+Kind)`, `FundEntry`. |
| | `Money.kt` | 45 | `Money` value class, `parse`, `sum`, `sumOfMoney`. |
| | `Json.kt` | 20 | `KapeluszJson` (ignoreUnknownKeys, coerceInputValues, encodeDefaults=false, `UnknownOperation` fallback). **Imports `ops.Operation`** (model → ops again). |
| `ops` | `Operation.kt` | 391 | `Change<T>`, `OpEnvelope` (transport), `Operation` sealed interface + **57 leaf operations**, each `@SerialName`. |
| | `Reducer.kt` | 854 | `OpContext`, `object Reducer` – one `apply` with a single `when` over all 57 ops (lines 55-278) + 36 private helpers. |
| | `Undo.kt` | 263 | Serialized `BandSettings`, `BandPart`, `BandChange` (stored in history entries!), `isUndoable`, `internal object Undoing` (the undo reducer + `problems()` integrity check), diff/restore helpers. |
| | `Engine.kt` | 79 | `ApplyResult`, `BatchResult`, `object Engine.apply/applyBatch` = Permissions → Reducer → Limits, idempotent seq handling, remembered rejections. |
| | `Rejection.kt` | 85 | Serialized `RejectionCode`, `RejectionReason` (55 values), `Rejection`; plus reducer-internal `RejectedException`, `reject/invalidIf/conflictIf/orNotFound`. |
| | `Limits.kt` | 184 | Size/length/count limits checked **after** the reducer by diffing `before`/`after` (`violation`). |
| `auth` | `Permissions.kt` | 224 | `Actor`, `Permissions.check/isAllowed`, `requiredRole` `when` over ops (29 branches), own-ness helpers. 70 of 224 lines are imports (one per op). |
| `domain` | `SettlementCalculator.kt` | 298 | `EventStatus`, `SettlementProblem`, `PartyLine`, `SuggestedTransfer`, `Settlement`, `settle()` (86 lines), `partyOrder`, `splitByWeight`, `suggestTransfers`, `mergePairs`. |
| | `SettleTogether.kt` | 173 | Pair rules (`canPair/mayInvite/isTaken/inviterOf`), state repair (`tidy`, `keepSettled`), queries (`partners`, `openEventsTogether`, `keptByPartner`), `split` (54 lines). |
| | `CostLedger.kt` | 91 | `CostStatus`, `CostLedger` (refunded/outstanding/paid/…/isLockedForOwner), `CostStatuses` (per-band cache of settled events). |
| | `BandFund.kt` | 62 | Fund totals, `canDisable`, `isHandedOver`, `canApplyDefault`, `stillDefault`. |
| | `MemberShares.kt` | 25 | Default share rules. |
| | `Artists.kt` | 33 | `isArtist`, `holdsMoney`, `ownTransfers`. |
| | `Viewers.kt` | 17 | `mayBecome`. |
| | `MemberDeletion.kt` | 28 | `possible`. |
| | `Lineup.kt` | 102 | Line-up picker: candidates, `locked`, **UI draft state** (`Draft.toggled/ticking`), `startFrom`, `change` → builds a `ChangeLineup` op (domain → ops). |
| | `Payees.kt` | 39 | Where to pay (BLIK/account) for a transfer. |
| | `Names.kt` | 47 | Collation + accent-folding search (`Names.order`, `matches`, `sortedByName`). |
| | `BankAccount.kt` / `BlikPhone.kt` | 40 / 60 | Normalise/display/copy; `BlikPhone.pays/onSide` (settlement-side helpers living in a phone-number file). |
| | `Places.kt` | 184 | Google-Maps URL parsing/encoding; **imports `api.PlaceSuggestion`** (domain → api). |
| | `PlayedPlaces.kt` / `EventLinks.kt` | 49 / 26 | Map grouping; `/e/<id>` links + `canOpen` visibility rule. |
| `api` | `ApiModels.kt` | 257 | HTTP bodies, `ApiErrors` constants, `HistoryEntry` (carries `BandChange`). |

Package dependency graph is not layered: `model → ops` (Band.kt:3, Json.kt:3-4), `ops → domain` (Reducer, Undo), `domain → ops` (Lineup.kt:10), `domain → api` (Places.kt:3), `auth → domain/ops`.

### 1.2 Public API surface actually used by app and server

Entry points (grep of `composeApp/` and `server/`):

* **`Engine.apply(band, actor, op, opId, timestamp): ApplyResult`** — app `data/BandRepository.kt:208` (dispatch), `:422` (replay pending); server `BandHandlers.kt:153,177`, `InviteHandlers.kt:91`, `Handler.kt:175`; ~15 test call sites.
* **`Engine.applyBatch(band, actor, envelopes, check?)`** — server `BandHandlers.kt:89,96` (second pass with a size check); app `SyncEngineTest`.
* **`Permissions.isAllowed`** — app `ui/model/Can.kt:44` (probe ops with fake ids), `CostScreen.kt:158-279`.
* **`Limits.violation`** — server `BandHandlers.kt:44` (abused with `draft.copy(name="", members=emptyList())` as a fake "before"), app reads `Limits.MAX_*` constants in 5 editors.
* **`isUndoable`, `BandChange.isEmpty/restoresAttachments`** — app `HistoryUndo.kt:32`; server builds `Undo` from history.
* **`BandChange.between`** — only inside `Engine.applyBatch` (server history) and tests.
* **Domain objects** (app usage count by symbol): `SettlementCalculator.settle` ×7 call sites, `.partyOrder` ×7, `CostLedger.outstanding` ×8, `SettleTogether.inviterOf` ×7, `Names.matches` ×6, `PlayedPlaces.*` ×12, `SettleTogether.keptByPartner` ×4, `Artists.isArtist` ×4, `Payees.*` ×4, `Lineup.*` ×6, `BandFund.*` ×6, `BlikPhone/BankAccount.*` ×10, `Viewers.mayBecome`, `MemberDeletion.possible`, `MemberShares.stillDefault`, `EventLinks.*` ×4. Server uses `Artists.holdsMoney`, `Viewers.mayBecome` (InviteHandlers:43-44), `Places.*`, `PlayedPlaces.*`, `EventLinks.url`, `SettlementCalculator.settle` (warm-up only).
* **Model extensions**: `effectiveAgendaTemplate` ×6, `allAttachments` ×2, `withPicture` (server).

Everything in `domain` is `public`; nothing in the module is `internal` except `Undoing`, `mergePairs` and the `reject*` helpers. The effective API is therefore "everything".

### 1.3 How the reducer is organised

* **One `when` over all 57 operations** in `Reducer.apply` (Reducer.kt:55-278), 241 lines, 57 branches. 26 branches are inline bodies (3-20 lines each, e.g. `ReceiveAwaited` :194-212, `AddAttachment` :255-267); 31 delegate to private helpers grouped by comment banners (`---- roster ----` :298, `---- events ----` :440, `---- refunds, fund, transfers ----` :545, `---- costs ----` :671, `---- helpers ----` :805).
* Pre-step: archived-event guard (:52-54). Post-steps: fund-needs-treasurer invariant (:280-283), `SettleTogether.tidy` (:285), `updatedAt` stamp for ops with `eventId` (:286-289).
* Validation is **inline, imperative**: 149 `invalidIf/conflictIf/orNotFound` calls in the file. Maximum nesting 7 levels (28-space indent).
* State updates go through three inline extension helpers `Band.updateEvent/updateMember/updateCost` (:836-852) that rebuild the whole list each time (`List.map`).
* Functions over 50 lines: `Reducer.apply` (241), `Undoing.apply` (57, Undo.kt:107-163), `Limits.events` (60), `Permissions.requiredRole` (60), `SettlementCalculator.settle` (86), `SettleTogether.split` (54). 40-50 lines: `updateCost` (40, Reducer.kt:692-731).
* `SettlementCalculator.settle` is invoked **from inside the reducer** in 5 places (:138, :608, :616, :634, :650) and from 7 domain helpers (`Artists`, `Viewers`, `MemberShares`, `BandFund`, `SettleTogether` ×2, `CostStatuses`). `settleSuggestions` settles the same event up to 3× per suggestion (:608, :616, and again inside `validateArtistsPaid` :650); `recordTogether` settles twice (:634, :650).

### 1.4 How permissions are checked

* `Permissions.check` (Permissions.kt:88-101): actor → active member; special-cases `LinkUser`, `ScheduleBandDeletion`, "only for yourself" ops; then `me.role.atLeast(requiredRole(op))`. `requiredRole` is a 29-branch `when` with "own" exceptions resolved by 6 helpers (`ownsParty`, `ownHolding`, `fundEntry`, `ownAttachments`, `ownCost`, `partnerIn`). Undo = `max(required(original), reverseRole(original))`.
* Runs on the server (enforce) and in the app (`Can` probes with placeholder ids `"probe"` and `LocalDate(2000,1,1)` — composeApp `ui/model/Can.kt:92-95`).
* **Duplicates / second sources of truth**:
  * Reducer's own role test `isLead(band, ctx)` (Reducer.kt:799) drives *behaviour* (`includeByLead` :762, refund auto-raise :716) — role logic outside `Permissions`, and `ctx.memberId == null` (system) silently counts as lead.
  * "Artist cannot be invited while holding money / viewer while playing" is checked in the reducer (`linkUser` :394-395), again in server `InviteHandlers.kt:43-44`, again in app `RosterScreen.kt:562-567`.
  * "Archived event is read-only": Reducer.kt:52 + app `Can.open()` (Can.kt:90) + 10 hand-written `!event.archived` checks in screens.
  * "Artist sees only their own events" is coded 6 times: `EventLinks.canOpen` (shared), `PlayedPlaces.visible` (shared), app `Can.kt:99 Band.isArtist`, `ShareTargets.kt:43`, `EventScreen.kt:174`, `EventSearch.kt:31`.

### 1.5 How undo is modelled

* `Undo(undoneOpId, original, change: BandChange, entry)` is itself an operation. `BandChange = before/after BandPart`, where `BandPart` is a sparse snapshot (entities by id, `null` = absent; order lists when something was removed/moved; `BandSettings` for the 6 band-level scalars). Computed by `BandChange.between(before, after)` in `Engine.applyBatch` and stored in `HistoryEntry.changes` on the server.
* `Undoing.apply` (Undo.kt:107-163): depth ≤ 4; refuses non-undoable kinds, empty changes, attachment restores; "changed since" = every `after` entity still equals current (members compared via `plain()` ignoring photo/pair/payment details; events compared **exactly, including `updatedAt`**); restores `before`; then `problems(restored) − problems(band)` must be empty (dangling references, duplicate names, over-refunds, fund-without-treasurer, pair consistency).
* Post-reducer `tidy` is skipped for `Undo` (Reducer.kt:285); the `problems()` check covers it instead.
* Server substitutes the recorded `original/change` so clients cannot forge them (BandHandlers.kt:82).

### 1.6 Rejection / validation

Three layers with different styles:
1. **Permissions** returns `Rejection?` (FORBIDDEN).
2. **Reducer** throws `RejectedException` via `invalidIf/conflictIf/orNotFound` (INVALID/CONFLICT/NOT_FOUND + a `RejectionReason`), caught in `Engine.apply`.
3. **Limits.violation(before, after)** returns `Rejection?` after the fact by diffing lists (`!==` identity shortcuts, Limits.kt:52-59).
4. Server adds a 4th: the `check` lambda in `applyBatch` (band size).
`RejectionReason` (55 values) mixes user-facing keys with app-only ones (`STORAGE_FAILED`).

### 1.7 Derived views: where computed, cached or not

* `Settlement` is **never cached in shared**. `CostStatuses(band)` is the only memo (settled-event ids, lazy) but `CostLedger.paid/unpaid/status` each build a *fresh* `CostStatuses` → settle every event per call (CostLedger.kt:30-35).
* `Settlement.parties` is computed in `settle()` (:187) and again lazily on the result (:90).
* App side: `EventsScreen.kt:161` and the `CostStatuses` users wrap in `remember(band)`; but **`EventScreen.kt:176`** (`SettlementCalculator.settle` on every recomposition of the event screen), **`SettleScreen.kt:86`** (settles *all* active events on every recomposition, plus `Can(band, …)` rebuilt each time), `ParticipantsSection.kt:301` (preview, intentional), `Summaries.kt:61/109`, `PeriodSummary.kt:148` (suspend with `yield()` as a workaround for cost), `EventSearch.kt:29` are uncached.
* Lookups are linear: `Band.member/event/cost`, `Event.participant/guest/refundOf` (Band.kt:110-120, Event.kt:42-44). `settle()` does `band.member(id)` per participant and `band.cost(id)` per refund; `CostStatuses` × 2 000 events × 5 000 costs is the documented reason for the `yield()`s.

### 1.8 Size / complexity numbers

| Metric | Value |
|---|---|
| Operations | 57 leaf types (+ `Undo`, `UnknownOperation` among them) |
| `Reducer.apply` | 241 lines, 57 `when` branches, max nesting 7 |
| Validation calls in Reducer | 149 (`invalidIf` 103, `conflictIf` 24, `orNotFound` 22 approx.) |
| `Permissions.requiredRole` | 60 lines, 29 branches; file is 31 % imports |
| `RejectionReason` values | 55 |
| Functions > 50 lines | 6 (listed in 1.3) |
| Places that call `SettlementCalculator.settle` | 12 in shared main, 7 in app |
| Objects passing the `(band, settlement, from, to, amount)` tuple | `SettleTogether.split`, `Payees.forTransfer` (6 params), `BlikPhone.onSide`, `Artists.ownTransfers` |
| Tests | 29 files / 225 tests, flat package, all through `Scenario` → `Engine` |

---

## 2. Problems

### 2.1 Files that mix concerns
* **`Reducer.kt`**: dispatch + 57 behaviours + validation vocabulary (`validShare`, `validName`, `validAgenda`, `account`) + state-update plumbing (`updateEvent/…`) + a hidden permission rule (`isLead`). The two `BlikPhone.normalize + invalidIf` blocks (:71-72, :78-79) and `account()` (:407-411) are the same pattern thrice.
* **`Undo.kt`**: the persisted history format (`BandChange/BandPart/BandSettings`) lives beside the undo algorithm and the band integrity checker `problems()` (which is a general invariant checker, useful far beyond undo).
* **`Rejection.kt`**: wire types + reducer-only throw helpers.
* **`Operation.kt`**: `Change<T>` and `OpEnvelope` (sync transport) + domain commands.
* **`model/Band.kt`**: domain state + sync bookkeeping (`lastSeq`, `recentRejections`) + `RecordedRejection` → model depends on ops. (Cannot change the fields — but the *type* `RecordedRejection/Rejection` could live in `model` or a neutral `wire` package so the dependency points the right way.)
* **`domain/`** is three different things in one package: money calculators (Settlement, CostLedger, BandFund, SettleTogether.split, MemberShares, Payees), **rule predicates** used by reducer *and* UI (Artists, Viewers, MemberDeletion, SettleTogether.canPair/mayInvite, BandFund.canDisable), and **text/format utilities** with no domain state (Names, BlikPhone, BankAccount, Places, EventLinks, PlayedPlaces). `Lineup.Draft` is UI editor state; `Places` imports `api`.
* `BlikPhone.pays/onSide` (BlikPhone.kt:52-59) is a settlement-side query stored in a phone-formatting file.

### 2.2 The reducer as a monolith
One `when` over 57 ops means every feature change touches an 854-line file; reviewers cannot see a feature's full rule set in one place (e.g. costs: `addCost/updateCost/deleteCost/validateCostEvent/includeByLead` at :673-769 *plus* `SetRefund` at :547, *plus* `RemoveParticipant`'s refund rule :538, *plus* the fund-entry cost rules :570-574/598-602, *plus* `Permissions.ownCost`, *plus* `CostLedger.isLockedForOwner`). There is no per-aggregate boundary: an event op can also mutate costs (`deleteEvent` :477), a cost op mutates events (`updateCost` :718, `deleteCost` :740), a band op rewrites all events (`DisableBandFund` :102).

### 2.3 Duplicated rules
* Attachment id regex: Reducer.kt:264 and Limits.kt:38 (identical literal, two places).
* Fund "on/off" = `treasurerMemberId == null`: 8 places in the reducer (:77, :89, :201, :224, :333, :565, :665, :280), `Payees.fund`, `SettlementCalculator:147`, `Can.kt:76`, `SettleScreen.kt:92`, `TransfersSection.kt:187/266`, `BandFundScreen.kt:155`, `EventScreen.kt:414`, `EventsScreen.kt:308`.
* `BAND_FUND` transfer-direction sum: `BandFund.transferred` and `transferredFromEvents` (BandFund.kt:47-61) are the same `when` twice, and `PeriodSummary.kt:188-192` has it a third time.
* Accent folding: `Names.fold/ACCENTED` (shared) vs `EventSearch.foldForSearch/FOLDS` (app) — two different tables that already disagree (`ß→ss`, `ā`, `ø` only in the app table; `ő` only in shared). A search by name and a sort by name treat "Müller/Mueller" differently from each other.
* Invite-role checks (`holdsMoney`/`mayBecome`): reducer `linkUser`, server `InviteHandlers:43-44`, app `RosterScreen:562-567`.
* Artist visibility: 6 copies (1.4).
* Pure band logic in app `ui/model`: `Summaries.kt` (`myNumber`, `fundKeptBy`, `unsettledEvent`, `upcomingEvent` — 80 lines of settlement reasoning), `PeriodSummary.kt` (238 lines: the whole yearly summary incl. fund flows), `EventSearch.searchableSummaries`, `ShareTargets` (which permission probes allow attachments). All of these take `Band` and return data; they belong in shared where they can be tested with `Scenario` and reused by the server (calendar feed, future e-mail summaries).

### 2.4 Naming
* `ops` vs `domain` vs `model`: an "operation" is data, but `ops` also holds the engine; `domain` holds URL parsers. `Engine` vs `Reducer` vs `Undoing` vs `Limits` are four stages of one pipeline with four naming styles.
* `BandFund` (object) / `BAND_FUND` (party id) / `FundCut` / `FundEntry` / `fundDefault` / `treasurerMemberId` (= "fund enabled") / `Event.fund` (computed) / `fundAmount`. A reader cannot tell which "fund" is the ledger, the cut, the party or the switch.
* `Holding` (model) is "collected"/"collected entry" in comments and UI, "awaited" when `partyId == ORGANISER`.
* `SettleTogether` (object), `settlesWith`, `invitedToSettle`, `settledTogether` (event field), `Settlement.partners`, `PartyLine.partnerId` — five spellings of one concept.
* `Rejection.code` (HTTP-ish) vs `Rejection.reason` (user key): the names do not say which is which.

### 2.5 Helpers with many parameters / tuple-passing
`SettleTogether.split(band, settlement, from, to, amount)`, `Payees.forTransfer(band, settlement, from, to, amount, payer)`, `BlikPhone.onSide(band, settlement, me, side)`, `Artists.ownTransfers(band, settlement, memberId)`, `periodSummary(band, me, from, to, onlyEventsOf, timeZone)`, `Lineup.startFrom(band, event, source, newId)`, `Band.create(6)`. The recurring `(band, event, settlement)` triple is an object that does not exist yet (`EventView`).

### 2.6 Implicit invariants not enforced by types
* `Id = String` for members, guests, events, costs, holdings, transfers, fund entries, **and** the sentinels `BAND_FUND`/`ORGANISER`. `Transfer.from/to`, `Holding.partyId`, `PartyLine.partyId` accept anything; `Limits.id()` has to forbid the sentinels by string compare. (A `@JvmInline value class PartyId(val value: String)` serializes identically to a string, so this *is* compatible with the stored JSON.)
* `Participant.fixedShare xor percent`, `Event.fundAmount == 0 while fundPercent != null`, `Member.settlesWith` symmetric, "fund on ⇔ treasurer set", `Change(null)` vs `null` — all documented, none typed.
* `OpContext.memberId == null` ⇒ system ⇒ lead (Reducer.kt:799).
* `Band.lastSeq` keys are `"$userId/$clientId"` on the server but bare `clientId` locally (BandHandlers:82) — convention only.
* `Event.updatedAt` must be stamped by every mutation; the generic stamp covers ops with `eventId`, the rest stamp by hand (:102, :339, :373, :721, :744, :768) and `SettleTogether.keepSettled` (SettleTogether.kt:73-76) forgets.

### 2.7 No memoisation layer
See 1.7. The cost shows in: `yield()` workarounds in `PeriodSummary`/`EventSearch`; `SettleScreen` and `EventScreen` recomputing on every recomposition; `CostLedger.status` being a trap (settles the whole band per call); reducer re-settling the same event 2-3×. The root cause is that `Band` is the only object handed around and it has no place to hang caches.

### 2.8 Test organisation
* Good: feature-named files, `Scenario` builds through the real `Engine`, `GoldenTest` (13 spec cases from the spreadsheets), `InvariantTest` (300 random events), `UndoTest`/`UndoPermissionsTest`.
* Weak: `Scenario.apply` defaults to `Actor.System` (Scenario.kt:306) — permissions are bypassed and `ctx.memberId == null`, so `isLead`-dependent behaviour (`includeByLead`, refund auto-raise) is exercised as "lead" by default and `addedBy/recordedBy` are mostly null in tests.
* **No wire-format fixtures.** `SerializationTest` round-trips two objects built in code; nothing pins the stored JSON of a full `Band`, of each of the 57 operations, or of a `HistoryEntry` with `BandChange`. Given the owner's constraint ("the shape in S3 must not change"), that is the missing safety net — `GoldenTest` guards the arithmetic, not the format.
* Flat package of 29 files; `SettleTogetherTest` (432 lines) covers reducer + permissions + undo + `split` in one class; `ReducerTest` is actually "costs & refunds".
* App tests (`CanTest`, `ShareTargetsTest`, `PeriodSummaryTest`, `FundKeptTest`) re-implement a mini `Scenario` because the logic under test lives in the app.

---

## 3. Target design (as if built from scratch, serialization identical)

### 3.1 Package layout

```
com.jacekgajek.kapelusz
├─ model/        Band, Member, Event, Cost, Money, Json           (unchanged types; add typed ids only if wanted)
│   └─ ids.kt    value class MemberId/EventId/PartyId …          (optional; JSON unchanged)
├─ ops/          Operation + 57 leaves, split by aggregate:
│   ├─ Operation.kt      sealed interface Operation; Change<T>; sealed sub-interfaces (below)
│   ├─ BandOps.kt        RenameBand, SetTreasurer, DisableBandFund, SetFund*, SetAgendaTemplate, Schedule/CancelBandDeletion, TransferOwnership
│   ├─ RosterOps.kt      AddMember … UnlinkUser, SetMemberShare, SetBlikPhone, SetBankAccount
│   ├─ PairOps.kt        InviteToSettleTogether … StopSettlingTogether
│   ├─ EventOps.kt       CreateEvent, UpdateEvent, SetEventArchived, SetEventAgenda, DeleteEvent
│   ├─ LineupOps.kt      AddParticipant, AddGuest, RenameGuest, SetWeight, SetFixedShare, RemoveParticipant, ChangeLineup
│   ├─ MoneyOps.kt       AddHolding, UpdateHolding, ReceiveAwaited, RemoveHolding, RecordTransfer, SettleSuggestions, RemoveTransfer, SetRefund, SetFundAmount
│   ├─ CostOps.kt        AddCost, UpdateCost, DeleteCost
│   ├─ FundOps.kt        AddFundEntry, UpdateFundEntry, RemoveFundEntry
│   ├─ AttachmentOps.kt  Add/Remove/RenameAttachment
│   └─ UndoOp.kt         Undo, UnknownOperation
├─ history/      BandChange, BandPart, BandSettings (serialized), OpEnvelope, Rejection types   ← "wire" package, no logic
├─ engine/       Engine (unchanged signature), Reducer (dispatch only), Undoing, Limits, Rejecting (throw helpers, internal)
│   └─ reduce/   BandReducer, RosterReducer, PairReducer, EventReducer, LineupReducer, HoldingReducer, TransferReducer,
│                CostReducer, FundReducer, AttachmentReducer, Validation.kt, BandUpdates.kt (updateEvent/…)
├─ rules/        Permissions (+ Actor), Capabilities (today's app `Can`), Artists, Viewers, MemberDeletion,
│                PairRules (canPair/mayInvite/isTaken/inviterOf), FundRules (canDisable/isHandedOver/canApplyDefault), Visibility (artist sees…)
├─ settlement/   SettlementCalculator, Settlement, PartyLine, SettleTogether.split/partners/keptByPartner/tidy/keepSettled,
│                CostLedger, BandFund totals, MemberShares, Payees
├─ view/         BandView, EventView, CostStatuses, Summaries (myNumber, unsettled, upcoming), PeriodSummary, EventSearch index
├─ text/         Names, BlikPhone, BankAccount, Places, EventLinks, PlayedPlaces
└─ api/          unchanged
```

Rule of thumb for the dependency direction: `model ← ops ← history ← engine`, `model ← settlement ← rules ← engine`, `view` depends on everything below it and nothing above; `text` depends only on `model`/`api`. `Rejection`, `RecordedRejection` move to `history` (or `model`) so `model` no longer imports `ops`; `KapeluszJson` moves to `history` (it needs `Operation`).

### 3.2 Slicing the reducer with a stable dispatch

Add *intermediate* sealed interfaces; leaves keep their `@SerialName`, so the JSON discriminator is untouched (kotlinx collects sealed subclasses recursively):

```kotlin
@Serializable sealed interface Operation { val eventId: Id? get() = null }
sealed interface BandOp : Operation
sealed interface RosterOp : Operation
sealed interface PairOp : Operation
sealed interface EventOp : Operation          { override val eventId: Id }
sealed interface LineupOp : EventOp
sealed interface HoldingOp : EventOp
sealed interface TransferOp : EventOp
sealed interface CostOp : Operation
sealed interface FundOp : Operation
sealed interface AttachmentOp : Operation     { val owner: AttachmentOwner }
```

Dispatcher (≈60 lines, replaces the 241-line `when`):

```kotlin
internal object Reducer {
    fun apply(band: Band, op: Operation, ctx: OpContext): Band {
        Guards.before(band, op)                                   // archived-event guard
        val result = when (op) {
            is BandOp       -> BandReducer.apply(band, op, ctx)
            is RosterOp     -> RosterReducer.apply(band, op, ctx)
            is PairOp       -> PairReducer.apply(band, op, ctx)
            is LineupOp     -> LineupReducer.apply(band, op, ctx)
            is HoldingOp    -> HoldingReducer.apply(band, op, ctx)
            is TransferOp   -> TransferReducer.apply(band, op, ctx)
            is EventOp      -> EventReducer.apply(band, op, ctx)  // after its sub-kinds
            is CostOp       -> CostReducer.apply(band, op, ctx)
            is FundOp       -> FundReducer.apply(band, op, ctx)
            is AttachmentOp -> AttachmentReducer.apply(band, op, ctx)
            is Undo         -> Undoing.apply(band, op)
            is UnknownOperation -> reject(...)
        }
        return Guards.after(band, result, op, ctx)                // fund/treasurer invariant, tidy, updatedAt stamp
    }
}
```

Each feature reducer is an `internal object` with one exhaustive `when` over *its* sealed group (compiler still forces coverage when an op is added), its private validations, and nothing else:

```kotlin
internal object CostReducer {
    fun apply(band: Band, op: CostOp, ctx: OpContext): Band = when (op) {
        is AddCost -> add(band, op, ctx)
        is UpdateCost -> update(band, op, ctx)
        is DeleteCost -> delete(band, op, ctx)
    }
    // addCost/updateCost/deleteCost/validateCostEvent/includeByLead move here verbatim
}
```

Shared validation vocabulary in `engine/reduce/Validation.kt`: `validName`, `blankToNull`, `validShare/Share`, `validatePositive`, `validateWeight`, `validFundCut`, `validAgenda`, `phone(input)`, `account(input)`, and the single `ID` regex shared with `Limits`. `BandUpdates.kt` holds `updateEvent/updateMember/updateCost`.

`OpContext` gains a per-op view so a reducer never settles the same event twice:

```kotlin
data class OpContext(val opId: Id, val timestamp: Long, val memberId: Id?, val isSystem: Boolean = memberId == null) {
    fun view(band: Band): BandView = BandView(band)   // Engine passes one; reducers that mutate mid-op make a new one
}
```

### 3.3 Where permissions go

Keep `Permissions.check` as the single enforcement point, but:
* index the matrix by the same groups (`when (op) { is PairOp -> …; is CostOp -> ownCost(…) }`), so the 70 imports go away and a new op in a group gets the group's default;
* move `isLead` out of the reducer into `OpContext.actsAsLead(band)` (defined next to `Permissions`), so every role decision reads from one place;
* move the app's `Can` into `rules/Capabilities.kt` (`class Capabilities(view: BandView, userId: String)` with the same probe methods) so the server and shared tests can use it and the probe ids become a private detail of shared;
* publish the *predicates* the UI needs as plain functions in `rules/` (`FundRules.isOn(band)`, `Visibility.canSee(band, me, event)`), and make the reducer, server and app call those instead of re-deriving `treasurerMemberId == null` / `role == ARTIST`.

### 3.4 Derived views: compute once per state change

```kotlin
/** One band state, with everything derived from it computed at most once. Immutable; replace it when the band changes. */
class BandView(val band: Band) {
    private val membersById = band.members.associateBy { it.id }
    private val costsById   = band.costs.associateBy { it.id }
    private val eventsById  = band.events.associateBy { it.id }
    private val settlements = HashMap<Id, Settlement>()           // single-threaded use; or lazy per event

    fun member(id: Id): Member? = membersById[id]
    fun cost(id: Id): Cost? = costsById[id]
    fun event(id: Id): EventView? = eventsById[id]?.let { EventView(this, it) }
    fun settlement(event: Event): Settlement = settlements.getOrPut(event.id) { SettlementCalculator.settle(band, event) }

    val settledEventIds: Set<Id> by lazy { band.events.filter { settlement(it).status == SETTLED }.mapTo(HashSet()) { it.id } }
    val costStatuses: CostStatuses by lazy { CostStatuses(this) }
    val fund: FundTotals by lazy { BandFund.totals(this) }        // balance, notHandedOver, canDisable
    fun forUser(userId: String): MemberView                       // me, role, visible events, Capabilities
}

class EventView(val band: BandView, val event: Event) {
    val settlement: Settlement get() = band.settlement(event)
    val partners: Map<Id, Id> get() = settlement.partners
    fun myNumber(me: Member?): MyNumber
    fun payees(from: Id, to: Id, amount: Money, payer: Id? = null): List<Payee>
    fun split(from: Id, to: Id, amount: Money): List<SuggestedTransfer>
}
```

* Domain helpers that today settle events take `BandView` instead of `Band` (`Artists.holdsMoney(view, id)`, `Viewers.mayBecome(view, id)`, `MemberShares.canApply(view, …)`, `BandFund.canApplyDefault(view, …)`, `SettleTogether.openEventsTogether(view, …)`, `CostStatuses(view)`); `CostLedger.paid/unpaid/status(band, …)` are deleted in favour of `view.costStatuses`.
* App: `BandState.Ready(band, userId)` becomes `BandState.Ready(view: BandView, me: MemberView)`; `BandRepository` builds it once in `dispatch`/`replay`. Screens call `view.settlement(event)`; `remember(band)` keys become `remember(view)`. `Summaries`, `PeriodSummary`, `EventSearch.searchableSummaries` move to `view/` and take a `BandView` (the `yield()`s can stay as an optional `suspend` wrapper, but with memoised settlements the summary becomes O(events) instead of O(events²)).
* Server: unchanged call sites; `InviteHandlers` uses `BandView(band)` for its two predicates.

### 3.5 Public API for app and server

```kotlin
// engine
object Engine {
    fun apply(band: Band, actor: Actor, op: Operation, opId: Id, timestamp: Long): ApplyResult      // unchanged
    fun applyBatch(band: Band, actor: Actor, envelopes: List<OpEnvelope>, check: ((Band, Band) -> Rejection?)? = null): BatchResult
    fun validateNew(band: Band): Rejection?          // replaces server's Limits.violation(draft.copy(...), draft) hack
}
// rules
object Permissions { fun check(band, actor, op): Rejection?; fun isAllowed(...) }
class Capabilities(view: BandView, userId: String) { … }     // ex-app `Can`
// view
class BandView(band) / class EventView / class MemberView
// history
fun isUndoable(op): Boolean; class BandChange { companion fun between(before, after) }
// text
Names, BlikPhone, BankAccount, Places, EventLinks, PlayedPlaces   (as today)
```
Everything in `engine/reduce/` and `Rejecting` becomes `internal`. `SettlementCalculator.settle` stays public (tests, previews) but is documented as "uncached; prefer `BandView`".

---

## 4. Migration plan (each step shippable, golden tests as the net)

| # | Step | Files touched | Risk | Safety net |
|---|---|---|---|---|
| 0 | **Pin the wire format.** Add `WireFormatTest`: (a) a committed JSON of a full `Band` built by a `Scenario` that uses every entity kind, decoded then re-encoded byte-equal; (b) one `OpEnvelope` JSON per operation type (57, with `Change(null)` cases), same check; (c) one `HistoryEntry` with a `BandChange`; (d) an op-log replay golden: a list of envelopes → expected final `Band` JSON (the GoldenTest scenarios serialised). Store fixtures as Kotlin string constants (KMP wasm test resources are awkward). | `shared/src/commonTest/…/WireFormatTest.kt`, `Fixtures.kt` | none | this *is* the net for everything below |
| 1 | **Split `Operation.kt`** into per-aggregate files and add the intermediate sealed interfaces (3.2). No reducer change. | `ops/Operation.kt` → 10 files | low (kotlinx sealed-hierarchy registration; step 0(b) proves discriminators unchanged) | step 0 |
| 2 | **Split `Reducer.kt`** mechanically: move each helper + its inline `when` bodies into the feature reducer objects; `Reducer.apply` becomes the dispatcher; extract `Validation.kt`, `BandUpdates.kt`, `Guards.kt`. Pure cut-and-paste, behaviour identical. | `ops/Reducer.kt` → `engine/reduce/*.kt` (≈11 files), `Limits.kt` (share `ID`) | low | all 225 tests + step 0(d) |
| 3 | **Move `isLead` into `OpContext`/rules**; make `Rejecting` helpers internal; move `Rejection`, `RecordedRejection`, `OpEnvelope`, `BandChange/BandPart/BandSettings`, `KapeluszJson` into `history/`; fix the `model → ops` import. Server/app imports change only. | `model/Band.kt`, `model/Json.kt`, `ops/Rejection.kt`, `ops/Undo.kt`, `ops/Operation.kt`, `api/ApiModels.kt`; import lines in composeApp/server | low | compile + step 0 |
| 4 | **Permissions by group** + move app `Can` to shared `rules/Capabilities` (with `CanTest`); add `Engine.validateNew`; make server `InviteHandlers:43-44` and app `RosterScreen:562-567` call one `rules` predicate (`Invites.refusal(view, member, role): RejectionReason?`). | `auth/Permissions.kt`, new `rules/*.kt`, `composeApp …/ui/model/Can.kt` (delete), `CostScreen.kt`, `RosterScreen.kt`, `server/BandHandlers.kt:44`, `InviteHandlers.kt` | low-medium (app recomposition unchanged; only call sites) | `PermissionsTest`, `UndoPermissionsTest`, `CanTest` (moved) |
| 5 | **Introduce `BandView`/`EventView`** in `view/`; switch the settle-calling domain helpers and `CostStatuses` to take it; delete `CostLedger.paid/unpaid/status`. Reducer uses `ctx.view(band)` in `settleSuggestions`/`recordTogether`/`validateArtistsPaid` (one settle per state). | new `view/BandView.kt`, `domain/{Artists,Viewers,MemberShares,BandFund,SettleTogether,CostLedger}.kt`, `engine/reduce/TransferReducer.kt` | medium (cache invalidation is by construction — a view is immutable per band instance; the only trap is reusing a view after mutating `band` inside a loop) | GoldenTest G4/G6/G12, `InvariantTest`, `SettleTogetherTest` |
| 6 | **App adopts the view**: `BandState.Ready(view)`, `remember(view)`, `EventScreen:176`, `SettleScreen:86` stop recomputing; move `Summaries.kt` pure functions, `PeriodSummary` computation, `EventSearch.searchableSummaries` + folding (merged with `Names.fold`, one table) to shared `view/`; app keeps only formatting. | `composeApp …/data/BandRepository.kt`, `ui/screens/*` (settle call sites), `ui/model/{Summaries,PeriodSummary,EventSearch,ShareTargets}.kt` → shared | medium (largest diff; purely mechanical for screens) | app tests move with the code; `PeriodSummaryTest`, `FundKeptTest`, `EventSearchTest` become shared tests |
| 7 | **Package tidy**: `domain/` → `settlement/`, `rules/`, `text/`; `ops/` keeps only operations; `Lineup.Draft` to app (optional). IDE moves, no logic. | import lines everywhere | low, noisy | compile + step 0 |
| 8 | **Typed ids** (optional, later): `value class PartyId(val value: String)` for `Transfer.from/to`, `Holding.partyId`, `PartyLine.partyId`; JSON identical (`@JvmInline` serializes as the wrapped string). | `model/Event.kt`, `settlement/*`, call sites | medium (touches many signatures) | step 0 proves the JSON; compiler finds the rest |
| 9 | **Tests**: give `Scenario.apply` an explicit actor in feature tests that depend on roles (`includeByLead`, refund auto-raise); split `SettleTogetherTest` into pair-rules / split / undo; group test files into sub-packages mirroring main. | `commonTest/**` | none | — |

Steps 0-3 are a weekend; 4-6 are the payoff (no recomputation per recomposition, one source for every rule); 7-9 are polish.

---

## 5. Noticed, not fixed

* `domain/SettleTogether.kt:73-76` — `keepSettled` appends to `event.settledTogether` without stamping `updatedAt`, unlike every other event mutation; event order/undo "changed since" won't notice this change.
* `ops/Reducer.kt:286-289` — `updatedAt` is stamped even when the op was a no-op (e.g. `SetWeight` to the same value, re-archiving an archived event); because `Undoing.apply` compares events exactly (Undo.kt:115), such a no-op later blocks undo of an earlier op on that event.
* `ops/Reducer.kt:323` — "not yourself; leave the band instead" is reported with `MEMBER_HAS_HISTORY`, a misleading reason key for the UI.
* `ops/Reducer.kt:122, :294` — "no such pair/invitation" uses code `CONFLICT` with reason `NOT_FOUND`; other missing things use `orNotFound` (code `NOT_FOUND`).
* `ops/Reducer.kt:799` — `isLead` treats `ctx.memberId == null` as lead; a *linked* user whose member was unlinked between queueing and applying also gets `memberId == null` (Engine.kt:32) — but `Permissions.check` refuses them first, so only `Actor.System` reaches it. Fragile coupling rather than a bug.
* `ops/Reducer.kt:606-626` + `:649-661` — `settleSuggestions` settles the event three times per suggestion (`:608`, `:616`, `:650`); `recordTogether` twice; correctness fine, cost O(suggestions × settle).
* `domain/SettlementCalculator.kt:90` and `:187` — `parties` is computed in `settle()` and again lazily on the returned `Settlement` (`mergePairs` twice).
* `domain/CostLedger.kt:30-35` — `paid/unpaid/status` each construct a new `CostStatuses` and settle every event; a loop over costs calling `CostLedger.status` is O(costs × events × settle). The app avoids it, `GoldenTest.g4` uses it.
* `domain/SettleTogether.kt:52-54` — `tidy` drops an invitation when the invitee has *any* other invitation pending (`count == 1` / `none {…}`), so two concurrent invitations to the same person (two phones) silently cancel both; the invite UI shows nothing.
* `domain/SettleTogether.kt:142-143` — in `split`, when *both* partners are artists the leftover is assigned to `parts[0]` (an artist) and `validateArtistsPaid` then refuses the whole transfer; a pair of two artists can therefore never settle a surplus together. (`canPair` allows two artists.)
* `domain/SettlementCalculator.kt:139,155` — `byPercent` is used when `percentSum == 100` *including* `event.fundPercent`; the fund's rounding-down to whole units (`FundCut.ofProfit`) plus the participants' rounding leftovers are then redistributed to participants only. Probably intended ("rounding goes to them"), but the fund never gets its leftover grosze.
* `ops/Limits.kt:52-59` — relies on `!==` list identity to skip checks; correct today because every reducer path rebuilds lists, but an optimisation that returns the same list (e.g. an early-return no-op) would silently skip limit checks for that op.
* `ops/Limits.kt:38` vs `ops/Reducer.kt:264` — same `[A-Za-z0-9_-]{1,64}` literal in two places; attachment ids are checked by both, with different `RejectionReason`s depending on which fires first.
* `server/…/BandHandlers.kt:44` — `Limits.violation(draft.copy(name = "", members = emptyList()), draft)` fabricates a "before" state to validate a new band; member-name length is checked but the currency regex comes *after* it (line 45), so a bad name reports before a bad currency — harmless, but the API shape invites this hack.
* `auth/Permissions.kt:137` — `AddHolding` "own" requires `partyId == me.id && participant`; an artist is refused by role, but a **viewer who is a participant** (possible for events created before the member became a viewer — `validateNotViewer` only guards new additions) is refused by role too, while `UpdateHolding` of their existing holding (`:138`, `ownHolding`) is *allowed* at `MEMBER`… also refused for a viewer. Consistent, just noting the pre-existing-participant hole in the "viewers take no part" invariant (`Viewers.mayBecome` checks only unsettled events).
* `ops/Reducer.kt:396` — `LinkUser` un-archives the member (`archived = false`) but does not re-check `validFreeName`; names are unique across archived members too, so this is safe today, noted because a future "rename on archive" would break it.
* `ops/Reducer.kt:692-731` — moving a cost to another event (`incurredForEventId` change) leaves its refund in the old event (by design per the comment at :706), so `cost.eventId` and the event that actually refunds it can disagree; `CostLedger.pendingFromEarlier` and `includeByLead` cope, but the UI has no way to show "refunded at A, assigned to B".
* `ops/Engine.kt:75` — `lastSeq` is advanced for rejected ops too (documented), but `recentRejections` keeps only the last 20 per client; a client with >20 rejected ops in one lost response gets plain `duplicates` for the older ones and never learns why they failed.
* `composeApp …/ui/screens/SettleScreen.kt:86` and `EventScreen.kt:176` — settlement recomputed on every recomposition (not in `remember`); `SettleScreen` settles *all* active events each time.
* `composeApp …/ui/model/EventSearch.kt:56-75` vs `domain/Names.kt:13-14` — two divergent accent-folding tables (see 2.3).
