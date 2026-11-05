"""Locate adb: PATH first, then the default Android SDK folder."""
import os
import shutil


def find_adb():
    found = shutil.which("adb")
    if found:
        return found
    sdk = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk",
                       "platform-tools", "adb.exe")
    return sdk if os.path.isfile(sdk) else None
