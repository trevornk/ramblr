#!/usr/bin/env bash
# Cuts a signed Ramblr release and publishes it to GitHub Releases.
#
# WHY THIS SCRIPT EXISTS
#
# v1.0.24 and v1.0.25 were both published with release bodies copied verbatim from
# fastlane/metadata/android/en-US/changelogs/<versionCode>.txt. Those changelog files are
# user-facing prose for F-Droid and deliberately contain no versionCode line -- but the
# in-app self-update checker REQUIRES one:
#
#   SelfUpdateResolver.parseVersionCodeFromReleaseBody()  ->  Regex "(?i)version\s*code[:\s]+(-?\d+)"
#
# With no match, SelfUpdateResolver.evaluate() returns CheckFailed("release body has no
# parseable versionCode line") and EVERY existing user silently stops being offered updates.
# It fails closed and quietly: nothing crashes, nothing logs to the user, the update just
# never appears. Both releases shipped that way and nobody noticed for weeks.
#
# So: never hand-publish a release. Use this script, which builds the body from the changelog
# AND appends the machine-readable versionCode line, then verifies the published body parses
# with the same regex the app uses.
#
# USAGE
#   ./scripts/cut-release.sh            # uses versionCode/versionName from app/build.gradle.kts
#   DRY_RUN=1 ./scripts/cut-release.sh  # build + verify locally, publish nothing
#
# PREREQUISITES
#   - keystore.properties present and pointing at the real release keystore (gitignored;
#     without it the release variant falls back to debug signing and existing users CANNOT
#     install the result -- signature mismatch)
#   - clean working tree on main, pushed, CI green
#   - fastlane/metadata/android/en-US/changelogs/<versionCode>.txt written

set -euo pipefail

cd "$(dirname "$0")/.."

DRY_RUN="${DRY_RUN:-0}"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export JAVA_HOME

fail() { echo "ERROR: $*" >&2; exit 1; }

# --- Read the single source of truth -----------------------------------------------------
VERSION_CODE=$(grep -E '^\s*versionCode = ' app/build.gradle.kts | head -1 | grep -oE '[0-9]+')
VERSION_NAME=$(grep -E '^\s*versionName = ' app/build.gradle.kts | head -1 | cut -d'"' -f2)
[ -n "$VERSION_CODE" ] || fail "could not read versionCode from app/build.gradle.kts"
[ -n "$VERSION_NAME" ] || fail "could not read versionName from app/build.gradle.kts"
TAG="v${VERSION_NAME}"

echo "==> Releasing ${TAG} (versionCode ${VERSION_CODE})"

# --- Preflight ---------------------------------------------------------------------------
[ -f keystore.properties ] || fail "keystore.properties missing -- release would be debug-signed and UNINSTALLABLE over the existing app"

CHANGELOG="fastlane/metadata/android/en-US/changelogs/${VERSION_CODE}.txt"
[ -f "$CHANGELOG" ] || fail "missing ${CHANGELOG} (F-Droid reads this, and it seeds the release body)"

if git rev-parse "$TAG" >/dev/null 2>&1; then
  fail "tag ${TAG} already exists -- bump versionCode/versionName first"
fi

if [ -n "$(git status --porcelain)" ]; then
  fail "working tree is dirty -- commit or stash before cutting a release"
fi

PUBLISHED_VC=$(gh release view --json body --jq .body 2>/dev/null \
  | grep -ioE 'version ?code[: ]+[0-9]+' | grep -oE '[0-9]+' | head -1 || true)
if [ -n "$PUBLISHED_VC" ] && [ "$VERSION_CODE" -le "$PUBLISHED_VC" ]; then
  fail "versionCode ${VERSION_CODE} is not greater than the currently published ${PUBLISHED_VC} -- self-update compares versionCodes, so this release would be invisible to existing users"
fi

# --- Build -------------------------------------------------------------------------------
echo "==> Running unit tests"
./gradlew testGithubDebugUnitTest

echo "==> Assembling signed release APKs (both flavors)"
./gradlew assembleGithubRelease assembleStorefrontRelease

GITHUB_APK="app/build/outputs/apk/github/release/Ramblr-${VERSION_NAME}-github-release.apk"
STOREFRONT_APK="app/build/outputs/apk/storefront/release/Ramblr-${VERSION_NAME}-storefront-release.apk"
[ -f "$GITHUB_APK" ] || fail "expected APK not found: ${GITHUB_APK}"
[ -f "$STOREFRONT_APK" ] || fail "expected APK not found: ${STOREFRONT_APK}"

# The self-update checker only accepts an asset named exactly Ramblr-X.Y.Z-github-release.apk
# (SelfUpdateResolver.GITHUB_RELEASE_APK_NAME). Verify rather than assume.
basename "$GITHUB_APK" | grep -qE '^Ramblr-[0-9]+\.[0-9]+\.[0-9]+-github-release\.apk$' \
  || fail "APK filename won't match SelfUpdateResolver.GITHUB_RELEASE_APK_NAME"

