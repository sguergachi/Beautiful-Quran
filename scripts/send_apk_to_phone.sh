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
WAIT=0
RELEASE=0
LABEL=""
KEEP_OLD=0
WAIT_SECONDS="${KDECONNECT_WAIT_SECONDS:-300}"
DEVICE_NAME="${KDECONNECT_DEVICE_NAME:-}"
DEVICE_ID="${KDECONNECT_DEVICE_ID:-}"
STAGE_DIR="${BQ_APK_STAGE:-${TMPDIR:-/tmp}/beautiful-quran-apks}"

usage() {
  cat <<'EOF'
Usage: scripts/send_apk_to_phone.sh [--release] [--label TEXT] [--skip-build]
                                    [--wait] [--name NAME] [--keep-old]

  --release      Build/send the release APK (default: debug)
  --label TEXT   What this build is, for the filename (default: the branch)
  --skip-build   Use the APK already in app/build/outputs
  --wait         Poll until a paired phone is reachable (default 300s)
  --name NAME    KDE Connect device name (default: first available)
  --keep-old     Do not delete previously staged APKs after a successful send

Env: KDECONNECT_DEVICE_ID, KDECONNECT_DEVICE_NAME, KDECONNECT_WAIT_SECONDS,
     BQ_APK_STAGE (where named builds are staged; default $TMPDIR/beautiful-quran-apks)
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-build) SKIP_BUILD=1 ;;
    --release) RELEASE=1 ;;
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

if [[ "$RELEASE" -eq 1 ]]; then
  APK="$REPO_ROOT/app/build/outputs/apk/release/app-release.apk"
  GRADLE_TASK=assembleRelease
else
  APK="$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk"
  GRADLE_TASK=assembleDebug
fi

if [[ "$SKIP_BUILD" -eq 0 ]]; then
  require_android_java_21
  if [[ ! -f "$REPO_ROOT/data/quran.db" ]]; then
    (cd "$REPO_ROOT" && python3 tools/build_db.py)
  fi
  (cd "$REPO_ROOT" && ./gradlew "$GRADLE_TASK")
fi

[[ -f "$APK" ]] || {
  printf 'error: no APK at %s (run without --skip-build)\n' "$APK" >&2
  exit 1
}

# Name it after the work, so the phone can tell one build from the next.
slug() {
  printf '%s' "$1" | tr '[:upper:]' '[:lower:]' |
    sed -e 's#[^a-z0-9]\+#-#g' -e 's#^-*##' -e 's#-*$##' | cut -c1-48
}
[[ -n "$LABEL" ]] || LABEL="$(cd "$REPO_ROOT" && git rev-parse --abbrev-ref HEAD 2>/dev/null || echo build)"
SHA="$(cd "$REPO_ROOT" && git rev-parse --short=8 HEAD 2>/dev/null || date +%H%M)"
KIND=$([[ "$RELEASE" -eq 1 ]] && echo release || echo debug)
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

# The new build has gone; the ones before it are landfill. Scoped to the
# staging directory and to the file just sent, so this can only ever remove
# builds this script staged.
if [[ "$KEEP_OLD" -eq 0 ]]; then
  removed=0
  while IFS= read -r -d '' old; do
    rm -f -- "$old" && removed=$((removed + 1))
  done < <(find "$STAGE_DIR" -maxdepth 1 -type f -name '*.apk' ! -name "$(basename -- "$APK")" -print0)
  (( removed == 0 )) || printf 'Deleted %d older staged APK(s) from %s\n' "$removed" "$STAGE_DIR"
fi

printf 'Kept: %s\n' "$APK"
