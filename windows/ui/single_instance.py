"""Only one Droidboard at a time: a new launch stops the old one that still holds the port."""
import os
import subprocess
import time

from winproc import NO_WINDOW


def parse_pids(text):
    """PIDs from the output of `port_owners` (one number per line; 0 is the system, ignored)."""
    return {int(w) for w in text.split() if w.isdigit() and int(w) > 0}


def port_owners(port):
    """PIDs of processes with a socket on local `port` (listening or connected).

    `netstat` does not list the listening socket of the running receiver on this PC, but
    Get-NetTCPConnection does, so ask PowerShell.
    """
    cmd = (f"(Get-NetTCPConnection -LocalPort {int(port)} -ErrorAction SilentlyContinue)"
           ".OwningProcess | Sort-Object -Unique")
    out = subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command", cmd],
                         capture_output=True, text=True, creationflags=NO_WINDOW).stdout
    return parse_pids(out)


def _image_name(pid):
    out = subprocess.run(["tasklist", "/FI", f"PID eq {pid}", "/FO", "CSV", "/NH"],
                         capture_output=True, text=True, creationflags=NO_WINDOW).stdout
    return out.strip().split(",")[0].strip('"').lower()


def stop_previous(port, wait=4.0):
    """Kills an older Droidboard (a python process) using `port`. Returns the PIDs killed.

    Other programs on that port are left alone: the receiver then reports the port as busy.
    """
    killed = []
    for pid in port_owners(port) - {os.getpid()}:
        if _image_name(pid).startswith("python"):
            subprocess.run(["taskkill", "/PID", str(pid), "/F", "/T"], capture_output=True,
                           creationflags=NO_WINDOW)
            killed.append(pid)
    deadline = time.time() + wait
    while killed and time.time() < deadline:      # let Windows release the port
        if not (port_owners(port) - {os.getpid()}):
            break
        time.sleep(0.2)
    return killed
