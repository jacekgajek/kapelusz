#!/usr/bin/env bash
# Deploys the API (Lambda) and the web app (S3 + CloudFront). Used by .github/workflows/deploy.yml and locally:
#   AWS_PROFILE=... WEB_BUCKET=jacekgajek-art-split-web CLOUDFRONT_DISTRIBUTION_ID=... scripts/deploy.sh
# Set SKIP_BUILD=1 to deploy already built artifacts. With ANDROID_KEYSTORE_PATH (and ANDROID_KEYSTORE_PASSWORD,
# ANDROID_KEY_ALIAS, KAPELUSZ_VERSION_CODE) it also publishes the signed APK at /download/kapelusz.apk.
# KAPELUSZ_VERSION_CODE must not be lower than the live one (see below); without it the web app is a "dev" build.
set -euo pipefail
cd "$(dirname "$0")/.."

: "${WEB_BUCKET:?WEB_BUCKET is required}"
: "${CLOUDFRONT_DISTRIBUTION_ID:?CLOUDFRONT_DISTRIBUTION_ID is required}"
FUNCTION_NAME="${FUNCTION_NAME:-art-split-api}"
JAR=server/build/libs/server-all.jar
DIST=composeApp/build/dist/wasmJs/productionExecutable
APK=androidApp/build/outputs/apk/release/androidApp-release.apk

# The version code becomes the app's version (web and Android): the server refuses apps below its minimum, and phones
# only update to a higher one. So never publish a lower one than what is live, e.g. a local deploy with an old number.
# Equal is fine (a re-run of the same CI deploy). FORCE_VERSION_CODE=1 skips the check, e.g. for a deliberate rollback.
if [[ -n "${KAPELUSZ_VERSION_CODE:-}" && "${FORCE_VERSION_CODE:-0}" != 1 ]]; then
  LIVE_CODE=$(aws s3 cp "s3://$WEB_BUCKET/download/version.json" - 2>/dev/null | sed -n 's/.*"versionCode": *\([0-9]*\).*/\1/p' || true)
  if [[ -n "$LIVE_CODE" && "$KAPELUSZ_VERSION_CODE" -lt "$LIVE_CODE" ]]; then
    echo "KAPELUSZ_VERSION_CODE=$KAPELUSZ_VERSION_CODE is lower than the live version $LIVE_CODE; use a higher one (or FORCE_VERSION_CODE=1)." >&2
    exit 1
  fi
fi

TASKS=(:server:shadowJar :composeApp:wasmJsBrowserDistribution)
if [[ -n "${ANDROID_KEYSTORE_PATH:-}" ]]; then
  : "${KAPELUSZ_VERSION_CODE:?KAPELUSZ_VERSION_CODE is required to publish the APK}"
  TASKS+=(:androidApp:assembleRelease)
fi
if [[ "${SKIP_BUILD:-0}" != 1 ]]; then
  ./gradlew --no-configuration-cache "${TASKS[@]}"
fi

echo "== API: uploading $JAR"
aws lambda update-function-code --function-name "$FUNCTION_NAME" --zip-file "fileb://$JAR" >/dev/null
aws lambda wait function-updated --function-name "$FUNCTION_NAME"
# Publishing takes the SnapStart snapshot; the alias then switches traffic. Roll back by pointing it at an older version.
VERSION=$(aws lambda publish-version --function-name "$FUNCTION_NAME" --query Version --output text)
aws lambda wait published-version-active --function-name "$FUNCTION_NAME" --qualifier "$VERSION"
aws lambda update-alias --function-name "$FUNCTION_NAME" --name live --function-version "$VERSION" >/dev/null
echo "   alias live -> version $VERSION"

# Android App Links: the site vouches for the app (package + signing certificate fingerprints).
if [[ -n "${ANDROID_CERT_SHA256:-}" ]]; then
  mkdir -p "$DIST/.well-known"
  FINGERPRINTS=$(echo "$ANDROID_CERT_SHA256" | tr ',' '\n' | sed '/^$/d; s/.*/"&"/' | paste -sd, -)
  cat > "$DIST/.well-known/assetlinks.json" <<JSON
