"""What the status window shows, without any UI: log lines sorted by part, the checks of the
Diagnostics card, the buffer history of the chart and the report the user can paste."""
import collections
import datetime
import os
import re


# ---------------------------------------------------------------- log lines

# filter name -> the [tags] it shows ("errors" is by level, not by tag)
FILTERS = {
    "All": None,
    "Mic": ("net", "audio", "opus", "bt", "bluetooth", "measure", "jitter"),
    "adb": ("adb", "autostart"),
    "Screen": ("screen", "vdd", "touch", "cursor"),
    "Camera": ("camera",),
    "Deck": ("deck", "devices"),
    "Errors": "errors",
}

_TAG = re.compile(r"^\s*\[([A-Za-z_-]+)\]")
_ERROR = re.compile(r"traceback|exception|error|failed|could not|cannot|can't|not found|refused|denied|"
                    r"unauthorized|timed out", re.I)
_WARN = re.compile(r"warn|not available|dropped|stale|retry|gave up|mixed", re.I)


def classify(text):
    """(tag, level) of one log line: tag like 'adb' ('' when the line has none), level 'error', 'warn' or ''."""
    m = _TAG.match(text)
    tag = m.group(1).lower() if m else ""
    if _ERROR.search(text) or text.startswith(("  File ", "    ")):
        level = "error"
    elif _WARN.search(text):
        level = "warn"
    else:
        level = ""
    return tag, level


def shows(filter_name, text):
    want = FILTERS.get(filter_name)
    if want is None:
        return True
    tag, level = classify(text)
    if want == "errors":
        return level == "error"
    return tag in want


def last_error(entries):
    """(time, text) of the newest error line in [(index, time, text)], or None."""
    for _, stamp, text in reversed(entries):
        if classify(text)[1] == "error":
            return stamp, text
    return None


# ---------------------------------------------------------------- the chart

