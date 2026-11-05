"""End-to-end latency measurement.

The laptop plays short chirps on its speakers, the phone hears them through
its mic, and the audio comes back through the whole pipeline. We find each
chirp in the audio that leaves the jitter buffer and compare times:

    latency = (when the chirp is written to the virtual mic)
            - (when the chirp left the laptop speaker)

That covers: phone mic capture + phone processing + USB/adb + jitter buffer +
virtual device output. It does NOT include the delay inside Discord/Zoom.
"""
import socket
import threading
import time

import numpy as np

from audio.protocol import pack_frame


def make_chirp(rate, ms=60, f0=1000.0, f1=6000.0, amplitude=0.5):
    """Linear sweep with a Hann window: easy to find by cross-correlation."""
    n = int(rate * ms / 1000)
    t = np.arange(n) / rate
    dur = n / rate
    phase = 2 * np.pi * (f0 * t + (f1 - f0) * t * t / (2 * dur))
    return (amplitude * np.sin(phase) * np.hanning(n)).astype(np.float32)


def dac_offset(time_info, fallback):
    """Seconds from 'now' until this callback's first sample reaches the device."""
    try:
        off = time_info.outputBufferDacTime - time_info.currentTime
    except AttributeError:
        off = 0.0
    return off if 0.0 < off < 1.0 else fallback


class Recorder:
    """Collects audio blocks leaving the jitter buffer with their output times."""

    def __init__(self):
        self._lock = threading.Lock()
        self.blocks = []  # (t_first_sample, int16 array)

    def add(self, samples, t_first):
        with self._lock:
            self.blocks.append((t_first, samples.copy()))

    def snapshot(self, rate):
        with self._lock:
            blocks = list(self.blocks)
        if not blocks:
            return np.zeros(0, np.float32), np.zeros(0)
        audio = np.concatenate([b for _, b in blocks]).astype(np.float32) / 32768.0
        times = np.concatenate([t + np.arange(len(b)) / rate for t, b in blocks])
        return audio, times


def estimate_latencies(audio, times, emit_times, template, window_s=1.0, min_snr=12.0):
    """For each emission time, find the chirp in `audio` and return its delay in
    seconds (None when it wasn't found clearly)."""
    if len(audio) < len(template):
        return [None] * len(emit_times)
    n = len(audio) + len(template) - 1
    size = 1 << (n - 1).bit_length()
    corr = np.fft.irfft(np.fft.rfft(audio, size) * np.conj(np.fft.rfft(template, size)), size)
    corr = np.abs(corr[:len(audio) - len(template) + 1])
    starts = times[:len(corr)]

    results = []
    for t_emit in emit_times:
        idx = np.nonzero((starts >= t_emit) & (starts <= t_emit + window_s))[0]
        if len(idx) == 0:
            results.append(None)
            continue
        window = corr[idx]
        peak = int(np.argmax(window))
        noise = float(np.median(window)) + 1e-9
        if window[peak] / noise < min_snr:
            results.append(None)
        else:
            results.append(float(starts[idx[peak]] - t_emit))
    return results


class ChirpPlayer:
    """Plays chirps on a real speaker and records exactly when each one starts."""

    def __init__(self, sd, device_index, rate, extra_settings=None):
        self.rate = rate
        self.chirp = make_chirp(rate)
        self._lock = threading.Lock()
        self._pos = None
        self.emit_times = []
        self.offsets = []  # speaker output latency used for each chirp (seconds)
        self.stream = sd.OutputStream(device=device_index, samplerate=rate, channels=1,
                                      dtype="float32", latency="low",
                                      callback=self._callback, extra_settings=extra_settings)
        self.stream.start()

    def trigger(self):
        with self._lock:
            if self._pos is None:
                self._pos = 0

    def _callback(self, outdata, frames, time_info, status):
        outdata.fill(0)
        with self._lock:
            if self._pos is None:
                return
            if self._pos == 0:
                lat = self.stream.latency if isinstance(self.stream.latency, float) else 0.0
                off = dac_offset(time_info, lat)
                self.emit_times.append(time.perf_counter() + off)
                self.offsets.append(off)
            n = min(frames, len(self.chirp) - self._pos)
            outdata[:n, 0] = self.chirp[self._pos:self._pos + n]
            self._pos += n
            if self._pos >= len(self.chirp):
                self._pos = None

    def close(self):
        self.stream.stop()
        self.stream.close()


class SelfTestSender:
    """A fake phone inside the receiver. Sends silence with chirps over localhost
    TCP, so we can time the Windows side alone: TCP + jitter buffer + output."""

    def __init__(self, host, port, rate):
        self.rate = rate
        self.frame = rate // 100
        self.chirp = make_chirp(rate)
        self.emit_times = []
        self.offsets = []
        self._lock = threading.Lock()
        self._pending = False
        self._pos = None
        self._stop = threading.Event()
        self._sock = None
        threading.Thread(target=self._run, args=(host, port), daemon=True).start()

    def trigger(self):
        with self._lock:
            if self._pos is None:
                self._pending = True

    def _run(self, host, port):
        while not self._stop.is_set():
            try:
                self._sock = socket.create_connection((host, port), timeout=1.0)
                break
            except OSError:
                time.sleep(0.2)
        if self._sock is None:
            return
        self._sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        seq = 0
        start = time.monotonic()
        try:
            while not self._stop.is_set():
                samples = np.zeros(self.frame, dtype=np.float32)
                starting = False
                with self._lock:
                    if self._pending:
                        self._pos, self._pending, starting = 0, False, True
                    if self._pos is not None:
                        n = min(self.frame, len(self.chirp) - self._pos)
                        samples[:n] = self.chirp[self._pos:self._pos + n]
                        self._pos += n
                        if self._pos >= len(self.chirp):
                            self._pos = None
                pcm = (samples * 32767).astype("<i2").tobytes()
                if starting:
                    self.emit_times.append(time.perf_counter())
                self._sock.sendall(pack_frame(seq, pcm, self.rate))
                seq += 1
                due = start + seq * self.frame / self.rate
                time.sleep(max(0.0, due - time.monotonic()))
        except OSError:
            pass

    def close(self):
        self._stop.set()
        if self._sock is not None:
            try:
                self._sock.close()
            except OSError:
                pass


def ms_avg(values):
    return f"{np.mean(values) * 1000:.1f} ms" if len(values) else "n/a"


FULL_PATH = "phone mic -> virtual mic output (Discord adds its own delay on top)"
WINDOWS_ONLY = "Windows side only: localhost TCP + jitter buffer + virtual mic output"


def summarize(latencies, scope=FULL_PATH):
    found = [x * 1000 for x in latencies if x is not None]
    lines = [f"[latency] chirp {i + 1}: " + (f"{x * 1000:6.1f} ms" if x is not None else "not detected")
             for i, x in enumerate(latencies)]
    if found:
        lines.append(f"[latency] median {np.median(found):.1f} ms | min {min(found):.1f} | "
                     f"max {max(found):.1f} | detected {len(found)}/{len(latencies)}")
    else:
        lines.append("[latency] no chirp detected - put the phone near the laptop speaker, "
                     "raise the volume, and make sure the app is not muted")
    lines.append(f"[latency] = {scope}")
    return "\n".join(lines)
