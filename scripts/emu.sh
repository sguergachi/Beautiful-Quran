#!/usr/bin/env bash
# GPU-accelerated Android emulator pool for parallel agents.
#
#   serial=$(scripts/emu.sh lease "my-task")   # claim (and boot) a free emulator
#   ANDROID_SERIAL=$serial scripts/emu.sh run  # build, install, launch
#   ANDROID_SERIAL=$serial scripts/emu.sh shot /tmp/a.png
#   scripts/emu.sh release "$serial"           # give it back; it stays booted for the next agent
#
# Other commands: status, perf [reset], doctor, down (stops free slots to free RAM).
#
# Design (why it is fast and safe to share):
#   * One golden AVD holds a booted snapshot. Every pool slot is a -read-only
#     instance of it, so N agents boot in ~20s (vs ~40s cold) and need no AVD of
#     their own. Slots are rented homes: they stay booted between tenants and a
#     new tenant gets the app's data wiped, not a reboot.
#   * Host GPU via -gpu host (NVIDIA GLES translator); Vulkan is left off, it
#     is the path that has crashed the renderer. -no-window still needs a live
#     X display for the GL context, so DISPLAY/XAUTHORITY are discovered.
#   * -no-audio: agents never play recitation through the user's speakers.
#   * Leases live in tmpfs under flock, so two agents can't take one slot; a
#     lease untouched for EMU_LEASE_TTL seconds is considered abandoned.
#   * `run` AOT-compiles the app after install; without it a fresh install is
#     interpreted and janks badly regardless of GPU.
#   * Booting refuses when host RAM is short. Swapping emulators are what made
#     earlier runs crawl at 2-3 fps.
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=android_env.sh
source "$SCRIPT_DIR/android_env.sh"

GOLD="${EMU_GOLD:-BeautifulQuran_gold_35}"
SNAPSHOT=pool_ready
SLOTS="${EMU_SLOTS:-4}"
BASE_PORT=5600            # slot i -> console port BASE_PORT+2i, serial emulator-<port>
GOLD_PORT=5598
RAM="${EMU_RAM:-3072}"    # MB; a 260MB APK install crashed surfaceflinger at 2GB
CORES="${EMU_CORES:-3}"
LEASE_TTL="${EMU_LEASE_TTL:-7200}"
BOOT_TIMEOUT="${EMU_BOOT_TIMEOUT:-240}"
STATE="${XDG_RUNTIME_DIR:-/tmp}/bq-emu"
ADB="$ANDROID_HOME/platform-tools/adb"
EMULATOR="$ANDROID_HOME/emulator/emulator"
mkdir -p "$STATE"

log() { printf '==> %s\n' "$*" >&2; }
fail() { printf 'error: %s\n' "$*" >&2; exit 1; }
serial_of() { echo "emulator-$((BASE_PORT + 2 * $1))"; }
port_of() { echo "${1#emulator-}"; }
adb_s() { "$ADB" -s "$1" "${@:2}"; }
mem_avail_mb() { awk '/MemAvailable/ { print int($2 / 1024) }' /proc/meminfo; }
is_up() { "$ADB" devices | grep -q "^$1[[:space:]]*device"; }

# A headless host-GPU emulator still needs an X display for its GL context.
gl_env() {
  local sock x
  for x in "/run/user/$(id -u)"/xauth_* "$HOME/.Xauthority"; do
    [[ -r "$x" ]] && { export XAUTHORITY="$x"; break; }
  done
  if [[ -z "${DISPLAY:-}" ]]; then
    for sock in /tmp/.X11-unix/X*; do
      [[ -S "$sock" ]] && { export DISPLAY=":${sock##*/X}"; break; }
    done
  fi
  [[ -n "${DISPLAY:-}" ]] || fail "no X display for host GPU (start from the desktop or set DISPLAY)"
}

