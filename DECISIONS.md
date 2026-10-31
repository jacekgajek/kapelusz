# Decisions

The choices that shape the code and are still in force, each with its reason, so nobody re-decides them by accident.
Dated entries come from the iteration decision logs ([docs/history/](docs/history/)); the refactoring decisions from
[REFACTORING.md](REFACTORING.md) §4. New decisions go at the end of the section they belong to, dated.

## Product

1. **Shared rules.** The model, operations, reducer, permissions, settlement maths and limits live in `shared/` and run
   identically on the device and on the server. The server is the authority; the app mirrors it so a refusal shows at
   once and reads the same. (I1 §3, §5.1; I3 R1; I7 §3.1)
2. **Append-only money.** A recorded transfer is never modified; an edit reopens it with compensating suggestions; undo
   is a new operation, or a withdrawal before the change was sent. (I1 §2.3; I5 §2.1)
3. **No push notifications to users.** Re-decided twice (I2 DL; I6 §9; again 2026-10-30). Firebase carries only a
   silent "this band changed" to the other devices; the app asks for no notification permission.
4. **Thresholds, not settings.** Behaviour for large groups switches past `Crowd.THRESHOLD = 12`; a five-piece band
   must not notice any of it, and nothing new gets a setting. (I8)
5. **Artists' privacy is UI-only** today: the server sends an artist the whole band and the screens hide what they
   should not see; the same for BLIK and bank numbers (the calendar feed and the history log do filter them). A
   server-side projection is a known, unbuilt milestone.
   (I4 §1.4; I5 §8; I7 §3.2)

## Data and sync

6. **Offline-first, operation-based sync.** The shown state is the server's state with the unsent operations replayed
   on top. Operations carry a client id, a per-client sequence number, an id and a timestamp; ids are client-made
   UUIDv7. The server is idempotent by `{user}/{clientId}` and sequence; a push answers with the new state and the fate
   of each operation. Conflicts are last-write-wins per entity in the server's order; there is no other merge.
   (I1 §5.2, I1 DL M3; I7 §2.3)
7. **Never lose a change.** The stored copy is loaded before any download; an unreadable index or band is backed up
   under its own key, never dropped silently; a failed write stays dirty and is retried; the store is flushed before
   every push so a sequence number is never reused after a crash; a file is stored before its operation is queued;
   held (undoable) operations survive a kill. (I1 DL 2026-10-21; I3 R4; I5 A1, A9a, §2.1)
8. **One S3 object per band**, written with an ETag condition and at most five attempts; the history is append-only in
   DynamoDB; no relational database; event sourcing declined, because a rule change would rewrite every replay.
   (I1 §5.2; I3 §3; I5 DL 2026-10-23)
9. **A minimum app version** (`Api.MIN_APP_VERSION`, answered with 426) instead of tolerant readers. Raising it takes
   two deploys and waits for Play. Unknown enum values coerce to defaults; an unknown operation decodes as
   `UnknownOperation`, which the server refuses, the queues drop, and the history shows. (I2 §2, DL V2-M3; I8 §2)
10. **No backward compatibility for stored data.** Every user is on a current version; renames and removals are done
    without migrations, and stored data is read leniently. The refactor removed the last migrations (decision 5
    below). (I2 V2-M10.0; I5 §9.1; I8 §2)
11. **Security posture.** Google tokens verified against JWKS with issuer, expiry, verified email and audience; HMAC
    sessions of 30 days renewed while used, 90 at most; per-user revocation ("sign out on all devices"); invite codes
    outside the band state, ten lookups an hour; attachment uploads create-only with exact size and type; short-lived
    presigned downloads; ids validated; caps in bytes; the Google keys never ship in the app. (I1 §5.3; I3 R1, R6;
    I5 §9; I6 §3)
12. **Durability.** Bucket versioning for 30 days, a daily AWS Backup kept a year in a vault (the lock is optional and
    not yet applied), an API role that
    cannot delete versions, `scripts/restore-s3.sh` for a point-in-time restore
    ([DISASTER_RECOVERY.md](DISASTER_RECOVERY.md)).

