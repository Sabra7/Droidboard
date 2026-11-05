"""What the Diagnostics card checks in the background: adb, the phone on the cable, its tunnels, the virtual display,
the laptop's addresses and Windows Firewall."""
import subprocess
import threading

from connection import firewall
from connection.adb import find_adb
from connection.pairing import local_addresses
from ui import dashboard
from winproc import NO_WINDOW


class Checks:
    """Asks adb and Windows every few seconds, in its own thread (adb can take a second to answer)."""

    def __init__(self, stop_event):
        self.adb = find_adb()
        self.devices, self.reverse, self.virtual_display = None, set(), None
        self.addresses = []
        self.firewall = (None, {}, [])       # (rules, profiles on, network categories), see firewall.query
        self._firewall_at = 0                 # loops until the firewall is asked again (it takes ~2 s)
        self.wake = threading.Event()
        self.stop_event = stop_event
        threading.Thread(target=self._run, daemon=True).start()

    def _adb(self, *cmd):
        r = subprocess.run([self.adb, *cmd], capture_output=True, text=True, timeout=5, creationflags=NO_WINDOW)
        return r.stdout

    def usb_serial(self):
        ready = [s for s, state in (self.devices or []) if state == "device"]
        return ready[0] if ready else None

    def _run(self):
        while not self.stop_event.is_set():
            try:
                from screen import vdd
                self.virtual_display = vdd.find()
            except Exception:
                self.virtual_display = None
            self.addresses = local_addresses()
            if self._firewall_at <= 0:
                self.firewall = firewall.query()
                self._firewall_at = 12        # every minute
            self._firewall_at -= 1
            if self.adb:
                try:
                    self.devices = dashboard.parse_adb_devices(self._adb("devices"))
                    serial = self.usb_serial()
                    self.reverse = (dashboard.parse_reverse_list(self._adb("-s", serial, "reverse", "--list"))
                                    if serial else set())
                except (OSError, subprocess.TimeoutExpired):
                    self.devices, self.reverse = None, set()
            self.wake.wait(5)
            self.wake.clear()

    def allow_firewall(self):
        firewall.allow()
        self._firewall_at = 0        # look again soon after the user answers the prompt
        self.wake.set()

    def reverse_all(self):
        """adb reverse for every port (the receiver keeps them, this is for when that went wrong)."""
        serial = self.usb_serial()
        if not serial:
            print("[adb] reverse: no phone on the cable")
            return
        for port in (dashboard.AUDIO_PORT, dashboard.DECK_PORT, dashboard.CAMERA_PORT, dashboard.SCREEN_PORT):
            r = subprocess.run([self.adb, "-s", serial, "reverse", f"tcp:{port}", f"tcp:{port}"],
                               capture_output=True, text=True, timeout=5, creationflags=NO_WINDOW)
            print(f"[adb] reverse tcp:{port}: " + ("ok" if r.returncode == 0
                                                   else (r.stderr.strip() or "failed")))
        self.wake.set()
