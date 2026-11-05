"""Start the phone app by itself when the phone is plugged in over USB.

Polls `adb devices`; when a USB phone shows up (and the user has "Auto-start phone" on, and no
phone is streaming yet) it runs `adb shell am start ... --ez autostart true`, which the Android
app reads to begin streaming and go back to the background.
"""
import subprocess

from winproc import NO_WINDOW


APP_ACTIVITY = "dev.phonemic/.MainActivity"


def parse_devices(adb_output):
    """{serial: state} from the output of `adb devices`."""
    devices = {}
    for line in adb_output.splitlines():
        parts = line.split()
        if line.startswith(("*", "List of devices")):      # daemon messages and the header
            continue
        if len(parts) >= 2:
            devices[parts[0]] = parts[1]
    return devices


def is_usb(serial):
    """Wi-Fi adb shows up as ip:port, and as a name with _adb-tls- in it (mDNS): neither is a cable."""
    return ":" not in serial and "_adb-tls" not in serial


def usb_phones(devices):
    """Serials of the phones that are ready and plugged in with a cable."""
    return sorted(s for s, state in devices.items() if state == "device" and is_usb(s))


def new_usb_phones(before, now):
    """Serials that are ready now but were not before. Wi-Fi adb is not USB: skipped."""
    return sorted(s for s in usb_phones(now) if before.get(s) != "device")


def start_command(adb, serial):
    # --activity-single-top: when the app is already open on the phone, the plain command only brings it
    # to the front and the app never sees `autostart`
    return [adb, "-s", serial, "shell", "am", "start", "--activity-single-top", "-n", APP_ACTIVITY,
            "--ez", "autostart", "true"]


def list_devices(adb):
    try:
        r = subprocess.run([adb, "devices"], capture_output=True, text=True, timeout=5,
                           creationflags=NO_WINDOW)
        return parse_devices(r.stdout)
    except (OSError, subprocess.TimeoutExpired):
        return {}


def watch(adb, settings, stats, stop_event, interval=2.0):
    """Thread body. Starts the app on every phone that is newly plugged in."""
    # A phone already plugged in at launch counts too (starting the laptop app is a reason to
    # start the phone one). Wait a moment first: a phone app that is already running connects
    # by itself within a second or two, and then it needs no push.
    stop_event.wait(4.0)
    before = {}
    while not stop_event.is_set():
        now = list_devices(adb)
        for serial in new_usb_phones(before, now):
            if settings.get("auto_start_phone") and not stats.connected:
                print(f"\n[auto] phone {serial} found: starting the app on it")
                try:
                    subprocess.run(start_command(adb, serial), capture_output=True, timeout=10,
                                   creationflags=NO_WINDOW)
                except (OSError, subprocess.TimeoutExpired) as e:
                    print(f"[auto] could not start the app: {e}")
        before = now
        stop_event.wait(interval)
