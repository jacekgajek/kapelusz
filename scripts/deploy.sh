#!/usr/bin/env bash
# Deploys the API (Lambda) and the web app (S3 + CloudFront). Used by .github/workflows/deploy.yml and locally:
#   AWS_PROFILE=... WEB_BUCKET=jacekgajek-art-split-web CLOUDFRONT_DISTRIBUTION_ID=... scripts/deploy.sh
# Set SKIP_BUILD=1 to deploy already built artifacts.
set -euo pipefail
cd "$(dirname "$0")/.."

: "${WEB_BUCKET:?WEB_BUCKET is required}"
: "${CLOUDFRONT_DISTRIBUTION_ID:?CLOUDFRONT_DISTRIBUTION_ID is required}"
FUNCTION_NAME="${FUNCTION_NAME:-art-split-api}"
JAR=server/build/libs/server-all.jar
DIST=composeApp/build/dist/wasmJs/productionExecutable

if [[ "${SKIP_BUILD:-0}" != 1 ]]; then
  ./gradlew --no-configuration-cache :server:shadowJar :composeApp:wasmJsBrowserDistribution
fi

echo "== API: uploading $JAR"
aws lambda update-function-code --function-name "$FUNCTION_NAME" --zip-file "fileb://$JAR" >/dev/null
aws lambda wait function-updated --function-name "$FUNCTION_NAME"
# Publishing takes the SnapStart snapshot; the alias then switches traffic. Roll back by pointing it at an older version.
VERSION=$(aws lambda publish-version --function-name "$FUNCTION_NAME" --query Version --output text)
aws lambda wait published-version-active --function-name "$FUNCTION_NAME" --qualifier "$VERSION"
aws lambda update-alias --function-name "$FUNCTION_NAME" --name live --function-version "$VERSION" >/dev/null
echo "   alias live -> version $VERSION"

echo "== Web: syncing $DIST to s3://$WEB_BUCKET"
# Wasm files have content hashes in their names: cache forever. Everything else must be revalidated.
aws s3 sync "$DIST" "s3://$WEB_BUCKET" --delete --exclude "*.wasm" --cache-control "no-cache"
aws s3 sync "$DIST" "s3://$WEB_BUCKET" --delete --exclude "*" --include "*.wasm" \
  --content-type application/wasm --cache-control "public, max-age=31536000, immutable"
aws cloudfront create-invalidation --distribution-id "$CLOUDFRONT_DISTRIBUTION_ID" --paths "/*" >/dev/null
echo "== Done"
