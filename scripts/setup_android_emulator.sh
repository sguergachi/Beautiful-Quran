#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"

# shellcheck source=android_env.sh
source "$SCRIPT_DIR/android_env.sh"

ANDROID_API="${ANDROID_API:-35}"
ANDROID_BUILD_TOOLS="${ANDROID_BUILD_TOOLS:-35.0.0}"
ANDROID_IMAGE_FLAVOR="${ANDROID_IMAGE_FLAVOR:-google_apis}"
ANDROID_IMAGE_ARCH="${ANDROID_IMAGE_ARCH:-x86_64}"
ANDROID_AVD_NAME="${ANDROID_AVD_NAME:-BeautifulQuran_API_${ANDROID_API}}"
ANDROID_DEVICE_ID="${ANDROID_DEVICE_ID:-pixel_7}"
# Per-AVD resource profile. Parallel agent emulators should be lean
# (ANDROID_AVD_RAM=2048 ANDROID_AVD_CORES=2); 4096/6 suits a single interactive
# emulator. Values are baked into the AVD config and can be overridden per run
# with `-memory`/`-cores` at emulator launch.
ANDROID_AVD_RAM="${ANDROID_AVD_RAM:-4096}"
ANDROID_AVD_CORES="${ANDROID_AVD_CORES:-6}"
CMDLINE_TOOLS_VERSION="${ANDROID_CMDLINE_TOOLS_VERSION:-14742923}"
CMDLINE_TOOLS_ZIP="commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip"
CMDLINE_TOOLS_URL="${ANDROID_CMDLINE_TOOLS_URL:-https://dl.google.com/android/repository/$CMDLINE_TOOLS_ZIP}"
JDK_URL="${ANDROID_DEV_JDK_URL:-https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse?project=jdk}"

SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"
SYSTEM_IMAGE="system-images;android-${ANDROID_API};${ANDROID_IMAGE_FLAVOR};${ANDROID_IMAGE_ARCH}"

log() {
  printf '\n==> %s\n' "$*" >&2
}

fail() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "missing required command: $1"
}

with_yes() {
  set +o pipefail
  yes | "$@"
  local status="${PIPESTATUS[1]}"
  set -o pipefail
  return "$status"
}

ensure_java_21() {
  if [[ "$(android_java_major_version "${JAVA_HOME:-}" || true)" == "21" ]]; then
    return
  fi

  require_cmd curl
  require_cmd tar

  log "Installing local JDK 21 in $ANDROID_DEV_JDK_HOME"
  tmp_dir="$(mktemp -d)"
  trap 'rm -rf "$tmp_dir"' EXIT

  curl --fail --location --output "$tmp_dir/jdk21.tar.gz" "$JDK_URL"
  rm -rf "$ANDROID_DEV_JDK_HOME"
  mkdir -p "$ANDROID_DEV_JDK_HOME"
  tar -xzf "$tmp_dir/jdk21.tar.gz" --strip-components=1 -C "$ANDROID_DEV_JDK_HOME"

  export JAVA_HOME="$ANDROID_DEV_JDK_HOME"
  export PATH="$JAVA_HOME/bin:$PATH"
  require_android_java_21
}

install_cmdline_tools_if_missing() {
  if [[ -x "$SDKMANAGER" ]]; then
    return
  fi

  require_cmd curl
  require_cmd unzip

  log "Installing Android command-line tools in $ANDROID_HOME"
  tmp_dir="$(mktemp -d)"
  trap 'rm -rf "$tmp_dir"' EXIT

  curl --fail --location --output "$tmp_dir/$CMDLINE_TOOLS_ZIP" "$CMDLINE_TOOLS_URL"
  unzip -q "$tmp_dir/$CMDLINE_TOOLS_ZIP" -d "$tmp_dir"

  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  mv "$tmp_dir/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
}

accept_licenses() {
  log "Accepting Android SDK licenses"
  with_yes "$SDKMANAGER" --sdk_root="$ANDROID_HOME" --licenses >/dev/null || true
}

install_sdk_packages() {
  log "Installing Android SDK packages"
  with_yes "$SDKMANAGER" --sdk_root="$ANDROID_HOME" \
    "platform-tools" \
    "emulator" \
    "platforms;android-${ANDROID_API}" \
    "build-tools;${ANDROID_BUILD_TOOLS}" \
    "$SYSTEM_IMAGE"
}

write_local_properties() {
  log "Writing Gradle SDK path"
  printf 'sdk.dir=%s\n' "$ANDROID_HOME" > "$REPO_ROOT/local.properties"
}

build_quran_db_if_missing() {
  if [[ -f "$REPO_ROOT/data/quran.db" ]]; then
    return
  fi

  require_cmd python3
  log "Building bundled Quran database asset"
  (cd "$REPO_ROOT" && python3 tools/build_db.py)
}

