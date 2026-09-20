#!/usr/bin/env bash
# Prepares signed release candidates from one verified Linux CI artifact; never publishes.
# Usage: ./scripts/prepare-release-from-ci-artifact.sh <run-id> <output-dir> <keystore> <ks-pass-file> <alias> <expected-cert-sha256>
set -euo pipefail

[ "$#" -eq 6 ] || {
  echo "usage: $0 <run-id> <output-dir> <keystore> <ks-pass-file> <alias> <expected-cert-sha256>" >&2
  exit 2
}

RUN_ID=$1
OUTPUT_DIR=$2
KEYSTORE=$3
PASSWORD_FILE=$4
KEY_ALIAS=$5
EXPECTED_SIGNER=$(printf '%s' "$6" | tr -d ':[:space:]' | tr '[:upper:]' '[:lower:]')
REPO=trevornk/ramblr
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

fail() { echo "ERROR: $*" >&2; exit 1; }
need() { command -v "$1" >/dev/null || fail "required command unavailable: $1"; }
need gh
need python3
APKSIGCOPIER=${APKSIGCOPIER:-$(command -v apksigcopier || true)}
if [ -z "$APKSIGCOPIER" ]; then
  APKSIGCOPIER="$(python3 -m site --user-base)/bin/apksigcopier"
fi
[ -x "$APKSIGCOPIER" ] || fail "apksigcopier is unavailable; install it or set APKSIGCOPIER"

[ -z "$(git status --porcelain)" ] || fail "working tree is dirty"
[ -r "$KEYSTORE" ] || fail "keystore is unreadable"
[ -r "$PASSWORD_FILE" ] || fail "keystore password file is unreadable"
[ ! -e "$OUTPUT_DIR" ] || fail "output directory already exists: $OUTPUT_DIR"

SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
APKSIGNER=$(find "$SDK/build-tools" -maxdepth 2 -name apksigner -type f 2>/dev/null | sort -V | tail -1)
ZIPALIGN=$(find "$SDK/build-tools" -maxdepth 2 -name zipalign -type f 2>/dev/null | sort -V | tail -1)
[ -x "$APKSIGNER" ] || fail "apksigner is unavailable"
[ -x "$ZIPALIGN" ] || fail "zipalign is unavailable"

HEAD_SHA=$(git rev-parse HEAD)
RUN_JSON=$(gh run view "$RUN_ID" --repo "$REPO" --json conclusion,headSha,name,url)
RUN_SHA=$(printf '%s' "$RUN_JSON" | python3 -c 'import json,sys; print(json.load(sys.stdin)["headSha"])')
RUN_NAME=$(printf '%s' "$RUN_JSON" | python3 -c 'import json,sys; print(json.load(sys.stdin)["name"])')
RUN_URL=$(printf '%s' "$RUN_JSON" | python3 -c 'import json,sys; print(json.load(sys.stdin)["url"])')
[ "$RUN_SHA" = "$HEAD_SHA" ] || fail "CI source SHA $RUN_SHA does not equal current $HEAD_SHA"
[ "$RUN_NAME" = "Reproducible release build" ] || fail "unexpected CI workflow: $RUN_NAME"
printf '%s' "$RUN_JSON" | python3 -c 'import json,sys; assert json.load(sys.stdin)["conclusion"] == "success"' \
  || fail "CI run is not successful"

TMP=$(mktemp -d -t ramblr-ci-artifact-XXXXXX)
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$OUTPUT_DIR"
gh run download "$RUN_ID" --repo "$REPO" --name app-release-unsigned --dir "$TMP"

PROVENANCE=$(find "$TMP" -name r8-source-commit.txt -type f -print -quit)
TEST_SUMMARY=$(find "$TMP" -name r8-unit-test-summary.txt -type f -print -quit)
[ -n "$PROVENANCE" ] && [ -n "$TEST_SUMMARY" ] || fail "CI artifact is missing provenance or per-flavor test summary"
[ "$(tr -d '[:space:]' < "$PROVENANCE")" = "$HEAD_SHA" ] || fail "artifact provenance does not equal current source SHA"
grep -Eq '^github tests=[0-9]+ failures=0 errors=0 ' "$TEST_SUMMARY" || fail "github unit-test gate missing or failed"
grep -Eq '^storefront tests=[0-9]+ failures=0 errors=0 ' "$TEST_SUMMARY" || fail "storefront unit-test gate missing or failed"

