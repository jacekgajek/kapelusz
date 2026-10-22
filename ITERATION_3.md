# Art Split — Iteration 3 (ideas)

Possible improvements after 1.0. Nothing here is planned yet.

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
