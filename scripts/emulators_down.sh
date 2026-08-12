#!/usr/bin/env bash
# Stop agent emulators created by scripts/emulators_up.sh.
#   scripts/emulators_down.sh                # stop every <base>_<n> AVD
#   scripts/emulators_down.sh <avd-name>     # stop just one
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"

# shellcheck source=android_env.sh
source "$SCRIPT_DIR/android_env.sh"

ANDROID_API="${ANDROID_API:-35}"
ANDROID_AVD_BASE="${ANDROID_AVD_BASE:-BeautifulQuran_API_${ANDROID_API}}"
EMULATOR="$ANDROID_HOME/emulator/emulator"
ADB="$ANDROID_HOME/platform-tools/adb"

log() {
  printf '\n==> %s\n' "$*" >&2
}

serial_for_avd() {
  local avd="$1" serial name
  while read -r serial; do
    [[ -n "$serial" ]] || continue
    name="$("$ADB" -s "$serial" emu avd name 2>/dev/null | head -n1 | tr -d '\r')"
    if [[ "$name" == "$avd" ]]; then
      printf '%s\n' "$serial"
      return 0
    fi
  done < <("$ADB" devices | awk '/^emulator-[0-9]+[[:space:]]+device$/ { print $1 }')
  return 1
}

stop_avd() {
  local avd="$1" serial
  serial="$(serial_for_avd "$avd" || true)"
  if [[ -z "$serial" ]] \
    && ! pgrep -f "qemu-system.*-avd[[:space:]]+${avd}([[:space:]]|$)" >/dev/null 2>&1; then
    return 0
  fi
  log "Stopping $avd ($serial)"
  [[ -n "$serial" ]] && "$ADB" -s "$serial" emu kill >/dev/null 2>&1 || true

  local deadline=$((SECONDS + 30))
  while serial_for_avd "$avd" >/dev/null 2>&1 \
    || pgrep -f "qemu-system.*-avd[[:space:]]+${avd}([[:space:]]|$)" >/dev/null 2>&1; do
    if (( SECONDS >= deadline )); then
      printf 'warning: %s ignored SIGTERM; sending SIGKILL\n' "$avd" >&2
      pkill -9 -f "qemu-system.*-avd[[:space:]]+${avd}([[:space:]]|$)" >/dev/null 2>&1 || true
      sleep 2
      return 1
    fi
    sleep 1
  done
  return 0
}

main() {
  local avds=() avd
  if (($# > 0)); then
    avds=("$1")
  else
    while read -r avd; do
      [[ "$avd" =~ ^${ANDROID_AVD_BASE}_[0-9]+$ ]] && avds+=("$avd")
    done < <("$EMULATOR" -list-avds 2>/dev/null)
  fi

  if ((${#avds[@]} == 0)); then
    printf 'no agent emulators found\n'
    exit 0
  fi

  local failed=0
  for avd in "${avds[@]}"; do
    stop_avd "$avd" || failed=1
  done
  exit "$failed"
}

main "$@"
