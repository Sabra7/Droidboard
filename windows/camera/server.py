"""The tablet as a webcam: receives the camera picture and hands it to Windows.

The tablet connects to port 50509 and sends a hello line, then one frame after another:

    hello   {"t": "hello", "code": "123456", "device": {"id": "...", "name": "..."}}\\n
            (this laptop itself needs no code)
    frame   4 bytes JPEG length | 2 bytes rotation (0/90/180/270) | 1 byte flags | the JPEG
    answer  {"t": "ready", "virtual": "OBS Virtual Camera", "note": "", "w": 1280, "h": 720}\\n
            (laptop -> tablet, after hello; again every 60 frames)

Every frame is turned upright, fitted to the output size and given to the sinks: the virtual
camera (pyvirtualcam, needs OBS Studio's virtual camera) and the preview window.
"""
import io
import struct
import threading
import time

from connection.pairing import listen_socket, peer_host
from deck.server import AttemptLimiter, read_hello, send_line


CAMERA_PORT = 50509
FRAME_HEADER = struct.Struct(">IHB")
FLAG_MIRROR = 1
MAX_FRAME = 6 * 1024 * 1024
MAX_HELLO = 2048


def pack_frame(jpeg, rotation=0, mirror=False):
    return FRAME_HEADER.pack(len(jpeg), rotation, FLAG_MIRROR if mirror else 0) + jpeg


def read_frame(read_exact):
    """-> (jpeg bytes, rotation degrees, mirror). read_exact(n) must return n bytes or raise."""
    size, rotation, flags = FRAME_HEADER.unpack(read_exact(FRAME_HEADER.size))
    if not 0 < size <= MAX_FRAME or rotation not in (0, 90, 180, 270):
        raise ValueError("bad camera frame header")
    return read_exact(size), rotation, bool(flags & FLAG_MIRROR)


def parse_size(text, default=(1280, 720)):
    """'1920x1080' -> (1920, 1080); anything unusable gives the default."""
    try:
        w, h = (int(v) for v in text.lower().split("x"))
        if 160 <= w <= 4096 and 120 <= h <= 4096:
            return w, h
    except (ValueError, AttributeError):
        pass
    return default


def decode(jpeg, rotation, mirror, size, fit="fill"):
    """JPEG -> upright RGB numpy array of exactly `size`. fit: 'fill' crops, 'fit' adds black bars."""
    import numpy as np
    from PIL import Image, ImageOps
    img = Image.open(io.BytesIO(jpeg))
    img.draft("RGB", (size[0] * 2, size[1] * 2))          # lets the JPEG decoder skip work on huge frames
    img = img.convert("RGB")
    turn = {90: Image.ROTATE_270, 180: Image.ROTATE_180, 270: Image.ROTATE_90}.get(rotation)
    if turn is not None:                                   # rotation is clockwise, Pillow counts the other way
        img = img.transpose(turn)
    if mirror:
        img = img.transpose(Image.FLIP_LEFT_RIGHT)
    img = ImageOps.pad(img, size, Image.BILINEAR) if fit == "fit" else ImageOps.fit(img, size, Image.BILINEAR)
    return np.asarray(img)


