"""What a connected device says about itself (its "profile"), and the stream that suits it.

The Android app reads its own screen, refresh rate, video decoder and cameras and sends them in every
hello, so the laptop never has to know the model: a 60 Hz phone with a small decoder and a 90 Hz tablet
with a big one each get a picture size and speed they can really handle.
"""

MIN_FPS = 24
BITS_PER_PIXEL = 0.1            # H.264 at a desktop-friendly quality: bits per pixel per frame


def _num(value, default=0):
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def parse_device(hello):
    """The profile in a hello message as a flat dict. Missing parts are 0 or "" (an old app sends none)."""
    dev = hello.get("device") if isinstance(hello.get("device"), dict) else {}
    avc = dev.get("avc") if isinstance(dev.get("avc"), dict) else {}
    return {
        "id": str(dev.get("id", ""))[:64],
        "name": str(dev.get("name", ""))[:64] or "Android device",
        "w": _num(hello.get("w"), _num(dev.get("w"))),
        "h": _num(hello.get("h"), _num(dev.get("h"))),
        "hz": _num(dev.get("hz")),
        "multitouch": bool(dev.get("multitouch", True)),
        "avc_w": _num(avc.get("w")),
        "avc_h": _num(avc.get("h")),
        "avc_fps": _num(avc.get("fps")),
        "avc_fps1080": _num(avc.get("fps1080")),
        "hevc": bool(dev.get("hevc", False)),
    }


def fit_inside(w, h, max_w, max_h):
    """The size (even numbers, same shape) of w x h shrunk to fit inside max_w x max_h; unchanged if it already does."""
    if not (max_w and max_h) or (w <= max_w and h <= max_h):
        return w, h
    k = min(max_w / w, max_h / h)
    return max(2, int(w * k) // 2 * 2), max(2, int(h * k) // 2 * 2)


def choose_stream(device, width, height, cap_fps=60, max_bitrate_mbps=20):
    """-> {width, height, fps, bitrate, note} for a device. `width` x `height` is the size wanted (its screen).

    The size shrinks to what its decoder can do, the speed is the lowest of the cap, its screen's refresh
    rate and what its decoder manages at that size, and the bit rate follows size and speed (never above the cap)."""
    note = []
    w, h = fit_inside(width, height, device.get("avc_w", 0), device.get("avc_h", 0))
    if (w, h) != (width, height):
        note.append(f"the video decoder of {device.get('name', 'this device')} stops at "
                    f"{device['avc_w']}x{device['avc_h']}")
    fps = max(MIN_FPS, cap_fps)
    if device.get("hz"):
        fps = min(fps, max(MIN_FPS, device["hz"]))
    if device.get("avc_fps"):                                  # the decoder said what it manages at the screen size
        fps = min(fps, max(MIN_FPS, device["avc_fps"] * (width * height) // max(1, w * h)))
    elif device.get("avc_fps1080"):                            # only a 1080p figure: scale it by the pixel count
        fps = min(fps, max(MIN_FPS, device["avc_fps1080"] * 1920 * 1080 // max(1, w * h)))
    bitrate = int(min(max_bitrate_mbps * 1_000_000, max(2_000_000, w * h * fps * BITS_PER_PIXEL)))
    return {"width": w, "height": h, "fps": int(fps), "bitrate": bitrate, "note": "; ".join(note)}
