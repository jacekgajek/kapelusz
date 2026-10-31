#!/usr/bin/env bash
# Runs Kapelusz on this machine, without AWS, Google or the internet (LOCAL_DEVELOPMENT.md).
#
#   scripts/local-dev.sh server        the local API on http://localhost:8787 (data in .local-data/)
#   scripts/local-dev.sh android       a debug build against it, installed on the device in ANDROID_SERIAL
#   scripts/local-dev.sh web           the web app against it, on http://localhost:8080
#
# Further arguments go to Gradle, e.g. --offline once the dependencies are downloaded.
set -euo pipefail
cd "$(dirname "$0")/.."

port="${KAPELUSZ_LOCAL_PORT:-8787}"
command="${1:-}"
shift || true

case "$command" in
  server)
    exec ./gradlew :localServer:run "$@"
    ;;
  android)
    # installDebug installs on every connected device: only on the one named.
#    if [[ -z "${ANDROID_SERIAL:-}" ]]; then
#      echo "Set ANDROID_SERIAL to the emulator to install on (adb devices lists them), e.g. emulator-5554." >&2
#      exit 1
#    fi
    KAPELUSZ_API_URL="http://10.0.2.2:$port" KAPELUSZ_DEV_SIGN_IN=true exec ./gradlew :androidApp:installDebug "$@"
    ;;
  web)
    KAPELUSZ_API_URL="http://localhost:$port" KAPELUSZ_DEV_SIGN_IN=true exec ./gradlew :composeApp:wasmJsBrowserDevelopmentRun "$@"
    ;;
  *)
    sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
    ;;
esac
