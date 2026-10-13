#!/usr/bin/env bash
# Build an APK, name it after the work, and share it to a paired KDE Connect
# phone — then throw the previous one away.
#
# Two rules live in here rather than in anyone's head:
#
#   * The phone never receives a generic app-debug.apk / app-release.apk. It
#     receives a file named after the work and the commit, because a phone full
#     of identically-named builds is a phone you cannot test from. (KDE Connect
#     also drops repeat sends of the same filename to its notification rate
#     limit, so a generic name is a send that silently does not arrive.)
#   * Once a new build has gone, the ones before it are deleted. They are
#     throwaway artifacts of a quarter of a gigabyte each, and left to pile up
#     they filled /tmp — which truncated a copy mid-send and shipped a broken
#     APK to the phone.
#
# Staged builds live in their own directory so that cleanup can only ever
# remove files this script created.
#
# The phone must be paired *and reachable* (KDE Connect app running, same LAN
# or an active Bluetooth/Tailscale link). Check with: kdeconnect-cli -a
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"

SKIP_BUILD=0
PUBLISHED=0
WAIT=0
RELEASE=0
ZIP=0
LABEL=""
KEEP_OLD=0
WAIT_SECONDS="${KDECONNECT_WAIT_SECONDS:-300}"
DEVICE_NAME="${KDECONNECT_DEVICE_NAME:-}"
DEVICE_ID="${KDECONNECT_DEVICE_ID:-}"
STAGE_DIR="${BQ_APK_STAGE:-${TMPDIR:-/tmp}/beautiful-quran-apks}"

usage() {
  cat <<'EOF'
Usage: scripts/send_apk_to_phone.sh [--release] [--zip] [--label TEXT] [--skip-build]
                                    [--published] [--wait] [--name NAME] [--keep-old]

  --release      Build/send the release APK (default: debug)
  --zip          Zip the staged build (store only) and share the ZIP instead
  --label TEXT   What this build is, for the filename (default: the branch)
  --skip-build   Use the APK already in app/build/outputs
  --published    Send the APK already at this worktree's known location
                 (scripts/publish_apk.sh); build + publish only if none exists
  --wait         Poll until a paired phone is reachable (default 300s)
  --name NAME    KDE Connect device name (default: first available)
  --keep-old     Do not delete previously staged builds after a successful send

Env: KDECONNECT_DEVICE_ID, KDECONNECT_DEVICE_NAME, KDECONNECT_WAIT_SECONDS,
     BQ_APK_STAGE (where named builds are staged; default $TMPDIR/beautiful-quran-apks)
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-build) SKIP_BUILD=1 ;;
    --published) PUBLISHED=1 ;;
    --release) RELEASE=1 ;;
    --zip) ZIP=1 ;;
    --keep-old) KEEP_OLD=1 ;;
    --label)
      shift
      LABEL="${1:-}"
      [[ -n "$LABEL" ]] || { usage >&2; exit 2; }
      ;;
    --wait) WAIT=1 ;;
    --name)
      shift
      DEVICE_NAME="${1:-}"
      [[ -n "$DEVICE_NAME" ]] || { usage >&2; exit 2; }
      ;;
    -h|--help) usage; exit 0 ;;
    *)
      printf 'error: unknown argument %s\n' "$1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

# shellcheck source=android_env.sh
source "$SCRIPT_DIR/android_env.sh"

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    printf 'error: %s is not installed\n' "$1" >&2
    exit 1
  }
}

require_cmd kdeconnect-cli

KIND=$([[ "$RELEASE" -eq 1 ]] && echo release || echo debug)
PUBLISH=("$SCRIPT_DIR/publish_apk.sh" "--$KIND")

# Every send goes through the worktree's known APK location (publish_apk.sh):
# a build is published there, then the phone gets a named copy of it. With
# --published an APK already there is sent as-is; only a missing one is built.
if [[ "$PUBLISHED" -eq 1 ]]; then
  if APK="$("${PUBLISH[@]}" --print)"; then :; else
    APK="$("${PUBLISH[@]}" | tail -n 1)"
  fi
