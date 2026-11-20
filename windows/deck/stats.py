"""Laptop readings for the tablet's Dim screen: CPU, RAM, GPU, VRAM and GPU temperature.

One shared sampler thread (StatsHub) runs only while at least one tablet is watching
({"t":"watch","on":true} on the deck connection). psutil and NVML are imported lazily and guarded:
a missing library, a laptop without an NVIDIA GPU or a broken driver gives None for those values and never
crashes the app. There is no CPU temperature (not reliable on Windows without admin): "temp" is the GPU's.
"""
import threading

INTERVAL = 2.0


def _pct(value):
    """A whole percent 0..100, or None when the value is missing or not a number."""
    if value is None or isinstance(value, bool):
        return None
    try:
        return max(0, min(100, int(round(float(value)))))
    except (TypeError, ValueError):
        return None


def build_stats(cpu, ram, gpu_info):
    """The stats message. gpu_info is None or {"gpu": %, "vram": %, "temp": degrees C}; any value may be None."""
    g = gpu_info or {}
    temp = g.get("temp")
    try:
        temp = None if temp is None else int(round(float(temp)))
    except (TypeError, ValueError):
        temp = None
    return {"t": "stats", "cpu": _pct(cpu), "ram": _pct(ram), "gpu": _pct(g.get("gpu")),
            "vram": _pct(g.get("vram")), "temp": temp}


class Sampler:
    """Reads the real machine. Every part is optional and fails to None."""

    def __init__(self):
        self._psutil = None
        self._psutil_tried = False
        self._nvml = None
        self._nvml_tried = False
        self._handle = None

    def _ps(self):
        if not self._psutil_tried:
            self._psutil_tried = True
            try:
                import psutil
                psutil.cpu_percent(interval=None)       # the first call is always 0.0: prime it
                self._psutil = psutil
            except Exception:
                self._psutil = None
        return self._psutil

    def _gpu_handle(self):
        if not self._nvml_tried:
            self._nvml_tried = True
            try:
                import pynvml
                pynvml.nvmlInit()
                self._handle = pynvml.nvmlDeviceGetHandleByIndex(0)
                self._nvml = pynvml
            except Exception:
                self._nvml = self._handle = None
        return self._handle

    def prime(self):
        self._ps()
        self._gpu_handle()

    def gpu_info(self):
        if self._gpu_handle() is None:
            return None
        n, h = self._nvml, self._handle
        info = {}
        for key, read in (
            ("gpu", lambda: n.nvmlDeviceGetUtilizationRates(h).gpu),
            ("vram", lambda: _ratio(n.nvmlDeviceGetMemoryInfo(h))),
            ("temp", lambda: n.nvmlDeviceGetTemperature(h, n.NVML_TEMPERATURE_GPU)),
        ):
            try:
                info[key] = read()
            except Exception:
                info[key] = None
        return info

    def sample(self):
        ps = self._ps()
        cpu = ram = None
        if ps is not None:
            try:
                cpu = ps.cpu_percent(interval=None)
                ram = ps.virtual_memory().percent
            except Exception:
                pass
        return build_stats(cpu, ram, self.gpu_info())

    def close(self):
        if self._nvml is not None:
            try:
                self._nvml.nvmlShutdown()
            except Exception:
                pass
        self._nvml = self._handle = None
        self._nvml_tried = False


def _ratio(mem):
    return 100.0 * mem.used / mem.total if mem.total else None


class StatsHub:
    """Who is watching, and the one thread that samples for all of them.

    add(key, send) / remove(key): send(msg) is called from the sampler thread every `interval` seconds.
    The thread starts with the first watcher and ends with the last one: nobody watching costs nothing.
    """

    FIRST_DELAY = 0.5        # lets the CPU counter measure something before the first reading

    def __init__(self, sample=None, interval=INTERVAL, first_delay=None):
        self._sampler = None if sample is not None else Sampler()
        self._sample = sample if sample is not None else self._sampler.sample
        self.interval = interval
        self.first_delay = self.FIRST_DELAY if first_delay is None else first_delay
        self._watchers = {}
        self._lock = threading.Lock()
        self._stop = None            # the Event of the running thread; None when no thread runs

    def add(self, key, send):
        with self._lock:
            self._watchers[key] = send
            if self._stop is None:
                self._stop = threading.Event()
                threading.Thread(target=self._run, args=(self._stop,), daemon=True, name="deck-stats").start()

    def remove(self, key):
        with self._lock:
            self._watchers.pop(key, None)
            if not self._watchers and self._stop is not None:
                self._stop.set()
                self._stop = None

    def watching(self):
        with self._lock:
            return len(self._watchers)

    def running(self):
        with self._lock:
            return self._stop is not None

    def _run(self, stop):
        if self._sampler is not None:
            self._sampler.prime()
        delay = self.first_delay
        while not stop.wait(delay):
            delay = self.interval
            try:
                msg = self._sample()
            except Exception:
                continue
            with self._lock:
                targets = list(self._watchers.values()) if not stop.is_set() else []
            for send in targets:
                try:
                    send(msg)
                except Exception:
                    pass
        if self._sampler is not None:
            with self._lock:
                idle = self._stop is None
            if idle:
                self._sampler.close()
