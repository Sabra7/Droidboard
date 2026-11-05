"""The tablet as a screen for the laptop: captures a Windows display, encodes it as H.264 and streams it.

Port 50510. The tablet connects, sends a hello line, then finger events; the laptop answers with a
ready line and then video:

    hello   {"t": "hello", "code": "123456", "w": 2304, "h": 1440, "mode": "extend" | "mirror",
             "device": {...}}\\n   (device: id, name and what the tablet can decode)
    ready   {"t": "ready", "w": 2304, "h": 1440, "fps": 60, "codec": "h264", "display": "...", "encoder": "...",
             "mode": "extend", "note": ""}\\n   (note says why "extend" became "mirror", when it did)
    video   14 bytes: type(1)=0 | flags(1, bit0 = key frame) | length(4) | time in microseconds(8), then H.264
            (Annex B, SPS/PPS in front of every key frame)
    touch   7 bytes: kind(1) | action(1) | finger(1) | x(2) | y(2); kind 1 = touch (x and y 0..65535 across the
            picture), kind 2 = "send me a key frame"
"""
import select
import struct
import threading
import time
from fractions import Fraction

from connection.pairing import listen_socket, peer_host
from deck.server import AttemptLimiter, read_hello, send_line
from screen.cursor import CursorOverlay
from screen.touch import TouchMapper, WindowsInjector


SCREEN_PORT = 50510
VIDEO_HEADER = struct.Struct(">BBIQ")
TOUCH_RECORD = struct.Struct(">BBBHH")
TYPE_VIDEO = 0
FLAG_KEY = 1
KIND_TOUCH, KIND_KEYFRAME = 1, 2
MAX_HELLO = 4096


def pack_video(data, key, pts_us):
    return VIDEO_HEADER.pack(TYPE_VIDEO, FLAG_KEY if key else 0, len(data), pts_us) + data


def pack_touch(action, finger, x, y):
    """x and y are fractions 0..1 of the picture."""
    clamp = lambda v: max(0, min(65535, int(round(v * 65535))))
    return TOUCH_RECORD.pack(KIND_TOUCH, action, finger, clamp(x), clamp(y))


def pack_keyframe_request():
    return TOUCH_RECORD.pack(KIND_KEYFRAME, 0, 0, 0, 0)


def unpack_record(data):
    """-> ("touch", action, finger, x, y) with x, y as fractions, or ("keyframe",)."""
    kind, action, finger, x, y = TOUCH_RECORD.unpack(data)
    if kind == KIND_TOUCH:
        return ("touch", action, finger, x / 65535.0, y / 65535.0)
    if kind == KIND_KEYFRAME:
        return ("keyframe",)
    raise ValueError("unknown record")


# ---------------------------------------------------------------- displays and capture

def list_displays(refresh=False):
    """[{index, device, name, rect, primary}] for every output DXGI can capture, in dxcam order.

    refresh=True looks again: dxcam lists the outputs once at start-up, a virtual monitor added since is new."""
    import dxcam
    if refresh:
        dxcam.__factory.__init__()
    found = []
    for d, outputs in enumerate(dxcam.__factory.outputs):
        for o, out in enumerate(outputs):
            r = out.desc.DesktopCoordinates
            rect = (r.left, r.top, r.right, r.bottom)
            found.append({"device": d, "output": o, "name": out.devicename, "rect": rect,
                          "primary": rect[0] == 0 and rect[1] == 0})
    return found


def pick_display(displays, wanted=""):
    """The display to send: the one named in settings ("" = the primary one)."""
    if wanted:
        for d in displays:
            if wanted.lower() in d["name"].lower():
                return d
    return next((d for d in displays if d["primary"]), displays[0] if displays else None)


class DxCapture:
    """The newest picture of one display (DXGI desktop duplication through dxcam), as a BGRA array."""

    def __init__(self, display, fps):
        import dxcam
        self.cam = dxcam.create(device_idx=display["device"], output_idx=display["output"], output_color="BGRA")
        self.fps = fps

    def start(self):
        self.cam.start(target_fps=self.fps, video_mode=True)

    def latest(self):
        return self.cam.get_latest_frame()

    def stop(self):
        try:
            self.cam.stop()
        finally:
            try:
                self.cam.release()
            except Exception:
                pass


