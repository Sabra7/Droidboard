"""Opus through libopus (a DLL), with ctypes: decoder, packet-loss concealment, and an encoder
(used by sims.phone and the tests).

No pip package is needed, only the library file. It is looked for, in this order:
  1. the DROIDBOARD_OPUS_DLL environment variable (full path of the file)
  2. opus.dll / libopus-0.dll / libopus.dll next to receiver.py
  3. the system (PATH)
  4. libraries that other Python packages ship: PyAV (av.libs) and SDL2 (share/sdl2/bin)
See the "Opus" section of README.md.
"""
import ctypes
import ctypes.util
import glob
import os
import sys

import numpy as np

import paths


OPUS_OK = 0
OPUS_APPLICATION_VOIP = 2048
OPUS_APPLICATION_AUDIO = 2049
OPUS_SET_BITRATE_REQUEST = 4002
MAX_PACKET = 1500
MAX_FRAME = 5760        # 120 ms at 48 kHz: the longest frame Opus can hold

_HERE = paths.DATA      # the windows folder, or the folder of Droidboard.exe
_NAMES = ("opus.dll", "libopus-0.dll", "libopus.dll")


class OpusUnavailable(Exception):
    """libopus could not be loaded or used. The message says what to do."""


def candidate_paths():
    """Files that may be libopus, best guess first (they may not exist)."""
    paths = []
    env = os.environ.get("DROIDBOARD_OPUS_DLL")
    if env:
        paths.append(env)
    paths += [os.path.join(_HERE, n) for n in _NAMES]
    for n in ("opus", "libopus-0", "libopus"):
        found = ctypes.util.find_library(n)
        if found:
            paths.append(found)
    site = [p for p in sys.path if p.lower().endswith("site-packages")]
    if getattr(sys, "_MEIPASS", None):
        site.append(sys._MEIPASS)        # Droidboard.exe: PyAV's DLLs are inside it, in av.libs
    for sp in site:
        paths += sorted(glob.glob(os.path.join(sp, "av.libs", "libopus*.dll")))
    roots = {sys.prefix, os.path.dirname(sys.executable)}
    for root in roots:
        paths += sorted(glob.glob(os.path.join(root, "share", "sdl2", "bin", "libopus-0.dll")))
    return paths


_lib = None
_lib_error = ""


def _load():
    global _lib, _lib_error
    if _lib is not None:
        return _lib
    tried = []
    for path in candidate_paths():
        if os.path.isabs(path) and not os.path.isfile(path):
            continue
        try:
            lib = ctypes.CDLL(path)
            lib.opus_decoder_create.restype = ctypes.c_void_p
            lib.opus_decoder_create.argtypes = [ctypes.c_int32, ctypes.c_int, ctypes.POINTER(ctypes.c_int)]
            lib.opus_decode.restype = ctypes.c_int
            lib.opus_decode.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_int32,
                                        ctypes.POINTER(ctypes.c_int16), ctypes.c_int, ctypes.c_int]
            lib.opus_decoder_destroy.argtypes = [ctypes.c_void_p]
            lib.opus_encoder_create.restype = ctypes.c_void_p
            lib.opus_encoder_create.argtypes = [ctypes.c_int32, ctypes.c_int, ctypes.c_int,
                                                ctypes.POINTER(ctypes.c_int)]
            lib.opus_encode.restype = ctypes.c_int32
            lib.opus_encode.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_int16), ctypes.c_int,
                                        ctypes.c_char_p, ctypes.c_int32]
            lib.opus_encoder_ctl.argtypes = [ctypes.c_void_p, ctypes.c_int]   # variadic: int values
            lib.opus_encoder_destroy.argtypes = [ctypes.c_void_p]
            _lib = lib
            return lib
        except (OSError, AttributeError) as e:
            tried.append(f"{path}: {e}")
    _lib_error = ("libopus was not found. Put opus.dll next to receiver.py or Droidboard.exe (see README.md, "
                  "section Opus), or set DROIDBOARD_OPUS_DLL.")
    raise OpusUnavailable(_lib_error)


def available():
    """True when libopus can be used. Never raises."""
    try:
        _load()
        return True
    except OpusUnavailable:
        return False


def unavailable_reason():
    return "" if available() else _lib_error


class OpusDecoder:
    """Decodes one Opus packet per call, and can conceal a lost one."""

    def __init__(self, rate=48000, channels=1):
        lib = _load()
        self.rate, self.channels = rate, channels
        err = ctypes.c_int(0)
        self._dec = lib.opus_decoder_create(rate, channels, ctypes.byref(err))
        if not self._dec or err.value != OPUS_OK:
            raise OpusUnavailable(f"could not create the Opus decoder (error {err.value})")
        self._lib = lib
        self.last_frame = rate // 100 * 2      # 20 ms until we know better
        self._buf = (ctypes.c_int16 * (MAX_FRAME * channels))()

    def _shape(self, n):
        pcm = np.ctypeslib.as_array(self._buf)[: n * self.channels].astype("<i2")
        return pcm if self.channels == 1 else pcm.reshape(-1, self.channels)

    def decode(self, packet):
        if not packet:       # libopus would treat an empty packet as a lost one
            raise ValueError("empty Opus packet")
        n = self._lib.opus_decode(self._dec, packet, len(packet), self._buf, MAX_FRAME, 0)
        if n < 0:
            raise ValueError(f"bad Opus packet (error {n})")
        self.last_frame = n
        return self._shape(n)

    def conceal(self):
        """Audio standing in for one lost packet (libopus packet-loss concealment)."""
        n = self._lib.opus_decode(self._dec, None, 0, self._buf, self.last_frame, 0)
        if n < 0:
            raise ValueError(f"Opus concealment failed (error {n})")
        return self._shape(n)

    def close(self):
        if self._dec:
            self._lib.opus_decoder_destroy(self._dec)
            self._dec = None

    def __del__(self):
        try:
            self.close()
        except Exception:
            pass


class OpusEncoder:
    """Encodes 10 / 20 ms frames of int16 audio (mono: shape (n,); stereo: (n, 2))."""

    def __init__(self, rate=48000, channels=1, bitrate=64000, application=OPUS_APPLICATION_VOIP):
        lib = _load()
        self.rate, self.channels = rate, channels
        err = ctypes.c_int(0)
        self._enc = lib.opus_encoder_create(rate, channels, application, ctypes.byref(err))
        if not self._enc or err.value != OPUS_OK:
            raise OpusUnavailable(f"could not create the Opus encoder (error {err.value})")
        self._lib = lib
        lib.opus_encoder_ctl(self._enc, OPUS_SET_BITRATE_REQUEST, ctypes.c_int32(bitrate))
        self._out = ctypes.create_string_buffer(MAX_PACKET)

    def encode(self, samples):
        pcm = np.ascontiguousarray(samples, dtype="<i2")
        frame = len(pcm)
        n = self._lib.opus_encode(self._enc, pcm.ctypes.data_as(ctypes.POINTER(ctypes.c_int16)),
                                  frame, self._out, MAX_PACKET)
        if n < 0:
            raise ValueError(f"Opus encode failed (error {n})")
        return self._out.raw[:n]

    def close(self):
        if self._enc:
            self._lib.opus_encoder_destroy(self._enc)
            self._enc = None

    def __del__(self):
        try:
            self.close()
        except Exception:
            pass
