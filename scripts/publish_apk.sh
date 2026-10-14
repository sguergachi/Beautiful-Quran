#!/usr/bin/env bash
# Build the APK and put it where this worktree's APK always lives.
#
# Every worktree (= every T3 Code thread) gets one fixed directory, so anything
# that wants "the APK for this thread" — an agent, the T3 Code "Send APK"
# action, scripts/send_apk_to_phone.sh --published — looks in one place instead
# of digging through app/build/outputs or guessing a /tmp name:
#
#   $BQ_APK_HOME/<worktree-dir-name>/release.apk   (+ release.info)
#   $BQ_APK_HOME/<worktree-dir-name>/debug.apk     (+ debug.info)
#
# BQ_APK_HOME defaults to ~/.t3/artifacts/Beautiful-Quran/apks. The name is
# deliberately generic: this is a *location*, not a delivery. The phone never
# sees it — send_apk_to_phone.sh re-stages a copy named after the work + commit
# (a generic name is a send KDE Connect silently drops).
#
# The .info file records what the APK was built from, so a stale one is
# detectable instead of silently shipped:
#   sha, branch, dirty (1 = uncommitted changes at build time), built_at, bytes
#
# Usage: scripts/publish_apk.sh [--release|--debug] [--skip-build] [--print]
#   --release     publish the release APK (default: debug)
#   --skip-build  publish the APK already in app/build/outputs
#   --print       build nothing and publish nothing; print the known path for
#                 this worktree and exit 0 if an APK is there, 1 if not
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"

KIND=debug
SKIP_BUILD=0
PRINT=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --release) KIND=release ;;
    --debug) KIND=debug ;;
    --skip-build) SKIP_BUILD=1 ;;
    --print) PRINT=1 ;;
    -h|--help) sed -n '2,/^set -euo/p' "$0" | sed -e '$d' -e 's/^# \{0,1\}//'; exit 0 ;;
    *) printf 'error: unknown argument %s\n' "$1" >&2; exit 2 ;;
  esac
  shift
done

APK_HOME="${BQ_APK_HOME:-$HOME/.t3/artifacts/Beautiful-Quran/apks}"
DEST_DIR="$APK_HOME/$(basename -- "$REPO_ROOT")"
DEST="$DEST_DIR/$KIND.apk"
INFO="$DEST_DIR/$KIND.info"

if [[ "$PRINT" -eq 1 ]]; then
  printf '%s\n' "$DEST"
  [[ -f "$DEST" ]]
  exit
fi

if [[ "$KIND" == release ]]; then
  BUILT="$REPO_ROOT/app/build/outputs/apk/release/app-release.apk"
  GRADLE_TASK=assembleRelease
else
  BUILT="$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk"
  GRADLE_TASK=assembleDebug
fi

if [[ "$SKIP_BUILD" -eq 0 ]]; then
  # shellcheck source=android_env.sh
  source "$SCRIPT_DIR/android_env.sh"
  require_android_java_21
  if [[ ! -f "$REPO_ROOT/data/quran.db" ]]; then
    (cd "$REPO_ROOT" && python3 tools/build_db.py) >&2
  fi
  (cd "$REPO_ROOT" && ./gradlew "$GRADLE_TASK") >&2
fi

[[ -f "$BUILT" ]] || {
  printf 'error: no APK at %s (run without --skip-build)\n' "$BUILT" >&2
  exit 1
}

SHA="$(cd "$REPO_ROOT" && git rev-parse --short=8 HEAD 2>/dev/null || echo unknown)"
BRANCH="$(cd "$REPO_ROOT" && git rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)"
DIRTY=0
[[ -z "$(cd "$REPO_ROOT" && git status --porcelain 2>/dev/null)" ]] || DIRTY=1

# Copy to a temp name and rename, so a reader never sees a half-written APK
# and a full disk cannot leave a truncated file at the known path.
mkdir -p "$DEST_DIR"
TMP="$DEST.tmp.$$"
trap 'rm -f -- "$TMP"' EXIT
cp --reflink=auto -- "$BUILT" "$TMP"
cmp -s -- "$BUILT" "$TMP" || {
  printf 'error: copy to %s is not identical to %s (disk full?)\n' "$DEST_DIR" "$BUILT" >&2
  exit 1
}
mv -f -- "$TMP" "$DEST"
cat > "$INFO" <<EOF
sha=$SHA
branch=$BRANCH
dirty=$DIRTY
built_at=$(date -Iseconds)
bytes=$(stat -c%s -- "$DEST")
EOF

printf '%s\n' "$DEST"
