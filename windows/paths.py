"""Where Droidboard keeps its files, run from source (python receiver.py) or built (Droidboard.exe).

Data the user owns (settings.json, deck.json, pairing.txt, logs/, an opus.dll they add) lives next to
receiver.py, or next to Droidboard.exe. Files that ship with the app (shared/icons.json) come from the
source tree, or from inside the exe.
"""
import os
import sys

FROZEN = getattr(sys, "frozen", False)          # set by PyInstaller
DATA = os.path.dirname(os.path.abspath(sys.executable if FROZEN else __file__))
_SHARED = os.path.join(getattr(sys, "_MEIPASS", ""), "shared") if FROZEN else os.path.join(DATA, "..", "shared")


def data(*name):
    """A file the user owns, e.g. data("settings.json")."""
    return os.path.join(DATA, *name)


def shared(*name):
    """A file that ships with the app, e.g. shared("icons.json")."""
    return os.path.join(_SHARED, *name)


def self_command(console=False):
    """The command that starts this program again: the exe, or Python with receiver.py.
    console: python.exe instead of pythonw.exe, so the output can be read."""
    if FROZEN:
        return [sys.executable]
    exe = sys.executable
    if console and exe.lower().endswith("pythonw.exe"):
        exe = exe[:-len("pythonw.exe")] + "python.exe"
    return [exe, os.path.join(DATA, "receiver.py")]