elif [[ "$SKIP_BUILD" -eq 1 ]]; then
  APK="$("${PUBLISH[@]}" --skip-build | tail -n 1)"
else
  APK="$("${PUBLISH[@]}" | tail -n 1)"
fi

[[ -f "$APK" ]] || {
  printf 'error: no APK at %s\n' "$APK" >&2
  exit 1
}

# What the published APK was built from. A name and a staleness check both
# come from it, rather than from whatever HEAD happens to be at send time.
info_value() { sed -n "s/^$1=//p" "${APK%.apk}.info" 2>/dev/null | head -n 1; }
BUILT_SHA="$(info_value sha)"
if [[ -n "$BUILT_SHA" ]]; then
  HEAD_SHA="$(cd "$REPO_ROOT" && git rev-parse --short=8 HEAD 2>/dev/null || true)"
  if [[ "$BUILT_SHA" != "$HEAD_SHA" || "$(info_value dirty)" == 1 ]]; then
    printf 'warning: published APK was built at %s%s (HEAD is %s) — rebuild without --published if that is not what you want.\n' \
      "$BUILT_SHA" "$([[ "$(info_value dirty)" == 1 ]] && echo ' with uncommitted changes')" "$HEAD_SHA" >&2
  fi
fi

# Name it after the work, so the phone can tell one build from the next.
slug() {
  printf '%s' "$1" | tr '[:upper:]' '[:lower:]' |
    sed -e 's#[^a-z0-9]\+#-#g' -e 's#^-*##' -e 's#-*$##' | cut -c1-48
}
[[ -n "$LABEL" ]] || LABEL="$(cd "$REPO_ROOT" && git rev-parse --abbrev-ref HEAD 2>/dev/null || echo build)"
SHA="${BUILT_SHA:-$(cd "$REPO_ROOT" && git rev-parse --short=8 HEAD 2>/dev/null || date +%H%M)}"
STAGED="$STAGE_DIR/Beautiful-Quran-$(slug "$LABEL")-$SHA-$KIND.apk"

mkdir -p "$STAGE_DIR"
# A build that already went by this name would be dropped by KDE Connect's
# notification rate limit, so give a repeat send a fresh minute stamp.
[[ ! -e "$STAGED" ]] || STAGED="${STAGED%.apk}-$(date +%H%M).apk"
cp -- "$APK" "$STAGED"
# The copy has to be whole: a full staging filesystem truncates it silently,
# and the phone gets a broken install rather than an error.
cmp -s -- "$APK" "$STAGED" || {
  printf 'error: staged copy is not identical to %s (is %s full?)\n' "$APK" "$STAGE_DIR" >&2
  rm -f -- "$STAGED"
  exit 1
}
APK="$STAGED"

# Zip for the slow link: APKs are already compressed, so store without
# recompressing. The phone verifies with `unzip -l`.
if [[ "$ZIP" -eq 1 ]]; then
  ZIPPED="${STAGED%.apk}.zip"
  [[ ! -e "$ZIPPED" ]] || ZIPPED="${ZIPPED%.zip}-$(date +%H%M).zip"
  rm -f -- "$ZIPPED"
  if command -v zip >/dev/null 2>&1; then
    (cd -- "$STAGE_DIR" && zip -j -0 -q "$ZIPPED" "$(basename -- "$STAGED")")
  else
    # No `zip` binary (e.g. minimal containers): stdlib fallback, still stored.
    python3 - "$STAGED" "$ZIPPED" <<'EOF'
import sys, zipfile, os
src, dst = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(dst, "w", zipfile.ZIP_STORED) as z:
    z.write(src, os.path.basename(src))
EOF
  fi
  printf 'Zipped %s (%s bytes)\n' "$(basename -- "$ZIPPED")" "$(stat -c%s -- "$ZIPPED")"
  APK="$ZIPPED"