def idle_frame(size):
    """The picture the virtual camera shows while no tablet is sending (so apps still list a live camera)."""
    import numpy as np
    from PIL import Image, ImageDraw, ImageFont
    w, h = size
    img = Image.new("RGB", size, (16, 17, 20))
    draw = ImageDraw.Draw(img)
    text = "Droidboard camera - waiting for the tablet"
    try:
        font = ImageFont.truetype("segoeui.ttf", max(12, h // 20))
    except OSError:
        font = ImageFont.load_default()
    left, top, right, bottom = draw.textbbox((0, 0), text, font=font)
    draw.text(((w - (right - left)) / 2, (h - (bottom - top)) / 2), text, fill=(154, 157, 166), font=font)
    return np.asarray(img)


class PreviewSink:
    """Keeps the newest picture for the preview window."""
    name = "preview"

    def __init__(self):
        self._lock = threading.Lock()
        self._frame = None
        self.serial = 0

    def push(self, frame):
        with self._lock:
            self._frame, self.serial = frame, self.serial + 1

    def latest(self):
        with self._lock:
            return self._frame, self.serial

    def close(self):
        pass


class VirtualCamSink:
    """Sends pictures to a virtual webcam (OBS Virtual Camera) through pyvirtualcam."""

    def __init__(self, fps=30):
        self.fps = fps
        self.cam = None
        self.name = ""
        self.note = ""
        self._failed_at = 0.0

    def push(self, frame):
        if self.cam is None:
            if time.monotonic() - self._failed_at < 10:      # do not retry on every frame
                return
            self._open(frame.shape[1], frame.shape[0])
            if self.cam is None:
                return
        try:
            self.cam.send(frame)
        except Exception as e:
            self.note = f"virtual camera stopped: {e}"
            self.close()

    def _open(self, w, h):
        try:
            import pyvirtualcam
        except ImportError:
            self.note = "pyvirtualcam is not installed (pip install pyvirtualcam)"
            self._failed_at = time.monotonic()
            return
        try:
            self.cam = pyvirtualcam.Camera(w, h, self.fps, fmt=pyvirtualcam.PixelFormat.RGB)
            self.name, self.note = self.cam.device, ""
        except Exception as e:
            self.note = "no virtual camera: install OBS Studio (its Virtual Camera is used)"
            print(f"[camera] {self.note} ({e})")
            self._failed_at = time.monotonic()

    def close(self):
        if self.cam is not None:
            try:
                self.cam.close()
            except Exception:
                pass
        self.cam, self.name = None, ""


class CameraServer:
    def __init__(self, code, sinks, size=(1280, 720), fit="fill", host="127.0.0.1", port=CAMERA_PORT,
                 always_on=False, registry=None):
        self.registry = registry          # devices.DeviceRegistry: who is connected
        # always_on: keep the virtual camera open and show a "waiting" picture when no tablet sends, so
        # Discord, Zoom and the like always find a camera in their list (and no flicker between sessions)
        self.always_on = always_on
        self.code, self.sinks, self.size, self.fit = code, sinks, size, fit
        self.host, self.port = host, port
        self.limiter = AttemptLimiter()
        self.streaming = False               # a tablet is sending right now
        self.frames = 0
        self._current = None
        self._sock = None
        self._stop = threading.Event()

    def start(self):
        s = listen_socket(self.host, self.port)
        s.listen(2)
        self.port = s.getsockname()[1]
        self._sock = s
        threading.Thread(target=self._accept, daemon=True, name="camera-accept").start()
        if self.always_on:
            threading.Thread(target=self._idle, daemon=True, name="camera-idle").start()
        return self

    def _idle(self):
        frame = idle_frame(self.size)
        while not self._stop.wait(0.2):
            if not self.streaming:
                for sink in self.sinks:
                    if isinstance(sink, VirtualCamSink):
                        sink.push(frame)

    def stop(self):
        self._stop.set()
        if self._sock:
            self._sock.close()
        if self._current:
            try:
                self._current.close()
            except OSError:
                pass

    def _accept(self):
        while not self._stop.is_set():
            try:
                conn, addr = self._sock.accept()
            except OSError:
                return
            if self._current is not None:        # a new tablet connection replaces the old one
                try:
                    self._current.close()
                except OSError:
                    pass
            self._current = conn
            threading.Thread(target=self._serve, args=(conn, peer_host(addr[0])), daemon=True, name="camera-client").start()

    def _virtual(self):
        return next((s for s in self.sinks if isinstance(s, VirtualCamSink)), None)

    def _serve(self, conn, addr):
        def read_exact(n):
            buf = bytearray()
            while len(buf) < n:
                chunk = conn.recv(n - len(buf))
                if not chunk:
                    raise ConnectionError("tablet closed the camera connection")
                buf += chunk
            return bytes(buf)

        try:
            token = None
            conn.settimeout(10)
            msg = read_hello(conn, addr, self.limiter, self.code, MAX_HELLO)
            if msg is None:
                return
            if self.registry is not None:
                from connection.device_profile import parse_device
                d = parse_device(msg)
                token = self.registry.add("camera", d["id"], d["name"], addr)
            self.streaming = True
            self._announce(conn)
            while not self._stop.is_set():
                jpeg, rotation, mirror = read_frame(read_exact)
                try:
                    frame = decode(jpeg, rotation, mirror, self.size, self.fit)
                except Exception:
                    continue                      # one damaged picture is not worth dropping the camera
                for sink in self.sinks:
                    sink.push(frame)
                self.frames += 1
                if self.frames % 60 == 1:         # tell the tablet whether Windows got a virtual camera
                    self._announce(conn)
        except (OSError, ValueError, ConnectionError):
            pass
        finally:
            if token is not None:
                self.registry.remove(token)
            self.streaming = False
            if not self.always_on:
                for sink in self.sinks:
                    if isinstance(sink, VirtualCamSink):
                        sink.close()               # lets OBS and the apps release the camera
            if self._current is conn:
                self._current = None
            try:
                conn.close()
            except OSError:
                pass

    def _announce(self, conn):
        v = self._virtual()
        send_line(conn, {"t": "ready", "virtual": v.name if v else "", "note": v.note if v else "",
                          "w": self.size[0], "h": self.size[1]})
