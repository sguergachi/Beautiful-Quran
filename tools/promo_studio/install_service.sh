#!/usr/bin/env bash
# One-time: run the Promo Studio server as a systemd user service that restarts
# itself, so it survives crashes, hangs, a closed terminal or agent session and
# a re-login.
#   tools/promo_studio/install_service.sh              # install + enable (starts it unless another server holds the port)
#   tools/promo_studio/install_service.sh --takeover   # also stop a server started by hand on port 8765, then start the service
#   tools/promo_studio/install_service.sh --uninstall
# Status: systemctl --user status promo-studio     Log: ~/.cache/bq-promo/studio-server.log
set -euo pipefail
HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
UNIT=promo-studio.service
DIR="$HOME/.config/systemd/user"
PORT=8765
if [[ "${1:-}" == "--uninstall" ]]; then
  systemctl --user disable --now "$UNIT" 2>/dev/null || true
  rm -f "$DIR/$UNIT"; systemctl --user daemon-reload
  echo "removed $UNIT"; exit 0
fi
mkdir -p "$DIR" "$HOME/.cache/bq-promo"
sed -e "s|@HERE@|$HERE|g" -e "s|@PYTHON@|$(command -v python3)|g" "$HERE/promo-studio.service.in" > "$DIR/$UNIT"
systemd-analyze --user verify "$DIR/$UNIT" 2>&1 | grep -v "^$" || true
systemctl --user daemon-reload
systemctl --user enable "$UNIT" >/dev/null 2>&1
listener() { ss -ltnp 2>/dev/null | grep ":$PORT " | sed -n 's/.*pid=\([0-9]*\).*/\1/p' | head -1; }
if [[ -n "$(listener)" ]] && ! systemctl --user is-active --quiet "$UNIT"; then
  if [[ "${1:-}" == "--takeover" ]]; then
    pid="$(listener)"; echo "stopping the server started by hand (pid $pid)"
    kill -TERM -- "-$(ps -o pgid= -p "$pid" | tr -d ' ')" 2>/dev/null || kill -TERM "$pid" || true
    for _ in $(seq 1 20); do [[ -z "$(listener)" ]] && break; sleep 0.5; done
  else
    echo "installed and enabled, but port $PORT is held by a server started by hand (pid $(listener)); run with --takeover to switch, or it takes over at next login"
    exit 0
  fi
fi
systemctl --user restart "$UNIT"
for _ in $(seq 1 120); do curl -sf "http://127.0.0.1:$PORT/api/health" >/dev/null && break; sleep 1; done
curl -sf "http://127.0.0.1:$PORT/api/health" >/dev/null && echo "Promo Studio is up: http://127.0.0.1:$PORT/ (service $UNIT)" || { echo "the service did not come up; see: journalctl --user -u promo-studio" >&2; exit 1; }
loginctl enable-linger "$USER" 2>/dev/null || echo "note: could not enable lingering; the service starts at login"
