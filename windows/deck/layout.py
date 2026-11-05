"""The button deck (a Stream Deck on the tablet): the layout, its validation and where it is saved.

Pure data, no sockets and no Windows calls, so it can be tested. The layout lives on the laptop
(windows/deck.json) because the actions run here; the tablet reads it and sends edits back.
"""
import json
import os
import threading

import paths
from ui.icons import migrate_icon


PATH = paths.data("deck.json")

MAX_BUTTONS = 48
MIN_COLS, MAX_COLS = 2, 8
COLORS = ("slate", "blue", "green", "amber", "red", "purple", "pink", "teal")

MEDIA_KEYS = ("play_pause", "next", "prev", "stop", "vol_up", "vol_down", "vol_mute")
SYSTEM_ACTIONS = ("lock", "sleep", "hibernate", "display_off", "sign_out", "restart", "shutdown",
                  "task_manager", "settings", "screenshot", "show_desktop")
DANGEROUS_SYSTEM = ("sign_out", "restart", "shutdown")     # the laptop asks before these run
PHONEMIC_ACTIONS = ("mute",)

# action type -> (field, what it holds, max length)
_TEXT_FIELD = {"hotkey": ("keys", 60), "text": ("text", 500), "open": ("target", 500)}
MAX_ARGS = 300
_CHOICE_FIELD = {"media": ("key", MEDIA_KEYS), "system": ("what", SYSTEM_ACTIONS),
                 "phonemic": ("what", PHONEMIC_ACTIONS)}


def default_deck():
    def b(i, label, icon, color, **action):
        return {"id": i, "label": label, "icon": icon, "color": color, "action": action}

    return {"cols": 4, "buttons": [
        b("mic-mute", "Mute mic", "mic", "red", type="phonemic", what="mute"),
        b("play", "Play / Pause", "circle-play", "green", type="media", key="play_pause"),
        b("prev", "Previous", "skip-back", "slate", type="media", key="prev"),
        b("next", "Next", "skip-forward", "slate", type="media", key="next"),
        b("vol-down", "Volume -", "volume-1", "blue", type="media", key="vol_down"),
        b("vol-up", "Volume +", "volume-2", "blue", type="media", key="vol_up"),
        b("vol-mute", "Mute sound", "volume-x", "amber", type="media", key="vol_mute"),
        b("lock", "Lock PC", "lock", "purple", type="system", what="lock"),
    ]}


def validate_action(action):
    """Returns a clean copy of an action dict, or raises ValueError."""
    if not isinstance(action, dict):
        raise ValueError("action must be an object")
    kind = action.get("type")
    if kind in _TEXT_FIELD:
        field, limit = _TEXT_FIELD[kind]
        value = action.get(field)
        if not isinstance(value, str) or not value.strip():
            raise ValueError(f"{kind}: '{field}' is empty")
        if len(value) > limit:
            raise ValueError(f"{kind}: '{field}' is too long")
        if kind == "hotkey":
            from deck.keys import parse_keys
            parse_keys(value)            # raises ValueError for a shortcut that makes no sense
        clean = {"type": kind, field: value if kind == "text" else value.strip()}
        if kind == "open":                       # optional command line arguments for a program
            args = action.get("args", "")
            if not isinstance(args, str) or len(args) > MAX_ARGS:
                raise ValueError("open: 'args' is too long")
            if args.strip():
                clean["args"] = args.strip()
        return clean
    if kind in _CHOICE_FIELD:
        field, choices = _CHOICE_FIELD[kind]
        if action.get(field) not in choices:
            raise ValueError(f"{kind}: '{field}' must be one of {', '.join(choices)}")
        return {"type": kind, field: action[field]}
    raise ValueError(f"unknown action type '{kind}'")


def validate_deck(deck):
    """Returns a clean copy of a deck dict, or raises ValueError with a message for the tablet."""
    if not isinstance(deck, dict):
        raise ValueError("deck must be an object")
    cols = deck.get("cols", 4)
    if not isinstance(cols, int) or isinstance(cols, bool) or not MIN_COLS <= cols <= MAX_COLS:
        raise ValueError(f"cols must be {MIN_COLS} to {MAX_COLS}")
    raw = deck.get("buttons")
    if not isinstance(raw, list) or len(raw) > MAX_BUTTONS:
        raise ValueError(f"at most {MAX_BUTTONS} buttons")
    buttons, seen = [], set()
    for item in raw:
        if not isinstance(item, dict):
            raise ValueError("a button must be an object")
        bid = item.get("id")
        if not isinstance(bid, str) or not bid or len(bid) > 40 or bid in seen:
            raise ValueError("every button needs its own id")
        seen.add(bid)
        label, icon = item.get("label", ""), item.get("icon", "")
        if not isinstance(label, str) or len(label) > 24 or not isinstance(icon, str) or len(icon) > 40:
            raise ValueError("label is at most 24 characters, icon is an icon name")
        icon = migrate_icon(icon)            # an old emoji becomes the matching icon name
        color = item.get("color", "slate")
        if color not in COLORS:
            raise ValueError(f"color must be one of {', '.join(COLORS)}")
        buttons.append({"id": bid, "label": label, "icon": icon, "color": color,
                        "action": validate_action(item.get("action"))})
    return {"cols": cols, "buttons": buttons}


class DeckStore:
    """The deck on disk. A missing or damaged file gives the default deck."""

    def __init__(self, path=PATH):
        self.path = path
        self._lock = threading.Lock()
        try:
            with open(path, encoding="utf-8") as f:
                self._deck = validate_deck(json.load(f))
        except (OSError, ValueError):
            self._deck = default_deck()

    def get(self):
        with self._lock:
            return json.loads(json.dumps(self._deck))

    def find(self, button_id):
        with self._lock:
            return next((b for b in self._deck["buttons"] if b["id"] == button_id), None)

    def set(self, deck):
        clean = validate_deck(deck)
        with self._lock:
            self._deck = clean
            tmp = self.path + ".tmp"
            try:
                with open(tmp, "w", encoding="utf-8") as f:
                    json.dump(clean, f, indent=2, ensure_ascii=False)
                os.replace(tmp, self.path)
            except OSError:
                pass           # a read-only folder must not stop the deck
        return self.get()


def describe(button):
    """One line for the approval question: what a button would do."""
    a = button["action"]
    kind = a["type"]
    if kind == "open":
        return f'Open {a["target"]}' + (f' {a["args"]}' if a.get("args") else "")
    if kind == "hotkey":
        return f'Press {a["keys"]}'
    if kind == "text":
        return f'Type "{a["text"][:60]}"'
    if kind == "system":
        return f'System: {a["what"].replace("_", " ")}'
    if kind == "media":
        return f'Media: {a["key"].replace("_", " ")}'
    return f'Droidboard: {a["what"]}'


def new_actions(old, new):
    """Buttons of `new` whose action is not the same as the one with that id in `old` (or that are new).

    Names, icons, colours, order and columns are not actions: changing those needs no approval."""
    before = {b["id"]: b["action"] for b in old["buttons"]}
    return [b for b in new["buttons"] if before.get(b["id"]) != b["action"]]
