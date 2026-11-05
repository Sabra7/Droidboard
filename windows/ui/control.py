"""Tray logic without any UI: mute state, latency presets, tooltip and icon choice."""
import re
import threading


# Latency presets: name -> jitter buffer target in ms. The ceiling (where old audio is dropped)
# follows as three times the target, the same ratio as the defaults (40 / 120).
PRESETS = {"low": 20, "balanced": 40, "stable": 80}
PRESET_LABELS = {"low": "Low (20 ms)", "balanced": "Balanced (40 ms)", "stable": "Stable (80 ms)"}


def preset_buffers(name):
    """(target_ms, max_ms) for a preset name, or None for '' / an unknown name."""
    target = PRESETS.get(name)
    return None if target is None else (target, target * 3)


def default_buffers(transport):
    """(target_ms, max_ms) when nobody chose a preset: smaller over the cable, larger over the air.
    USB is 30 ms, not the Low preset's 20: measured on the S23+, 20 ms gave underruns (8 in 35 s)
    and saved only 6 ms against 30 ms, which gave none."""
    return (30, 90) if transport == "USB" else preset_buffers("balanced")


class MuteState:
    """Mute from the Windows side: silence goes to the virtual mic."""

    def __init__(self):
        self._lock = threading.Lock()
        self.muted = False

    def toggle(self):
        with self._lock:
            self.muted = not self.muted
            return self.muted


_MEDIAN = re.compile(r"median\s+([\d.]+)\s*ms\s*\|\s*min\s+([\d.]+)\s*\|\s*max\s+([\d.]+)")


def parse_latency_summary(text):
    """Notification text from the output of `--self-test`."""
    m = _MEDIAN.search(text)
    if m:
        return f"Windows side: median {m.group(1)} ms (min {m.group(2)}, max {m.group(3)})"
    if "no chirp detected" in text:
        return "Latency test: no chirp detected"
    return "Latency test failed. See the log."


def icon_state(connected, muted):
    """Which tray icon: 'muted' (red), 'connected' (green) or 'waiting' (grey)."""
    if muted:
        return "muted"       # red even while waiting: a mute from Windows should never be a surprise
    return "connected" if connected else "waiting"


def tooltip(connected, muted, transport, buffer_ms, preset, rate, gaps, underruns):
    state = "Muted" if muted else "Connected" if connected else "Waiting for phone"
    lines = [f"Droidboard - {state}"]
    if connected:
        lines.append(f"via {transport} | buffer {buffer_ms:.0f} ms"
                     + (f" | {PRESETS[preset]} ms preset" if preset in PRESETS else ""))
        lines.append(f"{rate} pkt/s | gaps {gaps} | underruns {underruns}")
    return "\n".join(lines)[:127]       # a Windows tooltip holds at most 127 characters
