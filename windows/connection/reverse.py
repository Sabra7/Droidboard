"""adb reverse: the phone on the cable reaches the laptop's ports through these tunnels."""
import subprocess
import time

from connection.adb import find_adb
from winproc import NO_WINDOW


class AdbReverse:
    """Keeps `adb reverse tcp:PORT tcp:PORT` in place (it is lost on replug)."""

    def __init__(self, port, enabled):
        self.port = port
        self.adb = find_adb() if enabled else None
        self.last_try = 0.0
        self.last_msg = None
        if enabled and not self.adb:
            print("[adb] adb not found in PATH - install Android Platform Tools "
                  "or run 'adb reverse' yourself.")

    def _usb_serials(self):
        """The phones plugged in with a cable. With several adb devices (a tablet over Wi-Fi too),
        plain `adb reverse` fails with "more than one device", so every command names its phone."""
        from connection.autostart import list_devices, usb_phones
        return usb_phones(list_devices(self.adb))

    def remove(self):
        """Take the tunnel down on exit so nothing is left behind."""
        if not self.adb:
            return
        for serial in self._usb_serials():
            try:
                subprocess.run([self.adb, "-s", serial, "reverse", "--remove", f"tcp:{self.port}"],
                               capture_output=True, timeout=5,
                               creationflags=NO_WINDOW)
            except (OSError, subprocess.TimeoutExpired):
                pass

    def ensure(self, force=False):
        if not self.adb:
            return
        now = time.monotonic()
        if not force and now - self.last_try < 3.0:
            return
        self.last_try = now
        try:
            serials = self._usb_serials()
            msg = "no phone on the cable" if not serials else "ok"
            for serial in serials:
                r = subprocess.run([self.adb, "-s", serial, "reverse", f"tcp:{self.port}", f"tcp:{self.port}"],
                                   capture_output=True, text=True, timeout=5,
                                   creationflags=NO_WINDOW)
                if r.returncode != 0:
                    msg = r.stderr.strip() or r.stdout.strip()
        except (OSError, subprocess.TimeoutExpired) as e:
            msg = str(e)
        if msg != self.last_msg:
            print(f"\n[adb] reverse tcp:{self.port}: {msg}")
            self.last_msg = msg


def keep_reversed(stop_event):
    """The deck and the camera reach the laptop over the cable too: keep their adb tunnels in place."""
    from camera.server import CAMERA_PORT
    from deck.server import DECK_PORT
    from screen.cast import SCREEN_PORT
    tunnels = [AdbReverse(p, True) for p in (DECK_PORT, CAMERA_PORT, SCREEN_PORT)]
    for t in tunnels:
        t.ensure(force=True)
    while not stop_event.wait(3.0):
        for t in tunnels:
            t.ensure()
    for t in tunnels:
        t.remove()