GITHUB_APK=$(find "$TMP" -name 'Ramblr-*-github-release.apk' -type f -print -quit)
STOREFRONT_APK=$(find "$TMP" -name 'Ramblr-*-storefront-release.apk' -type f -print -quit)
GITHUB_MAPPING=$(find "$TMP" -path '*mapping/githubRelease/mapping.txt' -type f -print -quit)
STOREFRONT_MAPPING=$(find "$TMP" -path '*mapping/storefrontRelease/mapping.txt' -type f -print -quit)
[ -n "$GITHUB_APK" ] && [ -n "$STOREFRONT_APK" ] && [ -n "$GITHUB_MAPPING" ] && [ -n "$STOREFRONT_MAPPING" ] \
  || fail "CI artifact is missing one of the two APKs or mappings"
python3 tools/verify_r8_release.py --source \
  --storefront-apk "$STOREFRONT_APK" --github-apk "$GITHUB_APK" \
  --storefront-mapping "$STOREFRONT_MAPPING" --github-mapping "$GITHUB_MAPPING"

VERSION_CODE=$(grep -E '^\s*versionCode = ' app/build.gradle.kts | head -1 | grep -oE '[0-9]+')
VERSION_NAME=$(grep -E '^\s*versionName = ' app/build.gradle.kts | head -1 | cut -d'"' -f2)
[ -n "$VERSION_CODE" ] && [ -n "$VERSION_NAME" ] || fail "could not read version from app/build.gradle.kts"
for apk in "$GITHUB_APK" "$STOREFRONT_APK"; do
  basename "$apk" | grep -q "Ramblr-${VERSION_NAME}-" || fail "APK versionName mismatch: $apk"
done

signer() {
  "$APKSIGNER" verify --print-certs "$1" 2>/dev/null | awk -F': ' '/certificate SHA-256 digest/{print $2; exit}' \
    | tr -d ':[:space:]' | tr '[:upper:]' '[:lower:]'
}
for apk in "$GITHUB_APK" "$STOREFRONT_APK"; do
  "$APKSIGNER" verify --verbose "$apk" >/dev/null || fail "CI APK signature is invalid: $apk"
  INPUT_SIGNER=$(signer "$apk")
  [ -n "$INPUT_SIGNER" ] || fail "could not read CI APK signer: $apk"
  [ "$INPUT_SIGNER" != "$EXPECTED_SIGNER" ] || fail "CI APK is already release-signed; refusing to re-sign: $apk"
done

for flavor in github storefront; do
  input_var=${flavor^^}_APK
  input=${!input_var}
  output="$OUTPUT_DIR/Ramblr-${VERSION_NAME}-${flavor}-release.apk"
  "$APKSIGNER" sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" --ks-pass "file:$PASSWORD_FILE" \
    --key-pass "file:$PASSWORD_FILE" --alignment-preserved true --out "$output" "$input"
  "$APKSIGNER" verify --verbose --print-certs "$output" >/dev/null || fail "signed candidate verification failed: $output"
  [ "$(signer "$output")" = "$EXPECTED_SIGNER" ] || fail "candidate signer mismatch: $output"
  "$ZIPALIGN" -c -p 4 "$output" >/dev/null || fail "candidate lost ZIP alignment: $output"
  "$APKSIGCOPIER" compare "$output" "$input" || fail "candidate differs from CI APK outside its signature: $output"
done

{
  printf 'source_sha=%s\nci_run=%s\nci_url=%s\nversion_name=%s\nversion_code=%s\n' \
    "$HEAD_SHA" "$RUN_ID" "$RUN_URL" "$VERSION_NAME" "$VERSION_CODE"
  cat "$TEST_SUMMARY"
  (cd "$OUTPUT_DIR" && shasum -a 256 *.apk)
  for apk in "$OUTPUT_DIR"/*.apk; do
    printf 'signer %s %s\n' "$(signer "$apk")" "$(basename "$apk")"
  done
} > "$OUTPUT_DIR/verification.txt"

echo "Prepared signed candidates in $OUTPUT_DIR"
