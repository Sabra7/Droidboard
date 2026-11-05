"""Several phones for the mic: one is heard (active), the others stay connected and wait (standby).

The first phone to connect is heard. Another one waits until the user picks it (window or tray), or until
the heard phone goes away; then the phone that has waited longest takes over.
"""
import threading
import time


class MicHub:
    def __init__(self, clock=time.time):
        self._lock = threading.Lock()
        self._phones = {}            # token -> {"name", "transport", "since"}
        self._active = None
        self._next = 1
        self._clock = clock

    def join(self, name, transport):
        """A phone connected. -> its token. It is heard if no other phone is."""
        with self._lock:
            token = self._next
            self._next += 1
            self._phones[token] = {"name": name, "transport": transport, "since": self._clock()}
            if self._active is None:
                self._active = token
            return token

    def leave(self, token):
        """A phone went away; if it was heard, the one that waited longest is heard now."""
        with self._lock:
            self._phones.pop(token, None)
            if self._active == token:
                waiting = sorted(self._phones, key=lambda t: self._phones[t]["since"])
                self._active = waiting[0] if waiting else None

    def use(self, token):
        """The user picked this phone."""
        with self._lock:
            if token in self._phones:
                self._active = token

    def is_active(self, token):
        with self._lock:
            return self._active == token

    def has_active(self):
        with self._lock:
            return self._active is not None

    def phones(self):
        """[{token, name, transport, since, active}], the heard one first, then by how long they waited."""
        with self._lock:
            out = [dict(p, token=t, active=(t == self._active)) for t, p in self._phones.items()]
        return sorted(out, key=lambda p: (not p["active"], p["since"]))


def phone_name(devices, peer, transport):
    """A name for a phone on the mic: the name its deck, camera or screen gave from the same address, else the way."""
    named = [d["name"] for d in devices if d.get("addr") == peer]
    if len(named) == 1:
        return named[0]
    return f"{transport} phone" if transport == "USB" else f"Phone at {peer}"