[{"relation": ["delegate_permission/common.handle_all_urls"],
  "target": {"namespace": "android_app", "package_name": "com.jacekgajek.kapelusz", "sha256_cert_fingerprints": [$FINGERPRINTS]}}]
JSON
fi

echo "== Web: syncing $DIST to s3://$WEB_BUCKET"
# Order matters: new wasm files first (content-hashed names, cached forever), then the files that reference them
# (revalidated on every load), and only then delete the old wasm files. A page loaded mid-deploy never points at a
# missing file.
WASM_ARGS=(--exclude "*" --include "*.wasm" --content-type application/wasm --cache-control "public, max-age=31536000, immutable")
aws s3 sync "$DIST" "s3://$WEB_BUCKET" "${WASM_ARGS[@]}"
# download/ is not part of the web build: it holds the published APK, which a deploy without a keystore keeps.
# .well-known/ likewise, unless this deploy writes it: without it Android stops opening invite links in the app.
KEEP_ARGS=(--exclude "download/*")
[[ -n "${ANDROID_CERT_SHA256:-}" ]] || KEEP_ARGS+=(--exclude ".well-known/*")
aws s3 sync "$DIST" "s3://$WEB_BUCKET" --delete --exclude "*.wasm" "${KEEP_ARGS[@]}" --cache-control "no-cache"
aws s3 sync "$DIST" "s3://$WEB_BUCKET" --delete "${WASM_ARGS[@]}"

if [[ -n "${ANDROID_KEYSTORE_PATH:-}" ]]; then
  VERSION_NAME=$(sed -n 's/^kapelusz.versionName=//p' gradle.properties)
  echo "== APK: publishing $VERSION_NAME ($KAPELUSZ_VERSION_CODE)"
  # The APK first: version.json announces it to the website banner and the in-app update check.
  aws s3 cp "$APK" "s3://$WEB_BUCKET/download/kapelusz.apk" --cache-control "no-cache" \
    --content-type application/vnd.android.package-archive --content-disposition 'attachment; filename="kapelusz.apk"'
  printf '{"versionCode": %d, "versionName": "%s"}\n' "$KAPELUSZ_VERSION_CODE" "$VERSION_NAME" |
    aws s3 cp - "s3://$WEB_BUCKET/download/version.json" --cache-control "no-cache" --content-type application/json
  if [[ -n "${GITHUB_ENV:-}" ]]; then echo "APK_VERSION_NAME=$VERSION_NAME" >> "$GITHUB_ENV"; fi
else
  # Not an error (web and API are deployed), but the website banner and the update check need a published APK.
  echo "== APK: not published (set ANDROID_KEYSTORE_PATH, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, KAPELUSZ_VERSION_CODE)"
fi
aws cloudfront create-invalidation --distribution-id "$CLOUDFRONT_DISTRIBUTION_ID" --paths "/*" >/dev/null

# Smoke check (ITERATION_3 R5): the API answers (401 without a sign-in) and the website serves the app. A few tries:
# CloudFront takes a moment to drop the old files.
API_URL="${KAPELUSZ_API_URL:-$(sed -n 's/^kapelusz.apiUrl=//p' gradle.properties)}"
WEB_URL="${KAPELUSZ_WEB_URL:-$(sed -n 's/^kapelusz.webUrl=//p' gradle.properties)}"
check() { # url expected-status
  for _ in 1 2 3 4 5 6; do
    [[ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 "$1")" == "$2" ]] && return 0
    sleep 10
  done
  echo "Smoke check failed: $1 did not answer $2" >&2
  return 1
}
echo "== Smoke check: $API_URL, $WEB_URL"
check "${API_URL%/}/me?appVersion=0" 401
check "${WEB_URL%/}/" 200
check "${WEB_URL%/}/composeApp.js" 200
echo "== Done"