class H264Encoder:
    """Low-latency H.264: the NVIDIA encoder when there is one, otherwise x264 on the processor."""

    def __init__(self, width, height, fps, bitrate):
        import av
        self.av = av
        self.width, self.height = width - width % 2, height - height % 2       # H.264 wants even sizes
        self.name, self.ctx = None, None
        errors = []
        for name in ("h264_nvenc", "libx264"):
            try:
                self.ctx = self._open(name, fps, bitrate)
                self.name = name
                break
            except Exception as e:
                errors.append(f"{name}: {e}")
        if self.ctx is None:
            raise RuntimeError("no H.264 encoder (" + "; ".join(errors) + ")")
        self.nvenc = self.name == "h264_nvenc"
        self._pts = 0

    def _open(self, name, fps, bitrate):
        ctx = self.av.CodecContext.create(name, "w")
        ctx.width, ctx.height = self.width, self.height
        ctx.pix_fmt = "bgra" if name == "h264_nvenc" else "yuv420p"
        ctx.time_base, ctx.framerate = Fraction(1, fps), Fraction(fps, 1)
        ctx.bit_rate = bitrate
        ctx.gop_size = fps * 2
        ctx.options = (
            {"preset": "p1", "tune": "ull", "zerolatency": "1", "rc": "cbr", "bf": "0", "forced-idr": "1"}
            if name == "h264_nvenc" else
            {"preset": "ultrafast", "tune": "zerolatency", "bf": "0", "x264-params": "repeat-headers=1"})
        ctx.open()
        return ctx

    def encode(self, frame, key=False):
        """BGRA array -> list of (bytes, is_key)."""
        frame = frame[:self.height, :self.width]
        vf = self.av.VideoFrame.from_ndarray(frame, format="bgra")
        if not self.nvenc:
            vf = vf.reformat(format="yuv420p")
        vf.pts, self._pts = self._pts, self._pts + 1
        if key:
            vf.pict_type = self.av.video.frame.PictureType.I
        return [(bytes(p), bool(p.is_keyframe)) for p in self.ctx.encode(vf)]


# ---------------------------------------------------------------- the server

