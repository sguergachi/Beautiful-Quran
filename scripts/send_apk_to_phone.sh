#!/usr/bin/env bash
# Build the debug APK and share it to a paired KDE Connect phone.
#
# The phone must be paired *and reachable* (KDE Connect app running, same LAN
# or an active Bluetooth/Tailscale link). Check with: kdeconnect-cli -a
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
APK="$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk"

SKIP_BUILD=0
WAIT=0
WAIT_SECONDS="${KDECONNECT_WAIT_SECONDS:-300}"
DEVICE_NAME="${KDECONNECT_DEVICE_NAME:-}"
DEVICE_ID="${KDECONNECT_DEVICE_ID:-}"

usage() {
  cat <<'EOF'
Usage: scripts/send_apk_to_phone.sh [--skip-build] [--wait] [--name NAME]

  --skip-build   Use an already-built debug APK
  --wait         Poll until a paired phone is reachable (default 300s)
  --name NAME    KDE Connect device name (default: first available)

Env: KDECONNECT_DEVICE_ID, KDECONNECT_DEVICE_NAME, KDECONNECT_WAIT_SECONDS
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-build) SKIP_BUILD=1 ;;
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

if [[ "$SKIP_BUILD" -eq 0 ]]; then
  require_android_java_21
  if [[ ! -f "$REPO_ROOT/data/quran.db" ]]; then
    (cd "$REPO_ROOT" && python3 tools/build_db.py)
  fi
  (cd "$REPO_ROOT" && ./gradlew assembleDebug)
fi

[[ -f "$APK" ]] || {
  printf 'error: no APK at %s (run without --skip-build)\n' "$APK" >&2
  exit 1
}

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
kdeconnect-cli -d "$DEVICE_ID" --share "$APK"
printf 'Shared. Accept the file on the phone if prompted.\n'