class History:
    """The last `seconds` of the jitter buffer, sampled `per_second` times a second, plus the moments
    a gap or an underrun happened (shown as marks on the chart)."""

    def __init__(self, seconds=60, per_second=2):
        self.size = seconds * per_second
        self.buffer = collections.deque(maxlen=self.size)       # ms, or None while no phone is connected
        self.events = collections.deque(maxlen=self.size)       # "" / "gap" / "underrun" per sample
        self._last = None

    def add(self, buffered_ms, connected, gaps, underruns):
        self.buffer.append(buffered_ms if connected else None)
        event = ""
        if self._last is not None and connected:
            if underruns > self._last[1]:
                event = "underrun"
            elif gaps > self._last[0]:
                event = "gap"
        self.events.append(event)
        self._last = (gaps, underruns)

    def top(self, target_ms):
        """The value at the top of the chart: a round number above the samples and the target."""
        high = max([v for v in self.buffer if v is not None] + [target_ms * 2, 10])
        step = 20 if high <= 100 else 50
        return step * -(-high // step)

    def recent_events(self, samples):
        """How many gaps / underruns in the newest `samples` samples."""
        tail = list(self.events)[-samples:]
        return tail.count("gap"), tail.count("underrun")


# ---------------------------------------------------------------- adb answers

def parse_adb_devices(text):
    """`adb devices` -> [(serial, state)]. Network devices (ip:port, adb-...) are left out: only the cable."""
    found = []
    for line in text.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and ":" not in parts[0] and not parts[0].startswith("adb-"):
            found.append((parts[0], parts[1]))
    return found


def parse_reverse_list(text):
    """`adb reverse --list` -> {port} of the tcp tunnels ('UsbFfs tcp:50505 tcp:50505')."""
    ports = set()
    for line in text.splitlines():
        m = re.search(r"tcp:(\d+)\s+tcp:\d+", line)
        if m:
            ports.add(int(m.group(1)))
    return ports


# ---------------------------------------------------------------- checks

Check = collections.namedtuple("Check", "name ok detail")      # ok: True, False, or None (nothing to say)

AUDIO_PORT, DECK_PORT, CAMERA_PORT, SCREEN_PORT = 50505, 50508, 50509, 50510


def adb_checks(adb_path, devices, reverse_ports):
    """Checks about adb and the cable. devices: [(serial, state)] or None when adb could not be asked."""
    if not adb_path:
        return [Check("adb", False, "not found: install Android platform-tools")]
    checks = [Check("adb", True, adb_path)]
    if devices is None:
        checks.append(Check("Phone on the cable", False, "adb did not answer"))
        return checks
    ready = [s for s, state in devices if state == "device"]
    locked = [s for s, state in devices if state == "unauthorized"]
    if ready:
        checks.append(Check("Phone on the cable", True, ", ".join(ready)))
    elif locked:
        checks.append(Check("Phone on the cable", False,
                            f"{locked[0]} is waiting: allow USB debugging on the phone"))
    elif devices:
        checks.append(Check("Phone on the cable", False, f"{devices[0][0]}: {devices[0][1]}"))
    else:
        checks.append(Check("Phone on the cable", None, "nothing plugged in (or USB debugging is off)"))
    if ready:
        wanted = {AUDIO_PORT: "mic", DECK_PORT: "deck", CAMERA_PORT: "camera", SCREEN_PORT: "screen"}
        missing = [name for port, name in wanted.items() if port not in reverse_ports]
        checks.append(Check("adb reverse", not missing,
                            "all tunnels in place" if not missing else "missing: " + ", ".join(missing)))
    return checks


def app_checks(stats, out_name, opus_reason, virtual_display, wifi, addresses=()):
    """Checks about this program: sound device, Opus, servers, virtual camera and display."""
    checks = [Check("Output device", bool(out_name), out_name or "none")]
    checks.append(Check("Opus (libopus)", stats.opus_ok,
                        "loaded" if stats.opus_ok else (opus_reason or "not found")))
    servers = [(name, getattr(stats, attr, None) is not None)
               for name, attr in (("deck", "deck"), ("camera", "camera"), ("screen", "screen"))]
    down = [name for name, up in servers if not up]
    checks.append(Check("Servers", not down, "deck, camera and screen listening" if not down
                        else "not listening: " + ", ".join(down)))
    cam = getattr(stats, "camera", None)
    if cam is not None:
        sink = next((s for s in getattr(cam, "sinks", []) if hasattr(s, "cam")), None)   # the VirtualCamSink
        if sink is not None:
            name, note = getattr(sink, "name", ""), getattr(sink, "note", "")
            checks.append(Check("Virtual camera", bool(name) if (name or note) else None,
                                name or note or "starts when a tablet sends"))
    checks.append(Check("Virtual display driver", virtual_display is not None,
                        virtual_display or "not installed (the second screen mirrors only)"))
    if wifi:
        checks.append(wifi_check(addresses))
    if getattr(stats, "stereo_note", ""):
        checks.append(Check("Stereo", None, stats.stereo_note))
    return checks


def wifi_check(addresses):
    """Can a phone on Wi-Fi reach this laptop? addresses: best first, as pairing.local_addresses() gives them."""
    if not addresses:
        return Check("Wi-Fi", False, "no network address: connect the laptop to Wi-Fi")
    good_v4 = [a for a in addresses if ":" not in a and not a.startswith("169.254.")]
    v6 = [a for a in addresses if ":" in a]
    if good_v4:
        return Check("Wi-Fi", True, ", ".join(good_v4))
    if v6:
        return Check("Wi-Fi", None, "IPv6 only: phones still connect, older routers may not. "
                                     "Reconnecting the Wi-Fi usually brings IPv4 back")
    return Check("Wi-Fi", False, "the Wi-Fi gave no address (169.254): turn the laptop's Wi-Fi off and on")


_PROFILE = {"DomainAuthenticated": "Domain", "Domain": "Domain", "Private": "Private", "Public": "Public"}


def firewall_check(rules, profiles_on, categories):
    """Does Windows Firewall let phones reach this program over Wi-Fi?
    rules: [{Enabled, Action, Profile}] of the inbound rules for this program, or None when Windows could not be asked;
    profiles_on: {"Domain": bool, "Private": bool, "Public": bool}; categories: the current networks' categories."""
    if rules is None:
        return Check("Firewall", None, "could not ask Windows")
    nets = [_PROFILE.get(c, c) for c in categories]
    if not nets:
        return Check("Firewall", None, "no network")
    guarded = [n for n in nets if profiles_on.get(n, True)]
    if not guarded:
        return Check("Firewall", True, "off on this network")

    def applies(rule, net):
        profile = str(rule.get("Profile", ""))
        return str(rule.get("Enabled")) == "True" and ("Any" in profile or net in profile)

    for net in guarded:
        if any(r.get("Action") == "Block" and applies(r, net) for r in rules):
            return Check("Firewall", False, f"blocks phones on this {net.lower()} network: press Allow")
        if not any(r.get("Action") == "Allow" and applies(r, net) for r in rules):
            return Check("Firewall", False, f"phones on this {net.lower()} network are blocked: press Allow")
    return Check("Firewall", True, "phones on Wi-Fi may connect")


# ---------------------------------------------------------------- texts

def short_detail(check):
    """The detail as the window shows it: adb's path is long, the folder name is enough (the report keeps it)."""
    if check.name == "adb" and check.ok:
        return "found in " + os.path.basename(os.path.dirname(check.detail))
    return check.detail


def duration(seconds):
    """'45 s', '3 min', '2 h 05 min'."""
    seconds = int(max(0, seconds))
    if seconds < 60:
        return f"{seconds} s"
    if seconds < 3600:
        return f"{seconds // 60} min"
    return f"{seconds // 3600} h {seconds % 3600 // 60:02d} min"


def describe_device(device):
    """'Galaxy Tab S10 FE: deck, screen (192.168.1.5)'."""
    return f"{device['name']}: {', '.join(device['kinds'])} ({device['addr']})"


def accent_pair(abgr):
    """(light, dark) accent colours from the Windows accent (registry DWORD 0xAABBGGRR), or the app's
    fallback blue. At night the colour is mixed halfway with white so it reads on black, like the tone
    Material You uses for dark themes."""
    if abgr is None:
        return "#3A5BA9", "#AFC6FF"
    r, g, b = abgr & 0xFF, (abgr >> 8) & 0xFF, (abgr >> 16) & 0xFF
    light = f"#{r:02X}{g:02X}{b:02X}"
    dark = "#" + "".join(f"{c + (255 - c) // 2:02X}" for c in (r, g, b))
    return light, dark


def mic_line(stats):
    """'USB · Opus · stereo' for the phone sending the mic, '' when none."""
    if not stats.connected:
        return ""
    parts = [stats.transport or "?", stats.codec or "PCM", "stereo" if stats.channels == 2 else "mono"]
    return " · ".join(parts)


def report(status, numbers, checks, devices, log_entries, now=None):
    """Plain text to paste into a chat or an issue: everything the window shows, plus the newest log lines."""
    now = now or datetime.datetime.now()
    lines = [f"Droidboard report {now:%Y-%m-%d %H:%M:%S}", "", f"Status: {status}", ""]
    lines += [f"{name}: {value}" for name, value in numbers]
    lines += ["", "Checks:"]
    mark = {True: "ok  ", False: "FAIL", None: "--  "}
    lines += [f"  {mark[c.ok]} {c.name}: {c.detail}" for c in checks]
    lines += ["", "Devices:"]
    lines += [f"  {d}" for d in devices] or ["  none"]
    lines += ["", f"Log (last {len(log_entries)} lines):"]
    lines += [f"  {stamp:%H:%M:%S} {text}" for _, stamp, text in log_entries]
    return "\n".join(lines) + "\n"
