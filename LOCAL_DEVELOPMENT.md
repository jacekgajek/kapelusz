# Local development

The app on an emulator against an API on this machine. Nothing goes to AWS or Google, and once the dependencies
are downloaded, the host can be offline.

## What runs where

`localServer/` runs the API's own code (`server`'s `Api`: routes, permissions, sync, history, invites, attachments,
calendar). Only what reaches out of the machine is replaced:

| In production                       | Locally                                                                                     |
|-------------------------------------|---------------------------------------------------------------------------------------------|
| S3 data bucket                      | files under `.local-data/bucket/`, same keys (`bands/{id}/state.json`, …)                   |
| DynamoDB history table              | files under `.local-data/history/{band}/`                                                   |
| S3 presigned upload/download links  | `http://<host>:8787/_files/…` on the local server, created once and size-checked like S3's |
| Google sign-in                      | **development sign-in**: any name, as user `dev-<name>` (unsigned token)                    |
| Google Places and Geocoding         | made up: the typed text becomes a place in Kraków; every address gets a fixed spot nearby   |
| Firebase sync messages              | none: the app on screen checks for changes every 30 seconds, and on returning to it       |
| Event link cards (`/e/…`)           | none: no web app's page to put them in                                                      |

The data stays in `.local-data/` (ignored by git) between runs. Delete the folder to start afresh. Another
place can be set with `KAPELUSZ_LOCAL_DATA` (a relative path is taken from the repository's root), and another port
with `KAPELUSZ_LOCAL_PORT` (default 8787; the scripts' builds follow it too).

The development sign-in only works with the local server. The real API checks Google's signature and refuses these
tokens. A build with `kapelusz.devSignIn` fails on purpose if it's a release, or if the API URL is anything but
`10.0.2.2`, `localhost` or `127.0.0.1`. That keeps the sign-in from reaching Google Play or the website.

## Once, while online

Download what the offline runs will need:

```sh
./gradlew :localServer:classes :localServer:testClasses :androidApp:assembleDebug \
  :composeApp:wasmJsBrowserDevelopmentExecutableDistribution \
  -Pkapelusz.apiUrl=http://10.0.2.2:8787 -Pkapelusz.devSignIn=true
```

The last task fetches Node.js and the npm packages for the web app. Leave it out if you only use the emulator.

You also need the JDK 21 (the Gradle toolchain downloads it if it's missing) and an emulator with its system image,
from Android Studio's Device Manager.

## Run it

1. **Server**, in a terminal of its own:

   ```sh
   scripts/local-dev.sh server --offline
   ```

   It logs every request (`POST /bands/…/ops 200`) and stops with Ctrl+C.

2. **App** on the emulator. The emulator sees the host as `10.0.2.2`:

   ```sh
   export ANDROID_SERIAL=emulator-5554   # installDebug would otherwise install on every connected device
   scripts/local-dev.sh android --offline
   ```

   This is a debug build with `kapelusz.apiUrl=http://10.0.2.2:8787` and `kapelusz.devSignIn=true`. Debug builds
   allow plain HTTP to `10.0.2.2` and `localhost` (`androidApp/src/debug/`); release builds don't.

3. **Sign in**: type a name, e.g. `Alice`, and tap *Sign in as Alice*. The same name is always the same user
   (`dev-alice`). In the *Sign in again* dialog, type the signed-in user's name.

The script's `--offline` and other arguments go straight to Gradle. Without the script, set the same variables
yourself:

```sh
KAPELUSZ_API_URL=http://10.0.2.2:8787 KAPELUSZ_DEV_SIGN_IN=true ./gradlew --offline :androidApp:installDebug
```

The settings are read in this order: environment variables (`KAPELUSZ_API_URL`, `KAPELUSZ_DEV_SIGN_IN`), then this
machine's `local.properties`, then `gradle.properties`. `-P` doesn't help while `local.properties` sets the same
key, because `local.properties` comes first.

## Debugging in IntelliJ IDEA

**Server.** The shared run configuration *Local server* (in `.run/`) runs `:localServer:run --offline`. Start it with
**Debug**: IDEA attaches to the server's JVM, so breakpoints anywhere in `server/` or `localServer/` hit when the
app makes a request. The request log shows in the run tab.

**Android app.** Point the IDE's own builds at the local server in `local.properties` (git-ignored, next to
`sdk.dir`):

```properties
kapelusz.apiUrl=http://10.0.2.2:8787
kapelusz.devSignIn=true
```

Then pick the emulator in the device list and press **Debug** on the *androidApp* configuration. It installs only
on that device, and breakpoints in `composeApp/` and `shared/` hit. To debug an app that is already running, use
*Run → Attach Debugger to Android Process*. To stop on the app's very first line, run
`adb shell am set-debug-app -w com.jacekgajek.kapelusz` first.

**Remove those two lines when you're done.** Until then, every build on this machine talks to the local server,
including `./gradlew installDebug`. A release build (`bundleRelease`, `wasmJsBrowserDistribution`, i.e.
`scripts/deploy.sh`) fails while dev sign-in is on, which keeps them from reaching Google Play or the website.

Debugging the app needs IDEA's Android plugin to sync the project. If IDEA can't sync it (a `compileSdk` newer than
the plugin knows), use Android Studio for the app and IDEA for the server. Both can run at once.

**Web app.** *Web app (local server)* runs the web app against the local server on http://localhost:8080. Debug the
app's code in the browser's developer tools.

### Several users

- **On one emulator:** sign out (account menu), then sign in under another name. The app keeps each user's bands
  apart on the device, and each user's come back when they sign in again.
- **At once:** start a second emulator and install there too (`ANDROID_SERIAL=emulator-5556 scripts/local-dev.sh android`).
  Both reach the same server. The app on screen checks every 30 seconds, and right away when it comes back to the
  foreground. An app in the background doesn't check: without sync messages, nothing wakes it.
- **Joining a band:** a leader creates an invite (band members screen), the other user taps *Join a band* and types
  the 6-character code. Join links point to the real website, so open them only with the code.

### Web app

```sh
scripts/local-dev.sh web --offline     # http://localhost:8080, API on http://localhost:8787
```

The local server sends the same CORS headers that API Gateway adds. The Google sign-in script fails to load
offline, but the development sign-in doesn't need it.

### Physical phone over USB

Run `adb reverse tcp:8787 tcp:8787`, then build with `-Pkapelusz.apiUrl=http://localhost:8787`. The server only
listens on this machine's loopback, so a phone can only reach it this way.

## Switching between local and real

The app keeps its session and bands in its own storage. A build that points at another API still holds the
other API's session and bands, and they fail to sync. Before switching, sign out, or clear the app's data
(`adb shell pm clear com.jacekgajek.kapelusz`), which also drops the bands kept on the device. If a Google Play copy is installed, Android refuses the debug build
over the different signature: uninstall it first.

## Calendar feed

The subscription link points to `WEB_URL`, which defaults to `http://10.0.2.2:<port>` so it opens on the emulator.
For the web app on this machine, start the server with `WEB_URL=http://localhost:8787`.
Calendar apps that insist on HTTPS won't subscribe to it. You can still open the `.ics` link in the browser.

## Tests

`./gradlew :localServer:test` covers sign-in, sessions surviving a restart, the file store's conditional writes,
history order, made-up places, and presigned uploads and downloads. It runs as part of `./gradlew check`.
