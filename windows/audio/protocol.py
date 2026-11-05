"""Wire protocol shared by the Android app and the Windows receiver.

Every audio frame is one header followed by its payload, little-endian:

    magic        4s   b"PMIC"
    version      u8   1
    flags        u8   bit 0 = muted
    codec        u8   0 = PCM signed 16-bit, 1 = Opus (one Opus packet per frame)
    channels     u8   1 = mono, 2 = stereo (PCM: interleaved L R L R ...)
    seq          u32  increments by one per frame, wraps at 2^32
    timestamp_ns u64  capture time on the phone (monotonic clock)
    sample_rate  u32  e.g. 48000
    payload_len  u16  bytes of audio that follow

Right after a phone connects, the laptop sends a 6-byte capabilities message
(CAPS_MAGIC, version, bit flags) so the phone knows what the laptop can decode.
A phone that gets nothing within a moment assumes PCM only.

Keep this file and android/.../Protocol.kt in sync.
"""
import struct


MAGIC = b"PMIC"
VERSION = 1
CODEC_PCM16 = 0
CODEC_OPUS = 1
FLAG_MUTED = 0x01

HEADER = struct.Struct("<4sBBBBIQIH")
HEADER_SIZE = HEADER.size  # 26


CAPS_MAGIC = b"PMCP"
CAPS_VERSION = 1
CAP_OPUS = 0x01     # the laptop can decode Opus
CAPS_SIZE = 6


def pack_caps(opus):
    return CAPS_MAGIC + bytes([CAPS_VERSION, CAP_OPUS if opus else 0])


class ProtocolError(Exception):
    pass


def pack_frame(seq, payload, sample_rate=48000, flags=0, timestamp_ns=0,
               codec=CODEC_PCM16, channels=1):
    header = HEADER.pack(MAGIC, VERSION, flags, codec, channels,
                         seq & 0xFFFFFFFF, timestamp_ns, sample_rate, len(payload))
    return header + payload


def unpack_header(data):
    magic, version, flags, codec, channels, seq, ts, rate, plen = HEADER.unpack(data)
    if magic != MAGIC:
        raise ProtocolError(f"bad magic {magic!r}")
    if version != VERSION:
        raise ProtocolError(f"unsupported protocol version {version}")
    return {
        "flags": flags,
        "muted": bool(flags & FLAG_MUTED),
        "codec": codec,
        "channels": channels,
        "seq": seq,
        "timestamp_ns": ts,
        "sample_rate": rate,
        "payload_len": plen,
    }
