# Split Art

Settles a band's money after gigs: who collected what, who paid which costs, what goes to the band fund, and who
hands over how much to whom. Shared by the whole band and synced through a small server; works offline.

- Web: https://splitart.jacekgajek.com
- Android: download `split-art.apk` from the website; the app updates itself.

## Project

- `shared/` — the data model, operations, permissions and settlement rules, used by the apps and the server alike.
- `composeApp/` — the app (Compose Multiplatform) for Android and the web (Wasm).
- `androidApp/` — the Android application shell.
- `server/` — the API (AWS Lambda, Java 21, SnapStart); data in S3.
- `scripts/deploy.sh` — deploys API, website and APK (run by CI after a merge to `main`).
- `design/icon/` — sources of the app icon.

## Documents

- [ITERATION_1.md](ITERATION_1.md) — the first planning iteration, as built.
- [ITERATION_2.md](ITERATION_2.md) — the second iteration: plan, milestones and decision log (app 1.0).
- [ITERATION_3.md](ITERATION_3.md) — ideas for later.

The app was called Art Split until 1.0. The S3 buckets (`jacekgajek-art-split-*`) and the Lambda (`art-split-api`)
keep the old name: they hold the bands' data and run the API.