# Confirm the APK is release-signed, not debug-signed. A debug-signed release cannot be
# installed over an existing release-signed install. apksigner lives in the versioned SDK
# build-tools dirs and is not on PATH by default, so look it up rather than skipping the check.
APKSIGNER="$(command -v apksigner || true)"
if [ -z "$APKSIGNER" ]; then
  APKSIGNER="$(find "${ANDROID_HOME:-$HOME/Library/Android/sdk}/build-tools" -maxdepth 2 -name apksigner -type f 2>/dev/null | sort -V | tail -1 || true)"
fi
[ -n "$APKSIGNER" ] && [ -x "$APKSIGNER" ] \
  || fail "apksigner not found -- cannot verify the APK is release-signed. Install Android SDK build-tools or set ANDROID_HOME."

if "$APKSIGNER" verify --print-certs "$GITHUB_APK" 2>/dev/null | grep -qi 'CN=Android Debug'; then
  fail "APK is DEBUG-signed -- existing users could not install it (signature mismatch)"
fi

# Signing-identity continuity: Android refuses to install an update signed by a different key,
# so a rotated/lost keystore must be caught here rather than by users. Compare this build's
# signer against the currently published release's.
NEW_SIGNER=$("$APKSIGNER" verify --print-certs "$GITHUB_APK" 2>/dev/null \
  | grep -iE 'certificate SHA-256 digest' | head -1 | awk '{print $NF}')
[ -n "$NEW_SIGNER" ] || fail "could not read this build's signing certificate digest"

PREV_URL=$(gh release view --json assets \
  --jq '.assets[]|select(.name|test("^Ramblr-[0-9.]+-github-release\\.apk$")).url' 2>/dev/null | head -1 || true)
if [ -n "$PREV_URL" ]; then
  PREV_APK=$(mktemp -t prev-release-XXXXXX.apk)
  if curl -sfL -H "Accept: application/octet-stream" -o "$PREV_APK" "$PREV_URL"; then
    PREV_SIGNER=$("$APKSIGNER" verify --print-certs "$PREV_APK" 2>/dev/null \
      | grep -iE 'certificate SHA-256 digest' | head -1 | awk '{print $NF}')
    if [ -n "$PREV_SIGNER" ] && [ "$PREV_SIGNER" != "$NEW_SIGNER" ]; then
      rm -f "$PREV_APK"
      fail "signing key CHANGED vs the published release (${PREV_SIGNER} -> ${NEW_SIGNER}) -- existing users could not install this update"
    fi
    echo "==> Signing identity matches the published release"
  else
    echo "WARNING: could not download previous release APK; skipped signer continuity check" >&2
  fi
  rm -f "$PREV_APK"
fi
echo "==> Signature check passed (release-signed)"

# --- Compose the release body (the part that broke twice) --------------------------------
BODY_FILE=$(mktemp)
trap 'rm -f "$BODY_FILE"' EXIT
cat "$CHANGELOG" > "$BODY_FILE"
printf '\nversionCode: %s\n' "$VERSION_CODE" >> "$BODY_FILE"

# Verify with the SAME regex the app uses, before publishing.
PARSED=$(grep -ioE 'version ?code[: ]+[0-9]+' "$BODY_FILE" | grep -oE '[0-9]+' | head -1 || true)
[ "$PARSED" = "$VERSION_CODE" ] \
  || fail "composed release body does not parse to versionCode ${VERSION_CODE} (got '${PARSED:-none}')"

if [ "$DRY_RUN" = "1" ]; then
  echo "==> DRY_RUN=1, stopping before tag/publish. Composed body:"
  sed 's/^/    /' "$BODY_FILE"
  exit 0
fi

# --- Tag and publish ---------------------------------------------------------------------
echo "==> Tagging ${TAG}"
git tag -a "$TAG" -m "Ramblr ${VERSION_NAME}"
git push origin "$TAG"

echo "==> Creating GitHub release"
gh release create "$TAG" "$GITHUB_APK" "$STOREFRONT_APK" \
  --title "Ramblr ${VERSION_NAME}" \
  --notes-file "$BODY_FILE"

# --- Verify what actually landed ---------------------------------------------------------
echo "==> Verifying published release"
LIVE_VC=$(gh release view "$TAG" --json body --jq .body \
  | grep -ioE 'version ?code[: ]+[0-9]+' | grep -oE '[0-9]+' | head -1 || true)
[ "$LIVE_VC" = "$VERSION_CODE" ] \
  || fail "PUBLISHED body does not parse (got '${LIVE_VC:-none}') -- self-update is broken; fix with: gh release edit ${TAG} --notes-file <file>"

LATEST_TAG=$(gh api repos/trevornk/ramblr/releases/latest --jq .tag_name)
[ "$LATEST_TAG" = "$TAG" ] \
  || echo "WARNING: /releases/latest still reports ${LATEST_TAG}, not ${TAG}" >&2

gh release view "$TAG" --json assets --jq '.assets[].name' \
  | grep -qE "^Ramblr-${VERSION_NAME}-github-release\.apk$" \
  || fail "published assets do not include the github-flavor APK the updater looks for"

echo "==> Released ${TAG} (versionCode ${VERSION_CODE}); self-update body verified."
