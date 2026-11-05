"""A small jitter buffer: absorbs uneven packet arrival before audio playback."""
import collections
import threading

import numpy as np


class JitterBuffer:
    """Holds int16 samples between the network thread and the audio callback.

    - Waits until `target_ms` of audio is buffered before playing (priming).
    - On underrun it outputs silence and primes again.
    - Clock drift is absorbed by playing up to 0.5 % faster or slower (see _ratio).
    - If more than `max_ms` piles up anyway (a burst after a stall),
      it drops the oldest audio back down to `target_ms` so latency stays low.
    """

    def __init__(self, sample_rate, target_ms=40, max_ms=120, channels=1):
        self.rate = sample_rate
        self.channels = channels   # 1: arrays of shape (n,); 2: shape (n, 2). Sizes count frames.
        self.target = int(sample_rate * target_ms / 1000)
        self.max = int(sample_rate * max_ms / 1000)
        self._lock = threading.Lock()
        self._chunks = collections.deque()
        self._offset = 0
        self._size = 0
        self._primed = False
        self.underruns = 0
        self.dropped_samples = 0
        self.muted = False     # Windows-side mute: the buffer keeps draining but silence comes out
        self.ratio = 1.0       # playback speed now: >1 catching up, <1 letting the buffer refill

    def push(self, samples):
        if len(samples) == 0:
            return
        with self._lock:
            self._chunks.append(samples)
            self._size += len(samples)
            if self._size > self.max:
                excess = self._size - self.target
                self._discard(excess)
                self.dropped_samples += excess
            if not self._primed and self._size >= self.target:
                self._primed = True

    def set_target(self, target_ms, max_ms):
        """Change the latency preset while running."""
        with self._lock:
            self.target = int(self.rate * target_ms / 1000)
            self.max = int(self.rate * max_ms / 1000)

    # Clock drift between phone and laptop: instead of dropping audio, the buffer plays slightly
    # faster (too full) or slower (too empty), by at most MAX_STRETCH, using linear interpolation.
    MAX_STRETCH = 0.005         # +-0.5 %: far below what the ear notices
    HIGH_START, HIGH_FULL = 1.5, 2.0    # buffer level (times the target) where speeding up starts / is full
    LOW_START, LOW_FULL = 1.0, 0.5      # where slowing down starts / is full (early: one 10 ms block must stay available)

    def _ratio(self):
        """How many input samples to use per output sample (1.0 = untouched). Lock must be held."""
        size, target = self._size, self.target
        if size > self.HIGH_START * target:
            k = min(1.0, (size - self.HIGH_START * target) / ((self.HIGH_FULL - self.HIGH_START) * target))
            return 1.0 + self.MAX_STRETCH * k
        if size < self.LOW_START * target:
            k = min(1.0, (self.LOW_START * target - size) / ((self.LOW_START - self.LOW_FULL) * target))
            return 1.0 - self.MAX_STRETCH * k
        return 1.0

    def _shape(self, k):
        return (k,) if self.channels == 1 else (k, self.channels)

    @staticmethod
    def _stretch(buf, n):
        """Resample `buf` (m frames) to n frames by linear interpolation, with a constant step so
        consecutive blocks join without a jump."""
        m = len(buf)
        x = np.arange(n) * (m / n)
        src = np.arange(m)
        if buf.ndim == 1:
            return np.interp(x, src, buf).round().astype(np.int16)
        out = np.empty((n, buf.shape[1]), dtype=np.int16)
        for c in range(buf.shape[1]):
            out[:, c] = np.interp(x, src, buf[:, c]).round().astype(np.int16)
        return out

    def pull(self, n):
        with self._lock:
            if not self._primed:
                return np.zeros(self._shape(n), dtype=np.int16)
            self.ratio = self._ratio()
            m = n if self.ratio == 1.0 else max(1, int(round(n * self.ratio)))
            buf = np.zeros(self._shape(m), dtype=np.int16)
            filled = 0
            while filled < m and self._chunks:
                chunk = self._chunks[0]
                take = min(len(chunk) - self._offset, m - filled)
                buf[filled:filled + take] = chunk[self._offset:self._offset + take]
                filled += take
                self._offset += take
                self._size -= take
                if self._offset >= len(chunk):
                    self._chunks.popleft()
                    self._offset = 0
            underrun = filled < m
            if underrun:
                self.underruns += 1
                self._primed = False
        if underrun or m == n:
            out = np.zeros(self._shape(n), dtype=np.int16)
            k = min(filled, n)
            out[:k] = buf[:k]
        else:
            out = self._stretch(buf, n)
        if self.muted:
            out[:] = 0
        return out
    def set_channels(self, channels):
        """Switch between mono and stereo. Drops what is buffered (it has the old shape)."""
        self.clear()
        self.channels = channels

    def clear(self):
        with self._lock:
            self._chunks.clear()
            self._offset = 0
            self._size = 0
            self._primed = False

    def buffered_ms(self):
        with self._lock:
            return self._size * 1000.0 / self.rate

    def _discard(self, n):
        while n > 0 and self._chunks:
            chunk = self._chunks[0]
            take = min(len(chunk) - self._offset, n)
            self._offset += take
            self._size -= take
            n -= take
            if self._offset >= len(chunk):
                self._chunks.popleft()
                self._offset = 0