fi

pick_device() {
  local available id name
  available="$(kdeconnect-cli -a --id-name-only 2>/dev/null || true)"
  [[ -n "$available" ]] || return 1
  if [[ -n "$DEVICE_ID" ]]; then
    printf '%s\n' "$available" | awk -v id="$DEVICE_ID" '$1 == id { print; found=1 } END { exit !found }'
    return
  fi
  if [[ -n "$DEVICE_NAME" ]]; then
    printf '%s\n' "$available" | awk -v n="$DEVICE_NAME" 'tolower($0) ~ tolower(n) { print; found=1 } END { exit !found }'
    return
  fi
  printf '%s\n' "$available" | head -n 1
}

wait_for_device() {
  local deadline now line
  deadline=$((SECONDS + WAIT_SECONDS))
  while (( SECONDS < deadline )); do
    kdeconnect-cli --refresh >/dev/null 2>&1 || true
    line="$(pick_device || true)"
    if [[ -n "$line" ]]; then
      printf '%s\n' "$line"
      return 0
    fi
    sleep 3
  done
  return 1
}

kdeconnect-cli --refresh >/dev/null 2>&1 || true
LINE=""
if [[ "$WAIT" -eq 1 ]]; then
  LINE="$(wait_for_device || true)"
else
  LINE="$(pick_device || true)"
fi

if [[ -z "$LINE" ]]; then
  printf 'error: no reachable KDE Connect phone.\n' >&2
  printf 'Paired devices:\n' >&2
  kdeconnect-cli -l >&2 || true
  printf '\nUnlock the phone, open KDE Connect, and join this LAN (or Bluetooth).\n' >&2
  printf 'Then: kdeconnect-cli -a   and re-run this script.\n' >&2
  exit 1
fi

DEVICE_ID="${LINE%% *}"
DEVICE_LABEL="${LINE#* }"
printf 'Sending %s\n  → %s (%s)\n' "$APK" "$DEVICE_LABEL" "$DEVICE_ID"
# The share fails often enough (phone asleep, link drop) that the script
# retries it 3 times on its own: 10s, then 30s, then 60s.
SHARE_DELAYS=(${BQ_SHARE_RETRY_DELAYS:-10 30 60})
attempt=0
while true; do
  if kdeconnect-cli -d "$DEVICE_ID" --share "$APK"; then
    break
  fi
  attempt=$((attempt + 1))
  if (( attempt > 3 )); then
    printf 'error: share failed after 4 attempts; keeping %s\n' "$APK" >&2
    exit 1
  fi
  delay="${SHARE_DELAYS[$((attempt - 1))]:-60}"
  printf 'Share failed (attempt %d/4); retrying in %ss…\n' "$attempt" "$delay"
  sleep "$delay"
  kdeconnect-cli --refresh >/dev/null 2>&1 || true
  if refreshed="$(pick_device || true)"; then
    if [[ -n "$refreshed" ]]; then
      DEVICE_ID="${refreshed%% *}"
      DEVICE_LABEL="${refreshed#* }"
    fi
  fi
done
printf 'Shared. Accept the file on the phone if prompted.\n'

# The new build has gone; the ones before it are landfill. Scoped to the
# staging directory and to the file just sent, so this can only ever remove
# builds this script staged.
if [[ "$KEEP_OLD" -eq 0 ]]; then
  removed=0
  while IFS= read -r -d '' old; do
    rm -f -- "$old" && removed=$((removed + 1))
  done < <(find "$STAGE_DIR" -maxdepth 1 -type f \( -name '*.apk' -o -name '*.zip' \) ! -name "$(basename -- "$APK")" -print0)
  (( removed == 0 )) || printf 'Deleted %d older staged build(s) from %s\n' "$removed" "$STAGE_DIR"
fi

printf 'Kept: %s\n' "$APK"
