"""Integer-factor linear upsampling (Bluetooth sends 16 kHz, the output runs at 48 kHz)."""
import numpy as np


def upsample(x, k, prev):
    """x: int16 samples at the low rate. Returns (int16 at k times the rate, new prev)."""
    if len(x) == 0:
        return x, prev
    a = np.concatenate(([prev], x[:-1])).astype(np.float32)
    b = x.astype(np.float32)
    steps = (np.arange(1, k + 1, dtype=np.float32) / k)[None, :]
    out = a[:, None] + (b - a)[:, None] * steps
    return np.rint(out).astype(np.int16).reshape(-1), int(x[-1])