class ScreenServer:
    """capture(display, fps) -> object with start/latest/stop, encoder(w, h, fps, bitrate) -> object with
    encode(frame, key) and a name, injector(rect) -> object with apply(ops): all replaceable in tests."""

    def __init__(self, code, settings=None, host="127.0.0.1", port=SCREEN_PORT, displays=None,
                 capture=DxCapture, encoder=H264Encoder, injector=WindowsInjector, virtual=None,
                 cursor=True, registry=None):
        self.registry = registry          # devices.DeviceRegistry: who is connected
        self.code = code
        self.settings = settings
        self.host, self.port = host, port
        self._displays = displays or list_displays
        self._capture, self._encoder, self._injector = capture, encoder, injector
        self._virtual = virtual
        self._cursor = cursor            # True: draw the Windows pointer into the picture; or a factory(rect)
        self.limiter = AttemptLimiter()
        self.streaming = False
        self.frames = 0
        self.encoder_name = ""
        self._current = None
        self._sock = None
        self._stop = threading.Event()
        self._session = threading.Lock()         # the screen can be captured by one session at a time

    def _setting(self, key, default):
        try:
            return self.settings.get(key) if self.settings is not None else default
        except KeyError:
            return default

    def start(self):
        s = listen_socket(self.host, self.port)
        s.listen(2)
        self.port = s.getsockname()[1]
        self._sock = s
        threading.Thread(target=self._accept, daemon=True, name="screen-accept").start()
        return self

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
            if self._current is not None:               # one tablet at a time: the new one wins
                try:
                    self._current.close()
                except OSError:
                    pass
            self._current = conn
            threading.Thread(target=self._serve, args=(conn, peer_host(addr[0])), daemon=True, name="screen-client").start()

    def _serve(self, conn, addr):
        capture = None
        sender = None
        have_session = False
        token = None
        stop_sending = threading.Event()
        try:
            conn.settimeout(10)
            msg = read_hello(conn, addr, self.limiter, self.code, MAX_HELLO)
            if msg is None:
                return
            from connection.device_profile import choose_stream, parse_device
            device = parse_device(msg)
            if self.registry is not None:
                token = self.registry.add("screen", device["id"], device["name"], addr)
            # a new tablet replaces the old one: wait for the old session to let go of the screen
            have_session = self._session.acquire(timeout=5)
            if not have_session:
                send_line(conn, {"t": "error", "msg": "the screen is still busy, try again"})
                return

            fps_cap = int(self._setting("screen_fps", 60))
            mbps = float(self._setting("screen_bitrate_mbps", 20))
            # the size, speed and bit rate this device can really take (read from the device itself)
            stream = choose_stream(device, device["w"] or 2304, device["h"] or 1440, fps_cap, mbps)
            mode, note = ("extend" if msg.get("mode") == "extend" else "mirror"), ""
            display = None
            if mode == "extend":
                display, note = self._extend(stream["width"], stream["height"])
                if display is None:
                    mode = "mirror"
            if display is None:
                display = pick_display(self._displays(), self._setting("screen_display", ""))
            if display is None:
                send_line(conn, {"t": "error", "msg": "Windows has no display to send"})
                return
            fps, bitrate = stream["fps"], stream["bitrate"]
            if stream["note"] and not note:
                note = stream["note"]
            w, h = display["rect"][2] - display["rect"][0], display["rect"][3] - display["rect"][1]
            encoder = self._encoder(w, h, fps, bitrate)
            capture = self._capture(display, fps)
            self.encoder_name = encoder.name
            print(f"[screen] {device['name']}: {encoder.width}x{encoder.height} at {fps} fps, "
                  f"{bitrate / 1e6:.0f} Mbit/s ({encoder.name}); it reports {device['w']}x{device['h']}, "
                  f"{device['hz']} Hz, decoder up to {device['avc_w']}x{device['avc_h']}")
            capture.start()
            send_line(conn, {"t": "ready", "w": encoder.width, "h": encoder.height, "fps": fps, "codec": "h264",
                              "display": display["name"], "encoder": encoder.name, "mode": mode, "note": note})
            conn.settimeout(None)
            self.streaming = True
            need_key = threading.Event()
            need_key.set()
            overlay = None
            if self._cursor and self._setting("screen_cursor", True):
                factory = self._cursor if callable(self._cursor) else CursorOverlay
                overlay = factory(display["rect"])
            sender = threading.Thread(
                target=self._send_video, args=(conn, capture, encoder, fps, need_key, stop_sending, overlay),
                daemon=True, name="screen-video")
            sender.start()
            self._read_input(conn, display, need_key)
        except (OSError, ValueError, ConnectionError):
            pass
        except Exception as e:                           # a capture or encoder failure: tell the tablet
            print(f"[screen] cast failed: {type(e).__name__}: {e}")
            send_line(conn, {"t": "error", "msg": f"screen cast failed: {e}"[:200]})
        finally:
            stop_sending.set()
            self.streaming = False
            if sender is not None:
                sender.join(2)
            if capture is not None:
                capture.stop()
            if self._virtual is not None and self._virtual.device is not None:
                self._virtual.release()              # the extra monitor goes away with the tablet
            if token is not None:
                self.registry.remove(token)
            if have_session:
                self._session.release()
            if self._current is conn:
                self._current = None
            try:
                conn.close()
            except OSError:
                pass

    def _extend(self, width, height):
        """The virtual display, sized for the device -> (display or None, note)."""
        if self._virtual is None:
            from screen.vdd import VirtualDisplay
            self._virtual = VirtualDisplay()
        ok, note, device = self._virtual.extend(width, height)
        if not ok:
            return None, note
        for d in self._displays(refresh=True):
            if d["name"].lower() == device.lower():
                return d, note
        self._virtual.release()
        return None, "the virtual display did not show up for capture, so this mirrors the main screen"

    def _send_video(self, conn, capture, encoder, fps, need_key, stop, overlay=None):
        # Windows sleeps in steps of 15.6 ms unless asked for finer ones: 60 fps would come out as 32
        try:
            import ctypes
            ctypes.windll.winmm.timeBeginPeriod(1)
            fine_timer = True
        except (AttributeError, OSError):
            fine_timer = False
        try:
            self._video_loop(conn, capture, encoder, fps, need_key, stop, overlay)
        finally:
            if fine_timer:
                ctypes.windll.winmm.timeEndPeriod(1)

    def _video_loop(self, conn, capture, encoder, fps, need_key, stop, overlay=None):
        interval, t0 = 1.0 / fps, time.monotonic()
        nxt = t0
        while not stop.is_set():
            nxt += interval
            frame = capture.latest()
            if frame is not None:
                if overlay is not None:
                    frame = overlay.draw(frame)
                key = need_key.is_set()
                need_key.clear()
                for data, is_key in encoder.encode(frame, key):
                    try:
                        conn.sendall(pack_video(data, is_key, int((time.monotonic() - t0) * 1_000_000)))
                    except OSError:
                        return
                    self.frames += 1
            wait = nxt - time.monotonic()
            if wait > 0:
                stop.wait(wait)
            else:
                nxt = time.monotonic()                   # fell behind: do not try to catch up with a burst

    def _read_input(self, conn, display, need_key):
        mapper, injector = TouchMapper(), self._injector(display["rect"])
        buf = b""
        while not self._stop.is_set():
            # wait a quarter of a second for fingers: no timeout on the socket itself, which would also
            # cut the video that is being sent on it. Without news a held finger becomes a right click.
            ready, _, _ = select.select([conn], [], [], 0.25)
            if ready:
                chunk = conn.recv(TOUCH_RECORD.size * 16)
                if not chunk:
                    return
                buf += chunk
            while len(buf) >= TOUCH_RECORD.size:
                rec, buf = buf[:TOUCH_RECORD.size], buf[TOUCH_RECORD.size:]
                try:
                    item = unpack_record(rec)
                except ValueError:
                    continue
                if item[0] == "keyframe":
                    need_key.set()
                else:
                    ops = mapper.handle(item[1], item[2], item[3], item[4])
                    if ops:
                        injector.apply(ops)
            ops = mapper.tick()
            if ops:
                injector.apply(ops)
