#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
GRADLE_BUNDLE="$REPO_ROOT/app/build/outputs/bundle/release/app-release.aab"
GIT_COMMON_DIR="$(
  git -C "$REPO_ROOT" rev-parse --path-format=absolute --git-common-dir 2>/dev/null || true
)"
PRIMARY_ROOT="$(cd -- "$(dirname "$GIT_COMMON_DIR")" && pwd)"
RELEASE_DIR="$PRIMARY_ROOT/release"
VERSION_NAME="$(
  sed -nE 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' \
    "$REPO_ROOT/app/build.gradle.kts"
)"
if [[ -z "$VERSION_NAME" || "$VERSION_NAME" == *$'\n'* ]]; then
  printf 'error: expected one versionName in app/build.gradle.kts\n' >&2
  exit 1
fi
BUNDLE="$RELEASE_DIR/BeautifulQuran-$VERSION_NAME.aab"
EXPECTED_SHA1="48:36:68:57:5C:44:6A:BC:D2:AF:E3:3A:63:78:D6:CC:7B:DF:64:18"
KEYSTORE="${RELEASE_KEYSTORE_FILE:-$REPO_ROOT/release.keystore}"

# Linked worktrees do not inherit ignored files such as signing keys.
if [[ -z "${RELEASE_KEYSTORE_FILE:-}" && ! -f "$KEYSTORE" ]]; then
  primary_keystore="$PRIMARY_ROOT/release.keystore"
  if [[ -n "$GIT_COMMON_DIR" && -f "$primary_keystore" ]]; then
    KEYSTORE="$primary_keystore"
  fi
fi

# shellcheck source=android_env.sh
source "$SCRIPT_DIR/android_env.sh"

require_android_java_21

if [[ ! -f "$KEYSTORE" ]]; then
  printf 'error: %s is required to build a Play Store release bundle.\n' \
    "$KEYSTORE" >&2
  printf 'Place the key there or set RELEASE_KEYSTORE_FILE and try again.\n' >&2
  exit 1
fi

export RELEASE_KEYSTORE_FILE="$KEYSTORE"

if [[ ! -f "$REPO_ROOT/data/quran.db" ]]; then
  (cd "$REPO_ROOT" && python3 tools/build_db.py)
fi

(cd "$REPO_ROOT" && ./gradlew bundleRelease)

if [[ ! -f "$GRADLE_BUNDLE" ]]; then
  printf 'error: Gradle completed without producing %s\n' "$GRADLE_BUNDLE" >&2
  exit 1
fi

actual_sha1="$(
  LC_ALL=C keytool -printcert -jarfile "$GRADLE_BUNDLE" |
    awk '/SHA1:/{print $2; exit}'
)"

if [[ "$actual_sha1" != "$EXPECTED_SHA1" ]]; then
  printf 'error: bundle signer is %s; Google Play expects %s\n' \
    "${actual_sha1:-unknown}" "$EXPECTED_SHA1" >&2
  exit 1
fi

mkdir -p "$RELEASE_DIR"
cp "$GRADLE_BUNDLE" "$BUNDLE"

printf 'Release bundle: %s\n' "$BUNDLE"
printf 'Signing SHA1:   %s\n' "$actual_sha1"
printf 'Bundle SHA256:  '
sha256sum "$BUNDLE" | awk '{print $1}'
