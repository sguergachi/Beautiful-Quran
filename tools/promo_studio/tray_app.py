#!/usr/bin/env python3
"""Promo Studio tray manager: Single-instance system tray manager for Promo Studio.

Run `tray_app.py --install` once to place the Promo Studio launcher in the
application menu (and on the Desktop, when one exists).
"""
import atexit
import os
import shutil
import signal
import socket
import subprocess
import sys
import threading
import time
import urllib.request
import webbrowser

HERE = os.path.dirname(os.path.abspath(__file__))
STUDIO = os.path.join(HERE, "studio.py")
REPO = os.path.dirname(os.path.dirname(HERE))
ICON_PATH = os.path.join(REPO, "docs", "play_store_icon.png")
CACHE = os.environ.get("BQ_PROMO_CACHE", os.path.expanduser("~/.cache/bq-promo"))
LOG_FILE = os.path.join(CACHE, "studio-server.log")

HOST = "127.0.0.1"
PORT = 8765
URL = f"http://{HOST}:{PORT}/"
SOCKET_NAME = f"\0promo_studio_tray_{os.getuid()}"

proc = None
state = "stopped"  # "stopped", "starting", "running", "error"
icon_ref = None
server_lock = threading.Lock()


def is_healthy(timeout=1.0):
    try:
        req = urllib.request.Request(f"http://{HOST}:{PORT}/api/health")
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status == 200
    except Exception:
        return False


def update_ui():
    if not icon_ref:
        return
    status_map = {
        "running": f"Promo Studio (Running on :{PORT})",
        "starting": "Promo Studio (Starting...)",
        "stopped": "Promo Studio (Stopped)",
        "error": "Promo Studio (Server Error)",
    }
    title = status_map.get(state, "Promo Studio")
    try:
        icon_ref.title = title
        icon_ref.update_menu()
    except Exception:
        pass


def start_server():
    global proc, state
    with server_lock:
        if is_healthy(timeout=0.5):
            state = "running"
            update_ui()
            return

        _kill_proc()
        os.makedirs(CACHE, exist_ok=True)
        state = "starting"
        update_ui()

        try:
            log_f = open(LOG_FILE, "a", buffering=1, encoding="utf-8")
            log_f.write(f"\n--- Promo Studio server started at {time.strftime('%Y-%m-%d %H:%M:%S')} ---\n")
            proc = subprocess.Popen(
                [sys.executable, STUDIO, "serve", "--host", HOST, "--port", str(PORT)],
                stdout=log_f,
                stderr=subprocess.STDOUT,
                cwd=HERE,
                start_new_session=True,
            )
        except Exception as e:
            state = "error"
            update_ui()
            print(f"Failed to start server: {e}", file=sys.stderr)


def _kill_proc():
    global proc
    p = proc
    proc = None
    if p and p.poll() is None:
        try:
            os.killpg(p.pid, signal.SIGTERM)
            p.wait(timeout=3)
        except (subprocess.TimeoutExpired, ProcessLookupError):
            try:
                os.killpg(p.pid, signal.SIGKILL)
                p.wait(timeout=1)
            except Exception:
                pass


def stop_server():
    global state
    with server_lock:
        state = "stopped"
        _kill_proc()
        update_ui()


def restart_server():
    stop_server()
    time.sleep(0.5)
    start_server()
    open_studio(wait=True)


def open_studio(wait=True):
    def _target():
        if state == "stopped":
            start_server()
        if wait:
            t0 = time.time()
            while time.time() - t0 < 15:
                if is_healthy(timeout=0.5):
                    break
                time.sleep(0.2)
        webbrowser.open_new_tab(URL)

    threading.Thread(target=_target, daemon=True).start()


def view_server_log():
    os.makedirs(CACHE, exist_ok=True)
    if not os.path.exists(LOG_FILE):
        open(LOG_FILE, "a", encoding="utf-8").close()
    try:
        subprocess.Popen(["xdg-open", LOG_FILE])
    except Exception as e:
        print(f"Could not open log file: {e}", file=sys.stderr)


def on_quit():
    stop_server()
    if icon_ref:
        icon_ref.stop()


def ticker():
    global state
    while True:
        try:
            time.sleep(3)
            with server_lock:
                if state == "starting":
                    if proc and proc.poll() is not None:
                        state = "error"
                        update_ui()
                    elif is_healthy(timeout=0.5):
                        state = "running"
                        update_ui()
                elif state == "running":
                    if proc and proc.poll() is not None:
                        state = "error"
                        update_ui()
                    elif not is_healthy(timeout=0.5):
                        if proc and proc.poll() is not None:
                            state = "error"
                        else:
                            state = "starting"
                        update_ui()
                elif state == "stopped":
                    pass
        except Exception:
            pass


