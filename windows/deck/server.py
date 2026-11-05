"""The deck's TCP server: the tablet connects, gets the layout, sends button presses and edits.

One JSON object per line, in both directions. The first line from the tablet must be
{"t": "hello", "code": "123456", "device": {"id": "...", "name": "..."}}; a connection from this
laptop itself (adb reverse) needs no code.

  tablet -> laptop   hello | get | press {id} | save {deck}
  laptop -> tablet   deck {deck} | result {id, ok, msg} | error {msg, fatal?}
"""
import json
import threading
import time

from connection.pairing import is_local, listen_socket, peer_host
from deck.actions import execute
from deck.layout import describe, new_actions, validate_deck


DECK_PORT = 50508
MAX_LINE = 64 * 1024
MAX_FAILS, LOCK_SECONDS = 5, 60


class AttemptLimiter:
    """Stops guessing of the 6-digit code: MAX_FAILS wrong codes lock that address for a minute."""

    def __init__(self, clock=time.monotonic):
        self.clock = clock
        self._fails = {}
        self._locked_until = {}

    def blocked(self, addr):
        until = self._locked_until.get(addr)
        if until is None:
            return False
        if self.clock() >= until:
            del self._locked_until[addr]
            self._fails.pop(addr, None)
            return False
        return True

    def failed(self, addr):
        self._fails[addr] = self._fails.get(addr, 0) + 1
        if self._fails[addr] >= MAX_FAILS:
            self._locked_until[addr] = self.clock() + LOCK_SECONDS

    def ok(self, addr):
        self._fails.pop(addr, None)


