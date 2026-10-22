# Kapelusz — Iteration 3 (ideas)

Possible improvements after 1.0. Nothing here is planned yet.

## Planned features

Chosen on 2026-10-22 for the next iteration; details to be settled (grilling) before building.

### Share a settlement as text
- On the event: **Share settlement**, a plain-text summary that goes to WhatsApp, Messenger or wherever the band
  talks about money, e.g. "Club gig, 12 Sep: Jacek gets 250 zł; Bob pays Jacek 100 zł; Carol pays the band fund 50 zł".
- Uses the system share sheet (Android) or the clipboard / Web Share (web), in the app's language.
- To decide: which lines (only who pays whom, or also income, costs and the fund), and whether settled transfers are
  marked as done.

### Reminder about open debts
- A gentle notification on the phone: "You owe Bob 120 zł from Club gig (2 weeks ago)", built on the existing reminder
  scheduling (local, no push service).
- To decide: when it fires (days after the gig, repeats), whether it also reminds the one who is owed, a switch in
  Settings → Notifications, and the band fund's holder.

### Web app installable on iPhones (PWA)
- A web app manifest (name per language, the hat icon, theme colour) and the iOS home-screen tags, so "Add to Home
  Screen" gives an app icon that opens in its own window, for bandmates with iPhones (there is no iOS app).
- To decide: offline start (a service worker caching the app) or online only, and whether the website suggests
  installing it on iPhones like it suggests the APK on Android.

## Hardening and code quality (from the full review of 2026-10-22)

The full review (six parallel reviews: domain logic, server and security, Android, UI, sync, code quality) is
summarised with priorities in the session; P1 and P2 are proposed as a hardening milestone. Larger refactorings for
later: split the server's `Api.kt` into handlers with one ETag-retry helper; one error and cancellation helper on the
client; split `SettingsScreen.kt` and `App.kt`; a shared lenient `HistoryEntry` decoder; CI building the production
web app, pinned actions and a post-deploy smoke test.

## History performance

Measured in CloudWatch on 2026-10-22 (Lambda: 1024 MB, arm64, Java 21, SnapStart):

| | Before (build 17) | After parallel reads (build 20) |
|---|---|---|
| Typical request (median) | 72 ms | 50 ms |
| 90% of requests under | 129 ms | 97 ms |
| History requests | 2.9–4.2 s | 1.4–2.8 s |
| Cold starts | ≈270–600 ms, rare | ≈260–280 ms, rare |

The history log is one S3 file per accepted push, and a history page reads up to 100 of them. Reading them in
parallel (build 20) helped less than expected: Java's built-in HTTP client keeps at most 5 connections for reuse, so
most parallel reads open a new TLS connection, and the handshakes compete for the Lambda's fraction of a CPU.

Options, cheapest first:
1. **Pooled HTTP client for S3** (e.g. the SDK's Apache client) that keeps connections open between requests, and
   apply the page's size budget from the sizes the listing already returns, so a page is fetched at once. Expected:
   about 200–400 ms per history request on a warm Lambda. Cost: one server library (≈1 MB).
2. **History log in DynamoDB** (band state stays in S3): one query per page, about 50–100 ms regardless of size.
   Needs a table in Terraform, a migration of the existing log, and splitting entries above DynamoDB's 400 KB item
   limit (large offline batches with their recorded changes). On-demand pricing; at this scale within the free tier.
3. **More Lambda memory** (2048 MB): more CPU for the handshakes at about twice the cost per millisecond. Treats the
   symptom.

Not recommended: a relational database (e.g. Postgres on RDS): a fixed monthly cost even when idle, and connection
handling from Lambda is awkward.
