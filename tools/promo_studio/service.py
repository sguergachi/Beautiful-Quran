"""Keeping the Studio server up: systemd notify + watchdog, no dependencies.

Run under `promo-studio.service` (install_service.sh) the server tells systemd
when it is ready and, every few seconds, that it still answers its own health
check. A crashed process is restarted straight away; a hung one (a deadlock, a
wedged thread) stops pinging and is restarted too. Outside systemd every
function here does nothing.
"""
import os
import socket
import threading
import time
import urllib.request

UNIT = "promo-studio.service"


def notify(message):
    """Send one sd_notify message (READY=1, WATCHDOG=1, STATUS=...); False when not under systemd."""
    addr = os.environ.get("NOTIFY_SOCKET")
    if not addr:
        return False
    if addr.startswith("@"):
        addr = "\0" + addr[1:]
    try:
        with socket.socket(socket.AF_UNIX, socket.SOCK_DGRAM) as s:
            s.sendto(message.encode(), addr)
        return True
    except OSError:
        return False


def watchdog_interval():
    """Seconds between pings (half systemd's WatchdogSec), or None when no watchdog is set."""
    try:
        usec = int(os.environ.get("WATCHDOG_USEC", "0"))
    except ValueError:
        return None
    return usec / 2_000_000 if usec > 0 else None


def start_watchdog(host, port, interval=None, ping=notify):
    """Ping systemd only while this server answers /api/health: a process that
    is alive but stuck stops pinging and gets restarted. Returns the thread, or
    None without a watchdog."""
    interval = interval or watchdog_interval()
    if not interval:
        return None
    url = f"http://{'127.0.0.1' if host in ('0.0.0.0', '') else host}:{port}/api/health"

    def run():
        while True:
            try:
                with urllib.request.urlopen(url, timeout=max(1.0, interval / 2)) as r:
                    if r.status == 200:
                        ping("WATCHDOG=1")
            except Exception:
                pass                                   # no ping: systemd decides
            time.sleep(interval)

    t = threading.Thread(target=run, name="watchdog", daemon=True)
    t.start()
    return t