def line(obj):
    return (json.dumps(obj, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")


def send_line(conn, obj):
    """Best effort: a tablet that is gone is noticed by the reading side."""
    try:
        conn.sendall(line(obj))
    except OSError:
        pass


def read_hello(conn, addr, limiter, code, max_len):
    """The first line of a camera or screen connection, read byte by byte (the stream follows right after).

    Returns the hello dict, or None when the connection should end: a locked address or a wrong
    code also gets a fatal error line. A connection from this laptop itself needs no code.
    """
    if limiter.blocked(addr):
        send_line(conn, {"t": "error", "msg": "too many wrong codes, wait a minute", "fatal": True})
        return None
    hello = bytearray()
    while not hello.endswith(b"\n"):
        b = conn.recv(1)
        if not b or len(hello) > max_len:
            return None
        hello += b
    msg = json.loads(hello)
    if msg.get("t") != "hello":
        return None
    if not (is_local(addr) or msg.get("code") == code):
        limiter.failed(addr)
        send_line(conn, {"t": "error", "msg": "wrong pairing code", "fatal": True})
        return None
    limiter.ok(addr)
    return msg


class DeckServer:
    def __init__(self, store, code, hooks=None, backend=None, host="127.0.0.1", port=DECK_PORT,
                 approver=None, registry=None):
        self.registry = registry          # devices.DeviceRegistry: who is connected
        # approver(text) -> bool asks on the laptop before a tablet may add or change an action;
        # None approves everything (tests, and edits made on the laptop itself)
        self.approver = approver
        self.store, self.code = store, code
        self.hooks = hooks if hooks is not None else {}
        self.backend = backend
        self.host, self.port = host, port
        self.limiter = AttemptLimiter()
        self.clients = []
        self._lock = threading.Lock()
        self._sock = None
        self._stop = threading.Event()

    # -- lifecycle
    def start(self):
        s = listen_socket(self.host, self.port)
        s.listen(4)
        self.port = s.getsockname()[1]
        self._sock = s
        threading.Thread(target=self._accept, daemon=True, name="deck-accept").start()
        return self

    def stop(self):
        self._stop.set()
        if self._sock:
            self._sock.close()
        with self._lock:
            for c in self.clients:
                try:
                    c.close()
                except OSError:
                    pass

    def _accept(self):
        while not self._stop.is_set():
            try:
                conn, addr = self._sock.accept()
            except OSError:
                return
            threading.Thread(target=self._serve, args=(conn, peer_host(addr[0])), daemon=True,
                             name="deck-client").start()

    # -- one tablet
    def broadcast_deck(self):
        deck = self.store.get()
        with self._lock:
            targets = list(self.clients)
        for c in targets:
            send_line(c, {"t": "deck", "deck": deck})

    def _serve(self, conn, addr):
        conn.settimeout(30)              # the hello must come quickly; later the tablet pings
        reader = conn.makefile("rb")
        token = None
        try:
            hello = self._hello(conn, reader, addr)
            if not hello:
                return
            if self.registry is not None:
                from connection.device_profile import parse_device
                d = parse_device(hello)
                token = self.registry.add("deck", d["id"], d["name"], addr)
            with self._lock:
                self.clients.append(conn)
            send_line(conn, {"t": "deck", "deck": self.store.get()})
            conn.settimeout(None)
            while True:
                raw = reader.readline(MAX_LINE + 1)
                if not raw or len(raw) > MAX_LINE:
                    return
                self._handle(conn, raw)
        except (OSError, ValueError):
            pass
        finally:
            if token is not None:
                self.registry.remove(token)
            with self._lock:
                if conn in self.clients:
                    self.clients.remove(conn)
            try:
                conn.close()
            except OSError:
                pass

    def _hello(self, conn, reader, addr):
        if self.limiter.blocked(addr):
            send_line(conn, {"t": "error", "msg": "too many wrong codes, wait a minute", "fatal": True})
            return None
        raw = reader.readline(MAX_LINE + 1)
        try:
            msg = json.loads(raw)
        except ValueError:
            return None
        if not isinstance(msg, dict) or msg.get("t") != "hello":
            return None
        if is_local(addr) or msg.get("code") == self.code:
            self.limiter.ok(addr)
            return msg                    # the hello itself: it also says who is calling
        self.limiter.failed(addr)
        send_line(conn, {"t": "error", "msg": "wrong pairing code", "fatal": True})
        return None

    def _handle(self, conn, raw):
        try:
            msg = json.loads(raw)
        except ValueError:
            return
        kind = msg.get("t") if isinstance(msg, dict) else None
        if kind == "get":
            send_line(conn, {"t": "deck", "deck": self.store.get()})
        elif kind == "press":
            button = self.store.find(msg.get("id"))
            if button is None:
                send_line(conn, {"t": "result", "id": msg.get("id"), "ok": False, "msg": "no such button"})
                return
            ok, text = execute(button["action"], self.hooks, self.backend)
            send_line(conn, {"t": "result", "id": button["id"], "ok": ok, "msg": text})
        elif kind == "save":
            try:
                deck = validate_deck(msg.get("deck"))
            except ValueError as e:
                send_line(conn, {"t": "error", "msg": str(e)})
                return
            added = new_actions(self.store.get(), deck)
            if added and self.approver:
                # asking blocks until someone answers, so do it away from this tablet's reader
                threading.Thread(target=self._approve_and_save, args=(conn, deck, added), daemon=True).start()
                return
            self.store.set(deck)
            self.broadcast_deck()

    def _approve_and_save(self, conn, deck, added):
        lines = "\n".join("- " + (b["label"] or b["id"]) + ": " + describe(b) for b in added[:8])
        more = f"\n... and {len(added) - 8} more" if len(added) > 8 else ""
        question = ("The tablet wants to add or change these deck buttons:\n\n" + lines + more
                    + "\n\nAllow it?")
        if self.approver(question):
            self.store.set(deck)
            self.broadcast_deck()
        else:
            send_line(conn, {"t": "error", "msg": "denied on the laptop"})
            send_line(conn, {"t": "deck", "deck": self.store.get()})       # the tablet drops its edit

    def apply_local(self, deck):
        """An edit made on this laptop (the editor window): no approval, everyone gets the new layout."""
        self.store.set(deck)
        self.broadcast_deck()