def check_single_instance():
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    try:
        s.bind(SOCKET_NAME)
        s.listen(5)
        return s
    except socket.error:
        try:
            client = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            client.settimeout(2.0)
            client.connect(SOCKET_NAME)
            client.sendall(b"open\n")
            client.close()
            print("Promo Studio is already running. Opening browser...", flush=True)
        except Exception as e:
            print(f"Promo Studio is running but failed to communicate: {e}", file=sys.stderr)
        return None


def ipc_listener(s):
    while True:
        try:
            conn, _ = s.accept()
            msg = conn.recv(1024).decode("utf-8", errors="replace").strip()
            if msg == "open":
                open_studio(wait=True)
            elif msg == "quit":
                on_quit()
                break
            conn.close()
        except Exception:
            break


def create_tray_image():
    from PIL import Image, ImageDraw

    if os.path.exists(ICON_PATH):
        try:
            im = Image.open(ICON_PATH).convert("RGBA")
            im.thumbnail((64, 64), Image.Resampling.LANCZOS)
            return im
        except Exception as e:
            print(f"Failed to load icon from {ICON_PATH}: {e}", file=sys.stderr)

    im = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    draw = ImageDraw.Draw(im)
    draw.ellipse((4, 4, 60, 60), fill=(46, 82, 164))
    draw.text((22, 24), "PS", fill=(255, 255, 255))
    return im


def make_menu():
    from pystray import Menu, MenuItem

    def _items():
        yield MenuItem("Open Studio", lambda icon, item: open_studio(wait=True), default=True)

        if state == "running":
            label = f"● Running ({URL})"
        elif state == "starting":
            label = "◷ Starting..."
        elif state == "stopped":
            label = "○ Stopped"
        elif state == "error":
            label = "⚠ Server Error (check log)"
        else:
            label = "Promo Studio"
        yield MenuItem(label, None, enabled=False)

        yield Menu.SEPARATOR

        if state in ("running", "starting"):
            yield MenuItem("Restart Server", lambda icon, item: restart_server())
            yield MenuItem("Stop Server", lambda icon, item: stop_server())
        else:
            yield MenuItem("Start Server", lambda icon, item: start_server())

        yield MenuItem("View Server Log", lambda icon, item: view_server_log())

        yield Menu.SEPARATOR
        yield MenuItem("Quit Tray", lambda icon, item: on_quit())

    return Menu(_items)


DESKTOP_ENTRY = """[Desktop Entry]
Name=Promo Studio
Comment=Promo Studio tray launcher (beautiful-quran)
Exec={exe} {tray}
Path={here}
Icon={icon}
Terminal=false
Type=Application
Categories=AudioVideo;Video;
StartupNotify=true
"""


def install_desktop():
    """Write the launcher with this checkout's paths: the app menu gets it,
    plus the Desktop for double-clicking when one exists."""
    entry = DESKTOP_ENTRY.format(exe=sys.executable, tray=os.path.join(HERE, "tray_app.py"),
                                 here=HERE, icon=ICON_PATH if os.path.exists(ICON_PATH) else "video-display")
    apps = os.path.expanduser("~/.local/share/applications")
    os.makedirs(apps, exist_ok=True)
    dest = os.path.join(apps, "promo-studio.desktop")
    with open(dest, "w") as f:
        f.write(entry)
    print(f"installed {dest}")
    desktop = os.path.expanduser("~/Desktop")
    if os.path.isdir(desktop):
        copy = os.path.join(desktop, "promo-studio.desktop")
        shutil.copy(dest, copy)
        os.chmod(copy, 0o755)               # trusted, so it launches on double-click
        print(f"installed {copy}")


def main():
    global icon_ref

    if "--install" in sys.argv:
        install_desktop()
        return

    ipc_sock = check_single_instance()
    if not ipc_sock:
        sys.exit(0)

    try:
        import pystray
    except ImportError as e:
        print(f"Missing tray dependency: {e}", file=sys.stderr)
        sys.exit(1)

    atexit.register(stop_server)

    def _sig_handler(sig, frame):
        on_quit()
        sys.exit(0)

    signal.signal(signal.SIGINT, _sig_handler)
    signal.signal(signal.SIGTERM, _sig_handler)

    threading.Thread(target=ipc_listener, args=(ipc_sock,), daemon=True).start()
    threading.Thread(target=ticker, daemon=True).start()

    img = create_tray_image()
    icon_ref = pystray.Icon("Promo Studio", img, "Promo Studio (Starting...)", menu=make_menu())

    # Start server and queue browser opening
    start_server()
    open_studio(wait=True)

    icon_ref.run()


if __name__ == "__main__":
    main()
