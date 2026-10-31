#!/usr/bin/env bash
# Deploys the API (Lambda) and the web app (S3 + CloudFront). Used by the deploy job of .github/workflows/ci.yml and
# locally:
#   AWS_PROFILE=... WEB_BUCKET=jacekgajek-art-split-web CLOUDFRONT_DISTRIBUTION_ID=... scripts/deploy.sh
# Set SKIP_BUILD=1 to deploy already built artifacts. With ANDROID_UPLOAD_KEYSTORE_PATH (and
# ANDROID_UPLOAD_KEYSTORE_PASSWORD, ANDROID_UPLOAD_KEY_ALIAS, KAPELUSZ_VERSION_CODE) it also builds the Android app's
# bundle for Google Play, signed with the upload key; CI uploads it to Play, the only place the app
# comes from (no APK on the website since 2026-10-27). With KAPELUSZ_VERSION_CODE it writes /download/version.json,
# from which a web app open since before the deploy learns to reload.
# KAPELUSZ_VERSION_CODE must not be lower than the live one (see below); without it the web app is a "dev" build.
set -euo pipefail
cd "$(dirname "$0")/.."

: "${WEB_BUCKET:?WEB_BUCKET is required}"
: "${CLOUDFRONT_DISTRIBUTION_ID:?CLOUDFRONT_DISTRIBUTION_ID is required}"
FUNCTION_NAME="${FUNCTION_NAME:-art-split-api}"
JAR=server/build/libs/server-all.jar
DIST=composeApp/build/dist/wasmJs/productionExecutable

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
if [[ -n "${ANDROID_UPLOAD_KEYSTORE_PATH:-}" ]]; then
  : "${KAPELUSZ_VERSION_CODE:?KAPELUSZ_VERSION_CODE is required to build the Play bundle}"
  TASKS+=(:androidApp:bundleRelease)
fi
if [[ "${SKIP_BUILD:-0}" != 1 ]]; then
  # The build reads the KAPELUSZ_* variables (API URL, version code, keystore) while configuring: a cached
  # configuration from an earlier build would ship its values instead.
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
# download/ is not part of the web build: it holds version.json, written below.
# .well-known/ likewise, unless this deploy writes it: without it Android stops opening invite links in the app.
KEEP_ARGS=(--exclude "download/*" --exclude "privacy-policy" --exclude "privacy-policy/*")
[[ -n "${ANDROID_CERT_SHA256:-}" ]] || KEEP_ARGS+=(--exclude ".well-known/*")
# no-cache (revalidated on every load) also keeps sw.js and the manifests fresh: the service worker caches the app for
# offline starts itself (network first for these, cache first only for the hashed wasm files), so a new deploy is
# picked up on the next start.
aws s3 sync "$DIST" "s3://$WEB_BUCKET" --delete --exclude "*.wasm" "${KEEP_ARGS[@]}" --cache-control "no-cache"
aws s3 sync "$DIST" "s3://$WEB_BUCKET" --delete "${WASM_ARGS[@]}"

# The privacy policy (privacy-policy/, not part of the web build): plain pages without an extension, as CloudFront
# serves the bucket's keys as they are; anything else is the single-page app.
echo "== Privacy policy: /privacy-policy, /privacy-policy/pl"
aws s3 cp privacy-policy/index.html "s3://$WEB_BUCKET/privacy-policy" --cache-control "no-cache" --content-type "text/html; charset=utf-8"
aws s3 cp privacy-policy/pl.html "s3://$WEB_BUCKET/privacy-policy/pl" --cache-control "no-cache" --content-type "text/html; charset=utf-8"

if [[ -n "${KAPELUSZ_VERSION_CODE:-}" ]]; then
  VERSION_NAME=$(sed -n 's/^kapelusz.versionName=//p' gradle.properties)
  echo "== version.json: $VERSION_NAME ($KAPELUSZ_VERSION_CODE)"
  # A web app open since before this deploy sees a newer version here and offers to reload; also where a check
  # that versions only go up reads the live one (above).
  printf '{"versionCode": %d, "versionName": "%s"}\n' "$KAPELUSZ_VERSION_CODE" "$VERSION_NAME" |
    aws s3 cp - "s3://$WEB_BUCKET/download/version.json" --cache-control "no-cache" --content-type application/json
  if [[ -n "${GITHUB_ENV:-}" ]]; then echo "APP_VERSION_NAME=$VERSION_NAME" >> "$GITHUB_ENV"; fi
fi
aws cloudfront create-invalidation --distribution-id "$CLOUDFRONT_DISTRIBUTION_ID" --paths "/*" >/dev/null

# Smoke check: the API answers (401 without a sign-in) and the website serves the app. A few tries:
# CloudFront takes a moment to drop the old files.
API_URL="${KAPELUSZ_API_URL:-$(sed -n 's/^kapelusz.apiUrl=//p' gradle.properties)}"
WEB_URL="${KAPELUSZ_WEB_URL:-$(sed -n 's/^kapelusz.webUrl=//p' gradle.properties)}"
# CloudFront answers a missing file with index.html (200, text/html), so files are also checked for their content type.
check() { # url expected-status [content-type part]
  local answer
  for _ in 1 2 3 4 5 6; do
    answer="$(curl -s -o /dev/null -w '%{http_code} %{content_type}' --max-time 15 "$1")"
    [[ "${answer%% *}" == "$2" && "${answer#* }" == *"${3:-}"* ]] && return 0
    sleep 10
  done
  echo "Smoke check failed: $1 did not answer $2${3:+ with $3} (got: $answer)" >&2
  return 1
}
echo "== Smoke check: $API_URL, $WEB_URL"
check "${API_URL%/}/me?appVersion=0" 401
check "${WEB_URL%/}/" 200 html
check "${WEB_URL%/}/composeApp.js" 200 javascript
check "${WEB_URL%/}/sw.js" 200 javascript
check "${WEB_URL%/}/manifest.json" 200 json
# The single-page app answers missing pages with 200 too: the policy is told apart by its own text.
check_policy() { # url
  for _ in 1 2 3 4 5 6; do
    # The whole page first: grep -q would stop reading early, and pipefail would count curl's broken pipe as a failure.
    local page
    page="$(curl -s --max-time 15 "$1")" && [[ "$page" == *'id="rights"'* ]] && return 0
    sleep 10
  done
  echo "Smoke check failed: $1 is not the privacy policy" >&2
  return 1
}
check_policy "${WEB_URL%/}/privacy-policy"
check_policy "${WEB_URL%/}/privacy-policy/pl"
echo "== Done"
