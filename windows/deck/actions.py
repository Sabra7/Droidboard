"""Runs a deck button's action. Windows calls go through pywin32; WindowsBackend is swapped in tests."""
from deck.keys import EXTENDED, MEDIA_VK, parse_keys
from deck.layout import DANGEROUS_SYSTEM


SYSTEM_LABELS = {
    "lock": "Lock the PC", "sleep": "Sleep", "hibernate": "Hibernate", "display_off": "Turn the screen off",
    "sign_out": "Sign out", "restart": "Restart", "shutdown": "Shut down", "task_manager": "Task Manager",
    "settings": "Windows Settings", "screenshot": "Screenshot tool", "show_desktop": "Show the desktop",
}


class WindowsBackend:
    """Keyboard, shell and power calls through pywin32 (no administrator rights needed)."""

    def key_combo(self, mods, vk):
        import win32api
        import win32con

        def tap(code, up):
            flags = (win32con.KEYEVENTF_EXTENDEDKEY if code in EXTENDED else 0) | (win32con.KEYEVENTF_KEYUP if up else 0)
            win32api.keybd_event(code, win32api.MapVirtualKey(code, 0), flags, 0)

        for m in mods:
            tap(m, False)
        tap(vk, False)
        tap(vk, True)
        for m in reversed(mods):
            tap(m, True)

    def type_text(self, text):
        """Types Unicode text. pywin32 has no SendInput, so this one call goes through ctypes."""
        import ctypes
        from ctypes import wintypes

        class KEYBDINPUT(ctypes.Structure):
            _fields_ = [("wVk", wintypes.WORD), ("wScan", wintypes.WORD), ("dwFlags", wintypes.DWORD),
                        ("time", wintypes.DWORD), ("dwExtraInfo", ctypes.c_size_t)]

        class MOUSEINPUT(ctypes.Structure):          # only here so INPUT gets its real size
            _fields_ = [("dx", wintypes.LONG), ("dy", wintypes.LONG), ("mouseData", wintypes.DWORD),
                        ("dwFlags", wintypes.DWORD), ("time", wintypes.DWORD),
                        ("dwExtraInfo", ctypes.c_size_t)]

        class _U(ctypes.Union):
            _fields_ = [("ki", KEYBDINPUT), ("mi", MOUSEINPUT)]

        class INPUT(ctypes.Structure):
            _fields_ = [("type", wintypes.DWORD), ("u", _U)]

        UNICODE, KEYUP = 0x4, 0x2
        units = []
        data = text.replace("\r\n", "\n").encode("utf-16-le")
        for i in range(0, len(data), 2):
            code = int.from_bytes(data[i:i + 2], "little")
            for flags in (UNICODE, UNICODE | KEYUP):
                units.append(INPUT(1, _U(ki=KEYBDINPUT(0, code, flags, 0, 0))))
        inputs = (INPUT * len(units))(*units)
        ctypes.windll.user32.SendInput(len(units), inputs, ctypes.sizeof(INPUT))

    def open_target(self, target, args=""):
        """A website, file, folder, program or an app from the Start menu (shell:AppsFolder\\...)."""
        import win32api
        import win32con
        win32api.ShellExecute(0, "open", target, args or None, None, win32con.SW_SHOWNORMAL)

    def system(self, what):
        import win32api
        import win32con
        import win32gui

        def run(exe, args=None):
            win32api.ShellExecute(0, "open", exe, args, None, win32con.SW_HIDE)

        if what == "lock":
            run("rundll32.exe", "user32.dll,LockWorkStation")
        elif what == "sleep":
            win32api.SetSystemPowerState(True, False)
        elif what == "hibernate":
            win32api.SetSystemPowerState(False, False)
        elif what == "display_off":
            win32gui.PostMessage(win32con.HWND_BROADCAST, win32con.WM_SYSCOMMAND, win32con.SC_MONITORPOWER, 2)
        elif what == "sign_out":
            win32api.ExitWindowsEx(win32con.EWX_LOGOFF, 0)
        elif what == "restart":
            run("shutdown.exe", "/r /t 5")
        elif what == "shutdown":
            run("shutdown.exe", "/s /t 5")
        elif what == "task_manager":
            self.open_target("taskmgr.exe")
        elif what == "settings":
            self.open_target("ms-settings:")
        elif what == "screenshot":
            self.key_combo([0x5B, 0x10], ord("S"))
        elif what == "show_desktop":
            self.key_combo([0x5B], ord("D"))
        else:
            raise ValueError(f"unknown system action '{what}'")


def execute(action, hooks=None, backend=None):
    """Runs one validated action. Returns (ok, message); never raises.

    hooks: "mute" toggles the Windows-side mute, "confirm" (text -> bool) asks on this laptop first."""
    backend = backend or WindowsBackend()
    hooks = hooks or {}
    kind = action.get("type")
    try:
        if kind == "media":
            backend.key_combo([], MEDIA_VK[action["key"]])
        elif kind == "hotkey":
            mods, vk = parse_keys(action["keys"])
            backend.key_combo(mods, vk)
        elif kind == "text":
            backend.type_text(action["text"])
        elif kind == "open":
            backend.open_target(action["target"], action.get("args", ""))
        elif kind == "system":
            what = action["what"]
            confirm = hooks.get("confirm")
            if what in DANGEROUS_SYSTEM and confirm and not confirm(f"{SYSTEM_LABELS[what]} now?"):
                return False, "cancelled on the laptop"
            backend.system(what)
        elif kind == "phonemic":
            hook = hooks.get(action["what"])
            if hook is None:
                return False, "not available here"
            hook()
        else:
            return False, f"unknown action '{kind}'"
    except Exception as e:             # a bad path or a missing program must not kill the deck
        return False, (str(e) or type(e).__name__)[:120]
    return True, ""
