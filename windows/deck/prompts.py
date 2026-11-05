"""Questions asked on the laptop screen (win32 message boxes), for things the tablet must not do alone."""
import threading


_busy = threading.Lock()


def ask_yes_no(text, title="Droidboard"):
    """True when the user clicks Yes. One question at a time; a second one gets False at once."""
    if not _busy.acquire(blocking=False):
        return False
    try:
        import win32api
        import win32con
        flags = (win32con.MB_YESNO | win32con.MB_ICONQUESTION | win32con.MB_TOPMOST
                 | win32con.MB_SETFOREGROUND | win32con.MB_DEFBUTTON2)
        return win32api.MessageBox(0, text, title, flags) == win32con.IDYES
    finally:
        _busy.release()
