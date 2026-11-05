"""Running Windows command-line tools (adb, netsh, tasklist, DisplaySwitch ...) from the app."""
import subprocess

# creationflags for subprocess: no console window flashes up under pythonw or the exe (0 off Windows)
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)
