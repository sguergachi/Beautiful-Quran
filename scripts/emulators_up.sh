#!/usr/bin/env bash
# Bring up `count` lean, headless Android emulators for parallel agents.
# Each agent targets its own AVD through the existing run script:
#
#   scripts/emulators_up.sh [count]          # default 3
#   ANDROID_AVD_NAME=BeautifulQuran_API_35_1 ANDROID_EMULATOR_HEADLESS=1 \
#     scripts/run_android_app.sh             # agent 1's command
#
# AVDs are named <ANDROID_AVD_BASE>_0..N-1 and share one system image, so
# creating them is cheap. They boot concurrently and get distinct adb
# serials (emulator-5556, emulator-5558, ...). Stop them all with:
#
#   scripts/emulators_down.sh
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"

# shellcheck source=android_env.sh
source "$SCRIPT_DIR/android_env.sh"

ANDROID_API="${ANDROID_API:-35}"
ANDROID_AVD_BASE="${ANDROID_AVD_BASE:-BeautifulQuran_API_${ANDROID_API}}"
ANDROID_AVD_RAM="${ANDROID_AVD_RAM:-2048}"
ANDROID_AVD_CORES="${ANDROID_AVD_CORES:-2}"
ANDROID_DEVICE_ID="${ANDROID_DEVICE_ID:-pixel_7}"
EMULATOR="$ANDROID_HOME/emulator/emulator"
ADB="$ANDROID_HOME/platform-tools/adb"
AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"
BOOT_TIMEOUT_SECONDS="${BOOT_TIMEOUT_SECONDS:-300}"
COUNT="${1:-3}"
SYSTEM_IMAGE_DIR="$ANDROID_HOME/system-images/android-${ANDROID_API}/google_apis/x86_64"

log() {
  printf '\n==> %s\n' "$*" >&2
}

fail() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

