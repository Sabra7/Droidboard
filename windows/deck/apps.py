"""Programs the user can pick for an "Open" button: everything in the Start menu (desktop programs and Store apps)."""
import json
import subprocess
import threading

from winproc import NO_WINDOW


_lock = threading.Lock()
_apps = None

APPS_FOLDER = "shell:AppsFolder\\"


def parse(output):
    """PowerShell `Get-StartApps | ConvertTo-Json` -> sorted [(name, target)]. A target opens with ShellExecute."""
    if not output.strip():
        return []
    data = json.loads(output)
    if isinstance(data, dict):          # a single app comes back as one object, not a list
        data = [data]
    seen, out = set(), []
    for a in data:
        name, app_id = (a.get("Name") or "").strip(), (a.get("AppID") or "").strip()
        if name and app_id and (name, app_id) not in seen:
            seen.add((name, app_id))
            out.append((name, APPS_FOLDER + app_id))
    return sorted(out, key=lambda x: x[0].lower())


def installed_apps(refresh=False):
    """The Start menu apps, cached after the first call (it takes a second or two)."""
    global _apps
    with _lock:
        if _apps is None or refresh:
            try:
                r = subprocess.run(
                    ["powershell", "-NoProfile", "-Command", "Get-StartApps | ConvertTo-Json"],
                    capture_output=True, text=True, timeout=30, encoding="utf-8",
                    creationflags=NO_WINDOW)
                _apps = parse(r.stdout)
            except (OSError, subprocess.TimeoutExpired, ValueError):
                _apps = []
        return list(_apps)
