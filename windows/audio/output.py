"""Playing the voice into a Windows sound device (the virtual mic), or into nothing for tests."""
import sys
import threading
import time

from audio.channels import fit_block
from audio.latency import dac_offset


def pick_output(sd, query):
    """Output devices whose name contains `query`, best host API first."""
    prefs = ["Windows WASAPI", "Windows DirectSound", "MME"]
    hostapis = sd.query_hostapis()
    found = []
    for idx, dev in enumerate(sd.query_devices()):
        if dev["max_output_channels"] > 0 and query.lower() in dev["name"].lower():
            api = hostapis[dev["hostapi"]]["name"]
            rank = prefs.index(api) if api in prefs else len(prefs)
            found.append((rank, idx, api, dev["name"]))
    return sorted(found)


_com = threading.local()


def com_ready():
    """Sets up COM on this thread once (WASAPI needs it on the thread that opens a stream)."""
    if sys.platform != "win32" or getattr(_com, "done", False):
        return
    import ctypes
    ctypes.windll.ole32.CoInitializeEx(None, 0)     # COINIT_MULTITHREADED; "already set up" is fine too
    _com.done = True


def wasapi_settings(sd, api):
    """Let WASAPI resample if the device runs at another rate."""
    if api != "Windows WASAPI":
        return None
    try:
        return sd.WasapiSettings(auto_convert=True)
    except TypeError:  # older sounddevice
        return None


class SoundDeviceOutput:
    def __init__(self, query, rate, jb, block):
        import sounddevice as sd
        self.jb = jb
        candidates = pick_output(sd, query)
        if not candidates:
            sys.exit(f"No output device matching '{query}'. Install VB-Cable "
                     f"or pick one from --list-devices.")
        self.tap = None  # optional fn(samples, t_first) - used by --measure
        self.offsets = []  # output latency per callback while tapped (seconds)
        self.sd, self.rate, self.block, self.candidates = sd, rate, block, candidates
        self.query = query
        self.channels = 1
        self.name = ""        # the device really open, with its host API
        self.stream = None
        err = self._open(1)
        if err is not None:
            sys.exit(f"Could not open '{query}': {err}")

    def _open(self, channels):
        """Opens the stream with `channels` (tries each host API). Returns None, or the last error.
        It is also called from the connection thread (stereo arrives): WASAPI needs COM set up on the
        calling thread, else it refuses (GLE 0x490) and DirectSound takes over with about 70 ms more."""
        com_ready()
        last_err = None
        for _, idx, api, name in self.candidates:
            last_err = self._try(idx, api, name, channels)
            if last_err is None:
                return None
        return last_err

    def _try(self, idx, api, name, channels):
        """One host API: None when the stream runs, else the error."""
        try:
            stream = self.sd.OutputStream(device=idx, samplerate=self.rate, channels=channels,
                                          dtype="int16", blocksize=self.block,
                                          latency="low", callback=self._callback,
                                          extra_settings=wasapi_settings(self.sd, api))
            stream.start()
            self.stream, self.channels = stream, channels
            self.name = f"{name} [{api}]"
            print(f"[audio] output: {name} [{api}] @ {self.rate} Hz, "
                  f"{'stereo' if channels == 2 else 'mono'}")
            return None
        except Exception as e:  # the caller tries again or the next host API (slower ones add latency: say why)
            print(f"[audio] {api} refused {'stereo' if channels == 2 else 'mono'}: {e}")
            return e

    @staticmethod
    def device_names():
        """Names of the output devices, best host API first, without duplicates."""
        import sounddevice as sd
        names = []
        for _, _, _, name in pick_output(sd, ""):
            if name.startswith(("Primary Sound", "Microsoft Sound Mapper")):
                continue          # DirectSound's "default" entries, not real devices
            # the old MME API cuts names at 31 characters: that is the same device, skip it
            if not any(known.startswith(name) for known in names):
                names.append(name)
        return names

    def switch(self, query):
        """Plays into another device. If it cannot be opened the old one is kept.
        Returns True on success."""
        import sounddevice as sd
        candidates = pick_output(sd, query)
        if not candidates:
            return False
        saved = (self.candidates, self.query, self.channels)
        self.candidates, self.query = candidates, query
        self.stream.stop()
        self.stream.close()
        if self._open(saved[2]) is None or self._open(1) is None:
            return True
        self.candidates, self.query = saved[0], saved[1]       # back to what worked
        if self._open(saved[2]) is not None:
            self._open(1)
        return False

    def set_channels(self, channels):
        """Reopens the device as mono or stereo. If it refuses stereo, stays mono (the
        receiver then mixes the two channels down). Returns the channels really in use."""
        if channels == self.channels:
            return channels
        old = self.stream
        old.stop()
        old.close()
        if self._open(channels) is not None:
            print(f"[audio] this device does not accept {channels} channels: mixing to mono")
            if self._open(1) is not None:
                sys.exit("Could not reopen the output device")
        return self.channels

    def _callback(self, outdata, frames, time_info, status):
        block = self.jb.pull(frames)
        if block.ndim == 1 and outdata.shape[1] == 1:
            outdata[:, 0] = block
        else:
            outdata[:] = fit_block(block, outdata.shape[1])
        tap = self.tap
        if tap is not None:
            lat = self.stream.latency if isinstance(self.stream.latency, float) else 0.0
            off = dac_offset(time_info, lat)
            tap(block, time.perf_counter() + off)
            self.offsets.append(off)

    def close(self):
        self.stream.stop()
        self.stream.close()



class NullOutput:
    """Pulls audio at real-time pace without a sound card (for testing)."""

    def __init__(self, rate, jb, block):
        self.jb, self.rate, self.block = jb, rate, block
        self.name = ""
        self.running = True
        self.tap = None
        self.offsets = []
        threading.Thread(target=self._run, daemon=True).start()
        print("[audio] null output (no sound device)")

    def _run(self):
        period = self.block / self.rate
        next_t = time.monotonic()
        while self.running:
            block = self.jb.pull(self.block)
            tap = self.tap
            if tap is not None:
                tap(block, time.perf_counter())
            next_t += period
            time.sleep(max(0.0, next_t - time.monotonic()))

    def set_channels(self, channels):
        return channels      # nothing to open

    def switch(self, query):
        return True

    @staticmethod
    def device_names():
        return []

    def close(self):
        self.running = False
