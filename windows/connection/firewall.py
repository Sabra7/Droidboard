"""Windows Firewall: does it let phones on Wi-Fi reach this program, and the one-click fix (asks for admin once).

PowerShell is used instead of netsh because its values do not change with the Windows language.
"""
import ctypes
import json
import os
import subprocess
import sys

from winproc import NO_WINDOW

RULE_NAME = "Droidboard"


def programs():
    """The executables that listen: the frozen app, or both python.exe and pythonw.exe of this Python."""
    exe = os.path.abspath(sys.executable)
    if getattr(sys, "frozen", False):
        return [exe]
    folder = os.path.dirname(exe)
    found = [os.path.join(folder, n) for n in ("python.exe", "pythonw.exe")]
    return [p for p in found if os.path.exists(p)] or [exe]


def _quoted(paths):
    return ",".join("'" + p.replace("'", "''") + "'" for p in paths)


def query():
    """-> (rules, profiles_on, categories) for ui.dashboard.firewall_check, or (None, {}, []) if Windows did not answer."""
    script = (
        f"$p=@({_quoted(programs())}).ToLower();"
        "$r=@(Get-NetFirewallApplicationFilter | Where-Object { $p -contains "
        "[Environment]::ExpandEnvironmentVariables($_.Program).ToLower() } | Get-NetFirewallRule | "
        "Where-Object { $_.Direction -eq 'Inbound' } | Select-Object "
        "@{n='Enabled';e={\"$($_.Enabled)\"}},@{n='Action';e={\"$($_.Action)\"}},@{n='Profile';e={\"$($_.Profile)\"}});"
        "$f=@{}; Get-NetFirewallProfile | ForEach-Object { $f[\"$($_.Name)\"]=[bool]$_.Enabled };"
        "$c=@(Get-NetConnectionProfile | ForEach-Object { \"$($_.NetworkCategory)\" });"
        "@{rules=$r; profiles=$f; cats=$c} | ConvertTo-Json -Compress -Depth 4"
    )
    try:
        out = subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command", script],
                             capture_output=True, text=True, timeout=30, creationflags=NO_WINDOW).stdout
        data = json.loads(out)
    except (OSError, subprocess.TimeoutExpired, ValueError):
        return None, {}, []
    rules = data.get("rules") or []
    if isinstance(rules, dict):          # ConvertTo-Json writes a single item without the list
        rules = [rules]
    cats = data.get("cats") or []
    if isinstance(cats, str):
        cats = [cats]
    return rules, data.get("profiles") or {}, cats


def allow():
    """Ask Windows (one UAC prompt) to let this program take connections on every network, and switch off
    rules that block it (Windows makes those when its first question was dismissed). Returns at once."""
    paths = _quoted(programs())
    script = (
        f"$p=@({paths});"
        "Get-NetFirewallApplicationFilter | Where-Object { $p.ToLower() -contains "
        "[Environment]::ExpandEnvironmentVariables($_.Program).ToLower() } | Get-NetFirewallRule | "
        "Where-Object { $_.Direction -eq 'Inbound' -and $_.Action -eq 'Block' } | Disable-NetFirewallRule;"
        f"foreach ($x in $p) {{ New-NetFirewallRule -DisplayName '{RULE_NAME}' -Direction Inbound -Action Allow "
        "-Program $x -Profile Any | Out-Null }"
    )
    args = f'-NoProfile -WindowStyle Hidden -Command "{script}"'
    ok = ctypes.windll.shell32.ShellExecuteW(None, "runas", "powershell", args, None, 0)
    print("[net] firewall: asked Windows to allow Droidboard" if ok > 32
          else "[net] firewall: Windows did not run the change (the admin prompt was refused)")