wait_boot() {
  local serial="$1" port deadline=$((SECONDS + BOOT_TIMEOUT))
  port="$(port_of "$serial")"
  until [[ "$(adb_s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; do
    # the launcher takes a moment to exec qemu, so only judge it dead after a grace period
    (( SECONDS > deadline - BOOT_TIMEOUT + 10 )) && ! pgrep -f "qemu-system.* -port $port( |\$)" >/dev/null && fail "$serial died while booting; see $STATE/$serial.log"
    (( SECONDS < deadline )) || fail "$serial not booted after ${BOOT_TIMEOUT}s; see $STATE/$serial.log"
    sleep 0.5
  done
  # install fails with 'Broken pipe' until the package service is up, and
  # boot_completed can precede it
  until adb_s "$serial" shell pm path android >/dev/null 2>&1; do sleep 0.5; done
  adb_s "$serial" shell input keyevent 82 >/dev/null 2>&1 || true
}

launch() {  # serial, extra emulator args...
  local serial="$1"; shift
  gl_env
  setsid -f "$EMULATOR" -avd "$GOLD" -port "$(port_of "$serial")" \
    -no-window -no-audio -no-boot-anim -no-metrics \
    -gpu host -accel on -feature -Vulkan -netdelay none -netspeed full \
    "$@" > "$STATE/$serial.log" 2>&1 < /dev/null
}

# One-time: create the golden AVD, cold boot it, park the snapshot every slot loads.
build_gold() {
  [[ -d "$HOME/.android/avd/$GOLD.avd/snapshots/$SNAPSHOT" ]] && return 0
  log "Building golden AVD $GOLD (one-time, ~2 min)"
  local cfg="$HOME/.android/avd/$GOLD.avd/config.ini" kv serial="emulator-$GOLD_PORT"
  if [[ ! -f "$cfg" ]]; then
    printf 'no\n' | "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd --force \
      --name "$GOLD" --package "system-images;android-35;google_apis;x86_64" --device pixel_7 >&2
  fi
  for kv in hw.gpu.enabled=yes hw.gpu.mode=host "hw.ramSize=$RAM" "hw.cpu.ncore=$CORES"; do
    if grep -q "^${kv%%=*}=" "$cfg"; then sed -i "s|^${kv%%=*}=.*|$kv|" "$cfg"; else echo "$kv" >> "$cfg"; fi
  done
  launch "$serial" -no-snapshot-load
  wait_boot "$serial"
  adb_s "$serial" shell 'settings put global verifier_verify_adb_installs 0
    settings put global package_verifier_enable 0
    settings put global hide_error_dialogs 1
    settings put system screen_off_timeout 2147483647
    svc power stayon true'
  adb_s "$serial" emu avd snapshot save "$SNAPSHOT" >&2
  adb_s "$serial" emu kill >/dev/null 2>&1 || true
  while pgrep -f "qemu-system.* -port $GOLD_PORT( |\$)" >/dev/null; do sleep 1; done
}

boot_slot() {
  local serial="$1"
  is_up "$serial" && return 0
  local need=$((RAM + 1024))  # RSS runs ~0.7GB over the guest RAM (GPU buffers)
  if [[ -z "${EMU_FORCE:-}" ]] && (( $(mem_avail_mb) < need )); then
    status >&2
    fail "only $(mem_avail_mb)MB RAM available, need ~${need}MB per emulator. Release an idle one, or EMU_FORCE=1"
  fi
  ( flock 9; build_gold ) 9> "$STATE/gold.lock" || return 1
  log "Booting $serial from snapshot"
  launch "$serial" -read-only -snapshot "$SNAPSHOT" -no-snapshot-save
  wait_boot "$serial"
}

# Prints the serial of a free (or abandoned) slot, ready to use. Slots are
# rented homes: they stay booted between tenants, so a warm one is preferred and
# only its app state is wiped. A cold slot is booted only when none is warm.
lease() {
  local label="${1:-$(basename "$PWD")}" i serial f warm="" cold=""
  exec 8> "$STATE/lease.lock"; flock 8
  for ((i = 0; i < SLOTS; i++)); do
    serial="$(serial_of "$i")"; f="$STATE/$serial.lease"
    [[ -f "$f" ]] && (( $(date +%s) - $(stat -c %Y "$f") < LEASE_TTL )) && continue
    if is_up "$serial"; then warm="${warm:-$serial}"; else cold="${cold:-$serial}"; fi
  done
  local got="${warm:-$cold}"
  [[ -n "$got" ]] && echo "$label" > "$STATE/$got.lease"
  exec 8>&-
  [[ -n "$got" ]] || { status >&2; fail "all $SLOTS slots are leased; wait, or release one that is idle"; }
  if [[ "$got" == "$warm" ]]; then
    log "Reusing warm $got"
    adb_s "$got" shell 'am force-stop com.beautifulquran; pm clear com.beautifulquran; input keyevent 3' >/dev/null 2>&1 || true
  else
    boot_slot "$got" || { rm -f "$STATE/$got.lease"; exit 1; }
  fi
  echo "$got"
}

need_serial() {
  SERIAL="${ANDROID_SERIAL:-}"
  [[ -n "$SERIAL" ]] || fail "set ANDROID_SERIAL (use: ANDROID_SERIAL=\$(scripts/emu.sh lease) ...)"
  touch "$STATE/$SERIAL.lease" 2>/dev/null || true   # keeps the lease alive while in use
}

status() {
  local s name f label rss
  printf '%-15s %-26s %-6s %s\n' SERIAL AVD RSS LEASE
  while read -r s; do
    name="$(adb_s "$s" emu avd name 2>/dev/null | head -n1 | tr -d '\r')"
    rss="$(ps -o rss= -p "$(pgrep -f "qemu-system.* -port ${s#emulator-}( |\$)" | head -n1)" 2>/dev/null | awk '{printf "%dMB", $1/1024}')"
    f="$STATE/$s.lease"
    if [[ -f "$f" ]]; then label="$(cat "$f") ($(( ($(date +%s) - $(stat -c %Y "$f")) / 60 ))m idle)"; else label=free; fi
    [[ "$name" == "$GOLD" ]] || label=external
    printf '%-15s %-26s %-6s %s\n' "$s" "${name:-?}" "${rss:-?}" "$label"
  done < <("$ADB" devices | awk '/^emulator-[0-9]+[[:space:]]+device$/ { print $1 }')
  printf 'host RAM available: %sMB, pool: %s slots x %sMB\n' "$(mem_avail_mb)" "$SLOTS" "$RAM"
}

cmd_release() {
  local serial="${1:-${ANDROID_SERIAL:-}}"
  [[ -n "$serial" ]] || fail "usage: emu.sh release <serial>"
  rm -f "$STATE/$serial.lease"
  log "released $serial (still booted for the next tenant; \`emu.sh down\` frees its RAM)"
}

cmd_run() {  # [--release] [--apk FILE]
  local variant=debug apk=""
  while (($#)); do
    case "$1" in
      --release) variant=release ;;
      --apk) apk="$2"; shift ;;
      *) fail "usage: emu.sh run [--release] [--apk FILE]" ;;
    esac
    shift
  done
  need_serial
  if [[ -z "$apk" ]]; then
    require_android_java_21
    [[ -f "$REPO_ROOT/data/quran.db" ]] || (cd "$REPO_ROOT" && python3 tools/build_db.py)
    (cd "$REPO_ROOT" && ./gradlew "assemble${variant^}" >&2)
    apk="$(ls -t "$REPO_ROOT"/app/build/outputs/apk/"$variant"/*.apk | head -n1)"
  fi
  log "Installing $(basename "$apk")"
  adb_s "$SERIAL" install -r -t "$apk" >&2
  # A fresh install runs un-AOT-compiled code: measured 18% janky / 21ms p50 vs
  # 0% / 5ms after this 4s step. Skip with EMU_COMPILE=0.
  [[ "${EMU_COMPILE:-1}" == 0 ]] || adb_s "$SERIAL" shell cmd package compile -f -m speed com.beautifulquran >/dev/null
  adb_s "$SERIAL" shell am start -S -n com.beautifulquran/.MainActivity >/dev/null
  log "running on $SERIAL"
}

cmd_perf() {
  need_serial
  if [[ "${1:-}" == reset ]]; then
    adb_s "$SERIAL" shell dumpsys gfxinfo com.beautifulquran reset >/dev/null; return
  fi
  adb_s "$SERIAL" shell dumpsys SurfaceFlinger | grep -m1 'GLES:' | cut -c1-140
  adb_s "$SERIAL" shell dumpsys gfxinfo com.beautifulquran \
    | grep -E 'Total frames|Janky frames:|percentile' >&2
  echo "(emulator frame times measure the host; compare revisions, never publish them)" >&2
}

cmd_doctor() {
  local ok=1 s
  [[ -r /dev/kvm && -w /dev/kvm ]] && echo "ok   KVM" || { echo "FAIL /dev/kvm not usable"; ok=0; }
  if gl_env 2>/dev/null; then echo "ok   X display $DISPLAY"; else echo "FAIL no X display (host GPU can't start)"; ok=0; fi
  if command -v nvidia-smi >/dev/null; then
    nvidia-smi -L >/dev/null 2>&1 && echo "ok   $(nvidia-smi -L | head -n1 | cut -d'(' -f1)" || { echo "FAIL nvidia driver mismatch (reboot)"; ok=0; }
  fi
  echo "     host RAM available: $(mem_avail_mb)MB; swap used: $(free -m | awk '/Swap/ {print $3}')MB"
  while read -r s; do
    echo "     $s: $(adb_s "$s" shell dumpsys SurfaceFlinger 2>/dev/null | grep -m1 'GLES:' | grep -o 'Translator ([^)]*)' | head -n1)"
  done < <("$ADB" devices | awk '/^emulator-[0-9]+[[:space:]]+device$/ { print $1 }')
  [[ -d "$HOME/.android/avd/$GOLD.avd/snapshots/$SNAPSHOT" ]] && echo "ok   golden snapshot" || echo "     golden snapshot builds on first lease (~2 min)"
  (( ok )) || exit 1
}

cmd_down() {  # stops free slots only; a leased one has a tenant
  local i serial f
  for ((i = 0; i < SLOTS; i++)); do
    serial="$(serial_of "$i")"; f="$STATE/$serial.lease"
    [[ -f "$f" ]] && (( $(date +%s) - $(stat -c %Y "$f") < LEASE_TTL )) && continue
    rm -f "$f"; adb_s "$serial" emu kill >/dev/null 2>&1 || true
  done
}

case "${1:-}" in
  lease) shift; lease "$@" ;;
  release) shift; cmd_release "$@" ;;
  status) status ;;
  run) shift; cmd_run "$@" ;;
  shot) shift; need_serial; adb_s "$SERIAL" exec-out screencap -p > "${1:-/tmp/emu-$SERIAL.png}"; echo "${1:-/tmp/emu-$SERIAL.png}" ;;
  perf) shift; cmd_perf "$@" ;;
  doctor) cmd_doctor ;;
  down) cmd_down ;;
  *) sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
