"""The virtual display: a real extra monitor for Windows that exists only while the tablet shows it.

It comes from the open-source Virtual Display Driver (MIT, https://github.com/VirtualDrivers/Virtual-Display-Driver),
installed in C:\\VirtualDisplayDriver. The driver adds a monitor that Windows treats like a plugged-in screen.
Windows keeps it "cloned" with the main screen until it is told to extend; this module extends it when the
tablet connects and, if the laptop had one screen before, puts things back when the tablet leaves.
No administrator rights are needed once the driver is installed.
"""
import os
import subprocess
import time

from winproc import NO_WINDOW


MARK = "Virtual Display Driver"
SWITCH = os.path.join(os.environ.get("WINDIR", r"C:\Windows"), "System32", "DisplaySwitch.exe")
ATTACHED = 0x1                     # DISPLAY_DEVICE_ATTACHED_TO_DESKTOP


def best_mode(modes, width, height, hz=60):
    """The (w, h, hz) to use for a tablet of width x height. `modes` are the (w, h, hz) the display offers.

    Same size and the nearest refresh rate wins; else the biggest mode with the shape closest to the tablet's
    (so the picture fills it and touch lines up). None when there are no modes."""
    modes = list(modes)
    if not modes:
        return None
    same = [m for m in modes if (m[0], m[1]) == (width, height)]
    if same:
        return min(same, key=lambda m: abs(m[2] - hz))
    ratio = width / height
    off = {m: abs(m[0] / m[1] - ratio) for m in modes}
    closest = min(off.values())
    pool = [m for m in modes if off[m] <= closest + 0.02]            # the shapes that are as close as the best one
    biggest = max(pool, key=lambda m: m[0] * m[1])
    return min((m for m in pool if (m[0], m[1]) == (biggest[0], biggest[1])), key=lambda m: abs(m[2] - hz))


def _devices():
    """Every display adapter. Windows only answers about a display after the whole list has been asked for."""
    import win32api
    found, i = [], 0
    while True:
        try:
            found.append(win32api.EnumDisplayDevices(None, i))
        except Exception:
            return found
        i += 1


def find():
    """Device name of the virtual display (like \\\\.\\DISPLAY16), or None when the driver is not installed."""
    return next((d.DeviceName for d in _devices() if MARK in d.DeviceString), None)


def _attached(device):
    return any(d.DeviceName == device and d.StateFlags & ATTACHED for d in _devices())


def _other_screens(device):
    """How many other displays are on the desktop right now."""
    return sum(1 for d in _devices() if d.StateFlags & ATTACHED and d.DeviceName != device)


def _modes(device):
    import win32api
    out, i = [], 0
    while True:
        try:
            m = win32api.EnumDisplaySettings(device, i)
        except Exception:
            return out
        out.append(m)
        i += 1


def _switch(arg):
    subprocess.run([SWITCH, arg], timeout=20, creationflags=NO_WINDOW)


def _wait(condition, seconds):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        if condition():
            return True
        time.sleep(0.3)
    return condition()


class VirtualDisplay:
    """extend() when a tablet connects, release() when it leaves."""

    def __init__(self):
        self.device = None
        self._put_back = False          # we extended it and the laptop had one screen: go back to that after

    def extend(self, width, height, hz=60):
        """-> (ok, note, device name). The monitor is on the desktop, as big as the tablet if it can be."""
        device = find()
        if device is None:
            return False, "Windows has no virtual display driver, so this mirrors the main screen", None
        try:
            if not _attached(device):
                self._put_back = _other_screens(device) <= 1
                _switch("/extend")
                if not _wait(lambda: _attached(device), 12):
                    return False, "Windows did not extend to the virtual display, so this mirrors the main screen", None
        except Exception as e:
            self.device = device
            self.release()                           # do not leave Windows extended after a failure
            return False, f"the virtual display failed ({e}), so this mirrors the main screen", None
        self.device = device
        note = ""
        try:
            self._set_mode(device, width, height, hz)
        except Exception as e:                       # it works at the size it has: say so, do not stop
            note = f"the virtual display could not be set to the tablet's size ({e}); the picture is scaled"
        return True, note, device

    def _set_mode(self, device, width, height, hz):
        """Make the virtual monitor the tablet's size. Raises when Windows refuses or it does not stick."""
        import win32api
        import win32con
        # right after the extend Windows answers with an empty list for a moment: ask until it has sizes
        modes = []
        end = time.monotonic() + 6
        while not modes and time.monotonic() < end:
            _devices()
            modes = _modes(device)
            if not modes:
                time.sleep(0.3)
        if not modes:
            raise RuntimeError("it offers no sizes yet")
        want = best_mode([(m.PelsWidth, m.PelsHeight, m.DisplayFrequency) for m in modes], width, height, hz)
        if want is None:
            return

        def current():
            c = win32api.EnumDisplaySettings(device, win32con.ENUM_CURRENT_SETTINGS)
            return (c.PelsWidth, c.PelsHeight, c.DisplayFrequency), (c.Position_x, c.Position_y)

        try:
            size, position = current()
        except Exception:
            size, position = None, (0, 0)
        if size == want:
            return
        dm = next(m for m in modes if (m.PelsWidth, m.PelsHeight, m.DisplayFrequency) == want)
        # the monitor must keep its place on the desktop, or it jumps to the corner of the main screen
        dm.Fields |= (win32con.DM_PELSWIDTH | win32con.DM_PELSHEIGHT | win32con.DM_DISPLAYFREQUENCY
                      | win32con.DM_POSITION)
        dm.Position_x, dm.Position_y = position
        code = win32api.ChangeDisplaySettingsEx(device, dm, win32con.CDS_UPDATEREGISTRY)
        if code != 0:
            raise RuntimeError(f"Windows refused {want[0]}x{want[1]} (code {code})")
        if not _wait(lambda: current()[0] == want, 4):
            raise RuntimeError(f"the monitor stayed at {current()[0][0]}x{current()[0][1]}")

    def release(self):
        """The tablet left. Back to "PC screen only" if that is what the laptop had before."""
        if self.device is not None and self._put_back:
            try:
                _switch("/internal")
            except Exception:
                pass
        self.device, self._put_back = None, False

    def cleanup_stale(self):
        """At start-up: a virtual display left on the desktop by a crashed session is switched off again."""
        device = find()
        if device is not None and _attached(device) and _other_screens(device) <= 1:
            try:
                _switch("/internal")
                return True
            except Exception:
                pass
        return False
