"""Mono / stereo helpers: reading interleaved frames, mixing down, spotting a fake stereo signal."""
import numpy as np


def deinterleave(payload, channels):
    """int16 samples of one frame: shape (n,) for mono, (n, channels) for more."""
    a = np.frombuffer(payload, dtype="<i2")
    if channels == 1:
        return a
    return a[: len(a) // channels * channels].reshape(-1, channels)


def mix_to_mono(x):
    """Average the channels (int32 maths, so loud signals do not overflow)."""
    if x.ndim == 1:
        return x
    return x.astype(np.int32).mean(axis=1).astype(np.int16)


def fit_block(block, channels):
    """Shape an audio block to the number of channels the sound device has."""
    if block.ndim == 1:
        return block[:, None] if channels == 1 else np.repeat(block[:, None], channels, axis=1)
    if block.shape[1] == channels:
        return block
    if channels == 1:
        return mix_to_mono(block)[:, None]
    return np.repeat(mix_to_mono(block)[:, None], channels, axis=1)


class FakeStereoDetector:
    """Notices stereo that is really one mic copied to both sides.

    Compares the energy of the difference (L-R) with the energy of the sum (L+R) over about
    one second. Two real mics hear the voice differently; a copy has a difference of ~0.
    """
    WINDOW_FRAMES = 100        # 10 ms frames -> 1 second
    MIN_RMS = 100.0            # quieter than this tells us nothing
    MAX_SIDE_TO_MID = 0.01     # difference below -20 dB of the sum = "the same signal"

    def __init__(self):
        self.fake = False
        self._frames = 0
        self._side = 0.0
        self._mid = 0.0
        self._n = 0

    def feed(self, stereo):
        """Adds one frame (shape (n, 2)). Returns True/False when a verdict is ready, else None."""
        x = stereo.astype(np.float64)
        left, right = x[:, 0], x[:, 1]
        self._side += float(np.sum((left - right) ** 2))
        self._mid += float(np.sum((left + right) ** 2))
        self._n += len(x)
        self._frames += 1
        if self._frames < self.WINDOW_FRAMES:
            return None
        rms = (self._mid / max(self._n, 1) / 4.0) ** 0.5     # rms of (L+R)/2
        verdict = None
        if rms >= self.MIN_RMS:
            verdict = self._side <= self.MAX_SIDE_TO_MID * self._mid
            self.fake = verdict
        self._frames, self._side, self._mid, self._n = 0, 0.0, 0.0, 0
        return verdict
