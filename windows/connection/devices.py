"""Which devices are connected to the laptop right now, and for what.

Every server (deck, camera, screen) adds a connection here after it checked the pairing code and removes it
when the device leaves. Two tablets of the same model are told apart by the id the app made for itself.
"""
import threading
import time


class DeviceRegistry:
    def __init__(self):
        self._lock = threading.Lock()
        self._next = 1
        self._items = {}                 # token -> {kind, id, name, addr, since}
        self.listeners = []              # callables told about every change (a tray menu that wants to refresh)

    def add(self, kind, device_id, name, addr):
        """-> a token to give back to remove()."""
        with self._lock:
            token, self._next = self._next, self._next + 1
            self._items[token] = {"kind": kind, "id": device_id or addr, "name": name or addr, "addr": addr,
                                  "since": time.time()}
        self._changed()
        return token

    def remove(self, token):
        with self._lock:
            self._items.pop(token, None)
        self._changed()

    def _changed(self):
        for cb in list(self.listeners):
            try:
                cb()
            except Exception:
                pass

    def connections(self):
        with self._lock:
            return sorted(self._items.values(), key=lambda c: c["since"])

    def devices(self):
        """[{id, name, addr, kinds}] one entry per device, with everything it is connected for."""
        found = {}
        for c in self.connections():
            d = found.setdefault(c["id"], {"id": c["id"], "name": c["name"], "addr": c["addr"], "kinds": []})
            if c["kind"] not in d["kinds"]:
                d["kinds"].append(c["kind"])
        return list(found.values())

    def kind_count(self, kind):
        return sum(1 for c in self.connections() if c["kind"] == kind)


def describe(device):
    """One line for a menu: 'Galaxy Tab S10 FE: deck, screen'."""
    return f"{device['name']}: {', '.join(device['kinds'])}"
