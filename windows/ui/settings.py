"""Settings of the Windows app, saved in windows/settings.json (survive a restart)."""
import json
import os
import threading

import paths


PATH = paths.data("settings.json")

DEFAULTS = {
    "auto_start_phone": True,    # when the phone is plugged in over USB, start the app on it
    "output_device": "",         # name of the sound device to play into; "" = the --device option
    "latency_preset": "",        # "low" / "balanced" / "stable"; "" = the --buffer-ms option
    "notifications": True,       # Windows notification when the phone connects or disconnects
    "camera_size": "1280x720",   # picture size of the virtual webcam (the tablet as a camera)
    "camera_fit": "fill",        # "fill" crops the picture to the size, "fit" adds black bars
    "camera_fps": 30,
    "screen_fps": 60,            # the tablet as a second screen: frames per second
    "screen_bitrate_mbps": 20,   # ... video bit rate in Mbit/s
    "screen_cursor": True,       # ... draw the Windows mouse pointer into the picture
    "screen_display": "",        # ... which display to send: part of its name (e.g. "DISPLAY2"); "" = the main one
    "camera_always_on": True,     # keep the virtual webcam in apps' camera lists, showing "waiting", while no tablet sends
}


class Settings:
    """A small dict saved to disk on every change. Unknown or missing keys fall back to DEFAULTS."""

    def __init__(self, path=PATH):
        self.path = path
        self._lock = threading.Lock()
        self._data = dict(DEFAULTS)
        try:
            with open(path, encoding="utf-8") as f:
                saved = json.load(f)
            if isinstance(saved, dict):
                self._data.update({k: v for k, v in saved.items() if k in DEFAULTS})
        except FileNotFoundError:
            self._save()    # first run: write the file so it can be found and edited
        except (OSError, ValueError):
            pass    # a damaged file: start from the defaults

    def get(self, key):
        with self._lock:
            return self._data[key]

    def set(self, key, value):
        if key not in DEFAULTS:
            raise KeyError(key)
        with self._lock:
            self._data[key] = value
            self._save()

    def _save(self):
        tmp = self.path + ".tmp"
        try:
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(self._data, f, indent=2)
            os.replace(tmp, self.path)
        except OSError:
            pass    # a read-only folder must not stop the app