## Platform and delivery

13. **One Lambda**, Java 21 on arm64, SnapStart with CRaC priming, no frameworks and no DI, a jar under 15 MB (Firebase
    and Places over plain HTTP, no SDKs), a pooled S3 client, reserved concurrency as a cost cap, API Gateway HTTP API
    with throttling (the infrastructure settings live in the owner's Terraform repository), a membership check before
    any work, one timing line per request. (I1 §5.6; I3 R6; I5; I7 §2.5)
14. **No new paid AWS services, no infrastructure change when avoidable.** Keys live in the data bucket rather than
    Parameter Store; scheduled deletions run as an hourly sweep on requests; link cards through CloudFront behaviours.
    Terraform lives in a separate repository and is applied by the owner. (I1 §6.1; I2; I7 §2.5, §9)
15. **Delivery.** One workflow: pull requests run the tests; on `main` the release builds run after the tests, then
    `scripts/deploy.sh` publishes the Lambda, uploads the web app wasm-first with hashed names, invalidates CloudFront,
    uploads the Play bundle to closed testing and smoke-checks. Actions are pinned to hashes. Android comes from Google
    Play only, since 2026-10-27. The deploy job is paused since 2026-10-31, for the refactoring; it resumes by
    uncommenting it in `ci.yml`. (I1 §6.2; I3 R5, R11; I5 E1; I7 §1)
16. **Local development mirrors production** through `localServer/`: the same `Api`, files instead of S3 and DynamoDB,
    local presigned links, a development sign-in that only works against loopback hosts, fake Places and Geocoding, no
    Firebase. A release build with the development sign-in on fails on purpose.
    ([LOCAL_DEVELOPMENT.md](LOCAL_DEVELOPMENT.md))
17. **Wiring by hand**: `AppRuntime` and `Api` build their objects in dependency order; no DI framework. The toolchain
    stays at what the IDE supports. (I1 §5.1, DL 2026-10-20; I6 DL 2026-10-26)

## The refactoring (2026-10-31)

Taken before the whole-app restructuring; the plan and its progress are in [REFACTORING.md](REFACTORING.md).

| # | Decision | Choice |
|---|---|---|
| 1 | Order and scope | The recommended order (Phase F, 0, 1.1–1.6, 2.1–2.4, 3.1–3.4, 2.5–2.6, 3.5–3.7, 4.2–4.3, 5, the rest); server work after the app; no features or bug fixes meanwhile, those go to [BACKLOG.md](BACKLOG.md). |
| 2 | Presenter mechanism | `androidx.lifecycle.ViewModel`, one per screen, scoped to its navigation entry. |
| 3 | Where `Capabilities` lives | `shared/rules/`, so app, server and tests ask the same object. |
| 4 | `BandView` | `shared/view/`. |
| 5 | Legacy migrations | Deleted; every user is on 1.33 or newer. |
| 6 | Formatting and static analysis | ktlint, detekt with a baseline that only shrinks, `.editorconfig`, Dependabot. |
| 7 | Typed ids | Value classes, JSON-identical (pending, step 1.8). |
| 8 | Documentation | `SPEC.md`, `ARCHITECTURE.md`, `DECISIONS.md`, `BACKLOG.md`; the iteration files under `docs/history/`. |
| 9 | Resource key renaming | Yes, gradually, with the dead-key test (step 6.2). |
| 10 | Compose UI smoke tests | Yes; they run in the browser, the one target with a composition (the app has no JVM target). |
| 11 | Version bumps | A single patch bump per refactoring PR; refined the same day to one per PR that ships the app, none for server-only ones. |
| 12 | Noticed-not-fixed items | All to `BACKLOG.md`, revisited after the refactor. |

## Process

18. **Work process.** A branch per piece of work from `main`, implement, an independent review, fix, push, a pull
    request, merge on green CI; never stacked pull requests. (I5; I7; REFACTORING.md)
