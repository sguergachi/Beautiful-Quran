#!/usr/bin/env bash
# The whole APK delivery in one script: build, name it after the work, share
# it to a paired KDE Connect phone, retry a failed send, and throw the older
# builds away. Call it and read the exit status — no hand-rolled steps.
#
# Three rules live in here rather than in anyone's head:
#
#   * The phone never receives a generic app-debug.apk / app-release.apk. It
#     receives a file named after the work and the commit, because a phone full
#     of identically-named builds is a phone you cannot test from. (KDE Connect
#     also drops repeat sends of the same filename to its notification rate
#     limit, so a generic name is a send that silently does not arrive.)
#   * A send that fails is detected and retried here (10s, 30s, 60s); when
#     every attempt fails the exit status says so and the staged copy is kept
#     for a re-send — nothing is quietly dropped.
#   * Once a new build has gone, the ones before it are deleted. They are
#     throwaway artifacts of a quarter of a gigabyte each, and left to pile up
#     they filled /tmp — which truncated a copy mid-send and shipped a broken
#     APK to the phone.
#
# Only plain APKs are sent; the zip detour is retired. (--zip is still
# accepted and ignored, because the T3 Code "Send APK" action passes it.)
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
LABEL=""
KEEP_OLD=0
WAIT_SECONDS="${KDECONNECT_WAIT_SECONDS:-300}"
DEVICE_NAME="${KDECONNECT_DEVICE_NAME:-}"
DEVICE_ID="${KDECONNECT_DEVICE_ID:-}"
STAGE_DIR="${BQ_APK_STAGE:-${TMPDIR:-/tmp}/beautiful-quran-apks}"

usage() {
  cat <<'EOF'
Usage: scripts/send_apk_to_phone.sh [--release] [--label TEXT] [--skip-build]
                                    [--published] [--wait] [--name NAME] [--keep-old]

  --release      Build/send the release APK (default: debug)
  --label TEXT   What this build is, for the filename (default: the branch)
  --skip-build   Use the APK already in app/build/outputs
  --published    Send the APK at this worktree's known location
                 (scripts/publish_apk.sh) when it matches HEAD on a clean
                 tree; otherwise rebuild and republish it first
  --wait         Poll until a paired phone is reachable (default 300s)
  --name NAME    KDE Connect device name (default: first available)
  --keep-old     Do not delete previously staged builds after a successful send
  --zip          Accepted and ignored: zip sends are retired; the plain APK
                 is sent (kept only so the T3 Code action's flags still parse)

Env: KDECONNECT_DEVICE_ID, KDECONNECT_DEVICE_NAME, KDECONNECT_WAIT_SECONDS,
     BQ_APK_STAGE (where named builds are staged; default $TMPDIR/beautiful-quran-apks)
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-build) SKIP_BUILD=1 ;;
    --published) PUBLISHED=1 ;;
    --release) RELEASE=1 ;;
    --zip) printf 'note: zip sends are retired; sending the plain APK\n' ;;
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
# --published an APK already there is reused when it is current.
if [[ "$PUBLISHED" -eq 1 ]]; then
  APK="$("${PUBLISH[@]}" --print || true)"
  # Only an APK built from exactly this commit on a clean tree is sent as-is.
  # Anything else is republished (gradle is a no-op when nothing changed): the
  # action runs unattended, so a warning nobody reads would ship the old build.
  if [[ -f "$APK" ]]; then
    published_sha="$(sed -n 's/^sha=//p' "${APK%.apk}.info" 2>/dev/null | head -n 1)"
    published_dirty="$(sed -n 's/^dirty=//p' "${APK%.apk}.info" 2>/dev/null | head -n 1)"
    head_sha="$(cd "$REPO_ROOT" && git rev-parse --short=8 HEAD 2>/dev/null || true)"
    dirty_now=0
    [[ -z "$(cd "$REPO_ROOT" && git status --porcelain 2>/dev/null)" ]] || dirty_now=1
    if [[ "$published_sha" != "$head_sha" || "$published_dirty" != 0 || "$dirty_now" -eq 1 ]]; then
      printf 'Published APK is from %s (HEAD %s); republishing.\n' "${published_sha:-unknown}" "$head_sha" >&2
      APK=""
    fi
  fi
  [[ -f "$APK" ]] || APK="$("${PUBLISH[@]}" | tail -n 1)"
elif [[ "$SKIP_BUILD" -eq 1 ]]; then
  APK="$("${PUBLISH[@]}" --skip-build | tail -n 1)"
else
  APK="$("${PUBLISH[@]}" | tail -n 1)"
fi

[[ -f "$APK" ]] || {
  printf 'error: no APK at %s\n' "$APK" >&2
  exit 1
}

# The commit the published APK was built from names the file on the phone.
BUILT_SHA="$(sed -n 's/^sha=//p' "${APK%.apk}.info" 2>/dev/null | head -n 1)"

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
    printf 'error: send failed after 4 attempts; kept %s for a re-send\n' "$APK" >&2
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