# Point the emulator at the host Vulkan ICD so guest Vulkan does not fall
# through to bundled Lavapipe (which has SIGSEGV'd RenderThread under load).
ensure_host_vulkan_features() {
  local features_ini="$HOME/.android/advancedFeatures.ini"
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
  [[ -n "$icd" ]] || return 0

  mkdir -p "$HOME/.android"
  if [[ -f "$features_ini" ]] && grep -qE '^[[:space:]]*Vulkan[[:space:]]*=[[:space:]]*off' "$features_ini"; then
    log "Re-enabling host Vulkan in $features_ini (ICD: $icd)"
    sed -i 's/^[[:space:]]*Vulkan[[:space:]]*=[[:space:]]*off/Vulkan = on/' "$features_ini"
  elif [[ ! -f "$features_ini" ]] || ! grep -qE '^[[:space:]]*Vulkan[[:space:]]*=' "$features_ini"; then
    log "Writing host Vulkan feature flags to $features_ini"
    {
      printf '# Host GPU Vulkan ICD — avoids emulator Lavapipe crashes\n'
      printf 'Vulkan = on\n'
      printf 'GLDirectMem = on\n'
    } >> "$features_ini"
  fi
}

# Host GPU + enough RAM for Compose. Without this, avdmanager often leaves
# hw.gpu.enabled=no and the emulator is unusably laggy.
tune_avd_performance() {
  local cfg="$HOME/.android/avd/${ANDROID_AVD_NAME}.avd/config.ini"
  if [[ ! -f "$cfg" ]]; then
    return
  fi
  log "Tuning AVD for host GPU acceleration: $ANDROID_AVD_NAME"
  # shellcheck disable=SC2016
  if grep -q '^hw.gpu.enabled=' "$cfg"; then
    sed -i 's/^hw\.gpu\.enabled=.*/hw.gpu.enabled=yes/' "$cfg"
  else
    printf 'hw.gpu.enabled=yes\n' >> "$cfg"
  fi
  if grep -q '^hw.gpu.mode=' "$cfg"; then
    sed -i 's/^hw\.gpu\.mode=.*/hw.gpu.mode=host/' "$cfg"
  else
    printf 'hw.gpu.mode=host\n' >> "$cfg"
  fi
  if grep -q '^hw.ramSize=' "$cfg"; then
    sed -i "s/^hw\\.ramSize=.*/hw.ramSize=$ANDROID_AVD_RAM/" "$cfg"
  else
    printf 'hw.ramSize=%s\n' "$ANDROID_AVD_RAM" >> "$cfg"
  fi
  if grep -q '^hw.cpu.ncore=' "$cfg"; then
    sed -i "s/^hw\\.cpu\\.ncore=.*/hw.cpu.ncore=$ANDROID_AVD_CORES/" "$cfg"
  else
    printf 'hw.cpu.ncore=%s\n' "$ANDROID_AVD_CORES" >> "$cfg"
  fi
}

create_avd_if_missing() {
  if "$AVDMANAGER" list avd | grep -Fq "Name: $ANDROID_AVD_NAME"; then
    log "AVD already exists: $ANDROID_AVD_NAME"
    tune_avd_performance
    return
  fi

  log "Creating AVD: $ANDROID_AVD_NAME"
  device_args=()
  if "$AVDMANAGER" list device | grep -Eq "id: .*${ANDROID_DEVICE_ID}"; then
    device_args=(--device "$ANDROID_DEVICE_ID")
  fi

  printf 'no\n' | "$AVDMANAGER" create avd \
    --force \
    --name "$ANDROID_AVD_NAME" \
    --package "$SYSTEM_IMAGE" \
    "${device_args[@]}"
  tune_avd_performance
}

main() {
  ensure_java_21
  install_cmdline_tools_if_missing
  accept_licenses
  install_sdk_packages
  write_local_properties
  build_quran_db_if_missing
  create_avd_if_missing
  ensure_host_vulkan_features

  cat <<EOF

Android emulator setup is ready.

Run the app with:
  scripts/run_android_app.sh

Useful overrides:
  ANDROID_AVD_NAME=$ANDROID_AVD_NAME scripts/run_android_app.sh
  ANDROID_API=$ANDROID_API ANDROID_IMAGE_ARCH=$ANDROID_IMAGE_ARCH scripts/setup_android_emulator.sh
  ANDROID_AVD_RAM=$ANDROID_AVD_RAM ANDROID_AVD_CORES=$ANDROID_AVD_CORES scripts/setup_android_emulator.sh
Run parallel agent emulators with: scripts/emulators_up.sh [count]
EOF
}

main "$@"