# Headless instances still need a live display for the host-GPU GL context.
restore_local_display() {
  local socket xauthority
  if [[ -z "${XAUTHORITY:-}" || ! -r "${XAUTHORITY:-}" ]]; then
    for xauthority in "/run/user/$(id -u)"/xauth_* "$HOME/.Xauthority"; do
      if [[ -r "$xauthority" ]]; then
        export XAUTHORITY="$xauthority"
        break
      fi
    done
  fi
  for socket in /tmp/.X11-unix/X*; do
    [[ -S "$socket" ]] || continue
    export DISPLAY=":${socket##*/X}"
    return 0
  done
  fail "no graphical display found (no /tmp/.X11-unix/X*). Use ANDROID_EMULATOR_GPU=swiftshader_indirect"
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

avd_exists() {
  "$EMULATOR" -list-avds 2>/dev/null | grep -Fxq "$1"
}

tune_avd() {
  local avd="$1"
  local cfg="$HOME/.android/avd/${avd}.avd/config.ini"
  [[ -f "$cfg" ]] || return 0
  # shellcheck disable=SC2016
  for key in "hw.gpu.enabled=yes" "hw.gpu.mode=host" "hw.ramSize=$ANDROID_AVD_RAM" "hw.cpu.ncore=$ANDROID_AVD_CORES"; do
    if grep -q "^${key%%=*}=*" "$cfg"; then
      sed -i "s|^${key%%=*}.*|$key|" "$cfg"
    else
      printf '%s\n' "$key" >> "$cfg"
    fi
  done
}

create_avd() {
  local avd="$1"
  avd_exists "$avd" && return 0

  device_args=()
  if "$AVDMANAGER" list device | grep -Eq "id: .*${ANDROID_DEVICE_ID}"; then
    device_args=(--device "$ANDROID_DEVICE_ID")
  fi
  log "Creating AVD: $avd"
  printf 'no\n' | "$AVDMANAGER" create avd \
    --force \
    --name "$avd" \
    --package "system-images;android-${ANDROID_API};google_apis;x86_64" \
    "${device_args[@]}"
  tune_avd "$avd"
}

# Host GPU when the driver is alive (kernel module/user-space must match),
# otherwise CPU-rendered SwiftShader — every agent emulator shares the CPU.
gpu_mode() {
  local icd=""
  for candidate in \
    /usr/share/vulkan/icd.d/nvidia_icd.json \
    /usr/share/vulkan/icd.d/radeon_icd.x86_64.json \
    /usr/share/vulkan/icd.d/intel_icd.x86_64.json \
    /etc/vulkan/icd.d/nvidia_icd.json; do
    if [[ -r "$candidate" ]]; then
      icd="$candidate"
      break
    fi
  done
  if [[ -n "$icd" ]] && [[ "$icd" == *nvidia* ]] && ! nvidia-smi -L >/dev/null 2>&1; then
    printf 'swiftshader_indirect\n'
    return 0
  fi
  printf 'host\n'
}

start_one() {
  local avd="$1"
  local gpu="${ANDROID_EMULATOR_GPU:-$(gpu_mode)}"
  local emulator_log="$REPO_ROOT/.android-emulator-${avd}.log"
  log "Starting emulator: $avd (-gpu $gpu, -memory $ANDROID_AVD_RAM, -cores $ANDROID_AVD_CORES)"
  if command -v setsid >/dev/null 2>&1; then
    setsid -f "$EMULATOR" \
      -avd "$avd" \
      -no-window \
      -no-boot-anim \
      -netdelay none \
      -netspeed full \
      -gpu "$gpu" \
      -accel on \
      -memory "$ANDROID_AVD_RAM" \
      -cores "$ANDROID_AVD_CORES" \
      > "$emulator_log" 2>&1 < /dev/null
  else
    nohup "$EMULATOR" \
      -avd "$avd" \
      -no-window \
      -no-boot-anim \
      -netdelay none \
      -netspeed full \
      -gpu "$gpu" \
      -accel on \
      -memory "$ANDROID_AVD_RAM" \
      -cores "$ANDROID_AVD_CORES" \
      > "$emulator_log" 2>&1 < /dev/null &
  fi
}

memory_warning() {
  local available_mb available_gb needed_gb
  available_mb="$(awk '/MemAvailable/ { print int($2 / 1024) }' /proc/meminfo)"
  available_gb=$((available_mb / 1024))
  needed_gb=$((COUNT * (ANDROID_AVD_RAM / 1024 + 1)))
  if (( available_gb < needed_gb )); then
    printf 'warning: ~%s GB RAM available, %s lean emulators need ~%s GB\n' \
      "$available_gb" "$COUNT" "$needed_gb" >&2
    printf 'close heavy apps or lower COUNT / ANDROID_AVD_RAM.\n' >&2
  fi
}

main() {
  require_file() {
    [[ -e "$1" ]] || fail "$2"
  }
  require_file "$EMULATOR" "Android emulator not found. Run scripts/setup_android_emulator.sh first."
  require_file "$ADB" "adb not found. Run scripts/setup_android_emulator.sh first."
  require_file "$AVDMANAGER" "avdmanager not found. Run scripts/setup_android_emulator.sh first."
  [[ -d "$SYSTEM_IMAGE_DIR" ]] || fail "system image $SYSTEM_IMAGE_DIR not found. Run scripts/setup_android_emulator.sh first."

  local avds=() i avd
  for ((i = 0; i < COUNT; i++)); do
    avds+=("${ANDROID_AVD_BASE}_${i}")
  done

  for avd in "${avds[@]}"; do
    create_avd "$avd"
  done

  memory_warning
  restore_local_display

  local running=0
  for avd in "${avds[@]}"; do
    if serial_for_avd "$avd" >/dev/null 2>&1; then
      log "Reusing running emulator: $avd"
      running=$((running + 1))
    else
      if pgrep -f "qemu-system.*-avd[[:space:]]+${avd}([[:space:]]|$)" >/dev/null 2>&1; then
        fail "stale (unregistered) emulator process for $avd holds its lock. Run: scripts/emulators_down.sh $avd"
      fi
      start_one "$avd"
    fi
  done

  local pending=() serial still=()
  pending=("${avds[@]}")
  local deadline=$((SECONDS + BOOT_TIMEOUT_SECONDS))
  log "Waiting for $COUNT emulators to boot (up to ${BOOT_TIMEOUT_SECONDS}s)"
  while ((${#pending[@]} > 0)); do
    if (( SECONDS >= deadline )); then
      for avd in "${pending[@]}"; do
        printf '\n--- last lines of %s ---\n' "$REPO_ROOT/.android-emulator-${avd}.log" >&2
        tail -n 20 "$REPO_ROOT/.android-emulator-${avd}.log" >&2 || true
      done
      fail "emulators still booting after ${BOOT_TIMEOUT_SECONDS}s: ${pending[*]}"
    fi
    still=()
    for avd in "${pending[@]}"; do
      serial="$(serial_for_avd "$avd" || true)"
      if [[ -n "$serial" ]] \
        && [[ "$("$ADB" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
        "$ADB" -s "$serial" shell input keyevent 82 >/dev/null 2>&1 || true
        log "Booted $avd ($serial)"
      else
        if (( SECONDS > 8 )) \
          && ! pgrep -f "qemu-system.*-avd[[:space:]]+${avd}([[:space:]]|$)" >/dev/null 2>&1; then
          printf '\n--- last lines of %s ---\n' "$REPO_ROOT/.android-emulator-${avd}.log" >&2
          tail -n 20 "$REPO_ROOT/.android-emulator-${avd}.log" >&2 || true
          fail "emulator $avd exited before booting; see its log above"
        fi
        still+=("$avd")
      fi
    done
    pending=("${still[@]}")
    if ((${#pending[@]} > 0)); then
      sleep 3
    fi
  done

  printf '\n%s agent emulators are up.\n' "$COUNT"
  printf '%-32s %-16s %s\n' 'AVD' 'SERIAL' 'AGENT COMMAND'
  for avd in "${avds[@]}"; do
    serial="$(serial_for_avd "$avd" || true)"
    printf '%-32s %-16s ANDROID_AVD_NAME=%s ANDROID_EMULATOR_HEADLESS=1 scripts/run_android_app.sh\n' \
      "$avd" "$serial" "$avd"
  done
  printf '\nStop them all with: scripts/emulators_down.sh\n'
}

main "$@"
