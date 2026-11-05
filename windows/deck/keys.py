"""Keyboard shortcut text ("Ctrl+Shift+M") to Windows virtual-key codes, for the deck's key presses."""

_MODIFIERS = {"ctrl": 0x11, "control": 0x11, "shift": 0x10, "alt": 0x12, "win": 0x5B}
_NAMED = {
    "enter": 0x0D, "return": 0x0D, "esc": 0x1B, "escape": 0x1B, "tab": 0x09, "space": 0x20,
    "backspace": 0x08, "delete": 0x2E, "del": 0x2E, "insert": 0x2D, "home": 0x24, "end": 0x23,
    "pgup": 0x21, "pageup": 0x21, "pgdn": 0x22, "pagedown": 0x22,
    "left": 0x25, "up": 0x26, "right": 0x27, "down": 0x28, "printscreen": 0x2C,
    "playpause": 0xB3, "nexttrack": 0xB0, "prevtrack": 0xB1, "stopmedia": 0xB2,
    "volumeup": 0xAF, "volumedown": 0xAE, "volumemute": 0xAD,
}
# these need the "extended key" flag, or Windows sends the numpad version instead
EXTENDED = {0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x2D, 0x2E, 0x5B,
            0xB0, 0xB1, 0xB2, 0xB3, 0xAD, 0xAE, 0xAF}

MEDIA_VK = {"play_pause": 0xB3, "next": 0xB0, "prev": 0xB1, "stop": 0xB2,
            "vol_up": 0xAF, "vol_down": 0xAE, "vol_mute": 0xAD}


def parse_keys(text):
    """'Ctrl+Shift+M' -> ([modifier vks], main vk). One key alone ('F9', 'Enter') is fine.

    Raises ValueError for something that makes no sense."""
    parts = [p.strip().lower() for p in text.split("+") if p.strip()]
    if not parts:
        raise ValueError("empty shortcut")
    mods = []
    for p in parts[:-1]:
        if p not in _MODIFIERS:
            raise ValueError(f"unknown modifier '{p}'")
        if _MODIFIERS[p] not in mods:
            mods.append(_MODIFIERS[p])
    key = parts[-1]
    if key in _MODIFIERS and not mods:
        raise ValueError(f"'{key}' alone is not a shortcut")
    if len(key) == 1 and key.isalnum() and key.isascii():
        return mods, ord(key.upper())
    if key.startswith("f") and key[1:].isdigit() and 1 <= int(key[1:]) <= 24:
        return mods, 0x70 + int(key[1:]) - 1
    if key in _NAMED:
        return mods, _NAMED[key]
    raise ValueError(f"unknown key '{key}'")


_KEYSYM_NAMES = {"Return": "Enter", "Escape": "Esc", "Prior": "PgUp", "Next": "PgDn", "BackSpace": "Backspace",
                 "Delete": "Delete", "Insert": "Insert", "Home": "Home", "End": "End", "Tab": "Tab",
                 "space": "Space", "Left": "Left", "Right": "Right", "Up": "Up", "Down": "Down",
                 "Print": "PrintScreen"}
_MODIFIER_KEYSYMS = {"Control_L", "Control_R", "Shift_L", "Shift_R", "Alt_L", "Alt_R", "Super_L", "Super_R",
                     "Meta_L", "Meta_R", "Caps_Lock", "Num_Lock"}


def format_shortcut(keysym, ctrl=False, alt=False, shift=False, win=False):
    """What the editor's Record button writes: ('s', ctrl, shift) -> 'Ctrl+Shift+S'.

    None for a modifier key on its own or a key we cannot name (the user keeps pressing)."""
    if keysym in _MODIFIER_KEYSYMS:
        return None
    if keysym in _KEYSYM_NAMES:
        key = _KEYSYM_NAMES[keysym]
    elif len(keysym) == 1 and keysym.isalnum() and keysym.isascii():
        key = keysym.upper()
    elif keysym[:1] in "Ff" and keysym[1:].isdigit() and 1 <= int(keysym[1:]) <= 24:
        key = "F" + keysym[1:]
    else:
        return None
    mods = [n for n, on in (("Ctrl", ctrl), ("Alt", alt), ("Shift", shift), ("Win", win)) if on]
    return "+".join(mods + [key])
