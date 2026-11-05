"""Start with Windows: one value under HKCU\\...\\Run (no administrator rights needed)."""
import subprocess
import sys


RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
VALUE = "Droidboard"
LEGACY_VALUE = "PhoneMic"      # the name before the rename; removed when found


def run_command(executable, script, args):
    """The command line stored in the Run key. pythonw (no console window) when it exists.
    script: receiver.py, or None for Droidboard.exe (it is the program itself)."""
    if executable.lower().endswith("python.exe"):
        pythonw = executable[:-len("python.exe")] + "pythonw.exe"
        import os
        if os.path.isfile(pythonw):
            executable = pythonw
    args = list(args)
    if "--gui" not in args:
        args.append("--gui")     # started by Windows there is no console: it needs its own window
    return subprocess.list2cmdline([executable] + ([script] if script else []) + args)


def is_enabled():
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as key:
            for name in (VALUE, LEGACY_VALUE):
                try:
                    winreg.QueryValueEx(key, name)
                    return True
                except FileNotFoundError:
                    pass
        return False
    except (OSError, ImportError):
        return False


def set_enabled(on, script, args):
    """Adds or removes the Run entry. Returns True when it worked."""
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as key:
            for name in (VALUE, LEGACY_VALUE):
                try:
                    winreg.DeleteValue(key, name)
                except FileNotFoundError:
                    pass
            if on:
                winreg.SetValueEx(key, VALUE, 0, winreg.REG_SZ,
                                  run_command(sys.executable, script, args))
        return True
    except (OSError, ImportError):
        return False
