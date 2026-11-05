"""The voice server: the phone connects, sends frames, and they go into the jitter buffer."""
import socket
import sys
import threading
import time

from audio.channels import FakeStereoDetector, deinterleave, mix_to_mono
from audio.hub import MicHub, phone_name
from audio.opus import OpusDecoder, OpusUnavailable
from audio.protocol import CODEC_OPUS, CODEC_PCM16, HEADER_SIZE, ProtocolError, pack_caps, unpack_header
from audio.resample import upsample
from connection.pairing import HELLO_SIZE, check_hello, is_local, listen_socket, peer_host
from connection.reverse import AdbReverse
from ui.control import default_buffers


class Stats:
    """What the program knows right now; the window, the tray and every server read and write it."""

    def __init__(self):
        self.devices = None      # devices.DeviceRegistry: everything connected over the deck, camera and screen
        self.screen = None       # the ScreenServer (the tablet as a second screen)
        self.camera = None       # the CameraServer (the tablet as a webcam)
        self.camera_preview = None
        self.deck = None         # the DeckServer, once started
        self.deck_hooks = {}     # actions of the tablet's button deck that need the app (mute)
        self.connected = False
        self.packets = 0
        self.gaps = 0
        self.muted = False
        self.rate_warned = False
        self.transport = ""   # "USB", "Wi-Fi" or "Bluetooth" while connected
        self.code = ""        # Wi-Fi pairing code
        self.fatal = ""       # set when the receiver cannot run at all (shown by the window)
        self.stopping = False  # the program is quitting: connection loops must end
        self.codec = ""        # "PCM" or "Opus" while connected
        self.auto_buffer = False   # True: the jitter buffer target follows the way the phone connects
        self.opus_ok = False   # libopus found: we can tell the phone it may send Opus
        self.channels = 1      # channels the phone sends right now
        self.stereo_note = ""  # e.g. the phone sends the same signal on both sides
        # asked to open the sound device with this many channels; returns how many it really has
        self.set_output_channels = lambda n: n
        self.hub = MicHub()          # every phone connected for the mic, and which one is heard


def recv_exact(conn, n):
    if hasattr(conn, "recv_exact"):  # Bluetooth link
        return conn.recv_exact(n)
    buf = bytearray(n)
    view = memoryview(buf)
    got = 0
    while got < n:
        r = conn.recv_into(view[got:], n - got)
        if r == 0:
            raise ConnectionError("phone closed the connection")
        got += r
    return bytes(buf)


MAX_PLC = 4     # lost Opus packets concealed in a row; a longer hole is just skipped


def send_caps(conn, opus_ok):
    """Tell the phone what we can decode (best effort: an old phone app never reads it)."""
    if hasattr(conn, "sendall"):
        try:
            conn.sendall(pack_caps(opus_ok))
        except OSError:
            pass


def handle_connection(conn, args, jb, stats, make_decoder=None, heard=None, transport=""):
    """Reads one phone's frames into the jitter buffer. With several phones, `heard()` says whether this one is
    the one the mic plays; while it is not, its frames are read and dropped and the link stays up."""
    if isinstance(conn, socket.socket):
        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        conn.settimeout(1.5)  # frames arrive every 10 ms; 1.5 s of silence = dead link (a pulled cable)
    send_caps(conn, stats.opus_ok)
    expected = None
    prev = 0              # last sample, for upsampling
    channels = 0          # what the phone sends now; 0 until the first frame
    out_channels = 1      # what the sound device plays
    detector = FakeStereoDetector()
    decoder = None
    playing = heard is None or heard()      # this phone is the one the mic plays
    if playing and stats.auto_buffer:     # no preset chosen: USB gets the small buffer, wireless the larger one
        jb.set_target(*default_buffers(transport or stats.transport))

    def push(samples):
        jb.push(mix_to_mono(samples) if out_channels == 1 and samples.ndim == 2 else samples)

    try:
        while not stats.stopping:
            h = unpack_header(recv_exact(conn, HEADER_SIZE))
            payload = recv_exact(conn, h["payload_len"])
            if heard is not None and not heard():
                playing = False       # standby: keep the link, drop the sound
                continue
            if not playing:           # picked after waiting: start clean, like a new connection
                playing = True
                channels, decoder, expected = 0, None, None
                if stats.auto_buffer:
                    jb.set_target(*default_buffers(transport or stats.transport))
            codec = h["codec"]
            if codec not in (CODEC_PCM16, CODEC_OPUS) or h["channels"] not in (1, 2):
                raise ProtocolError(f"unsupported codec={codec} channels={h['channels']}")
            if (h["channels"] == 2 or codec == CODEC_OPUS) and h["sample_rate"] != args.rate:
                raise ProtocolError(f"{'Opus' if codec == CODEC_OPUS else 'stereo'} at "
                                    f"{h['sample_rate']} Hz is not supported (output is {args.rate} Hz)")
            if h["channels"] != channels:     # first frame, or the phone switched Mono <-> Stereo
                channels = h["channels"]
                out_channels = stats.set_output_channels(channels)
                jb.set_channels(out_channels)
                detector = FakeStereoDetector()
                decoder = None
                stats.channels = channels
                stats.stereo_note = ("this sound device is mono: the two mics are mixed"
                                     if channels == 2 and out_channels == 1 else "")
            if codec == CODEC_OPUS:
                if decoder is None:
                    try:
                        decoder = (make_decoder or OpusDecoder)(args.rate, channels)
                    except OpusUnavailable as e:
                        raise ProtocolError(f"the phone sends Opus but this PC cannot decode it: {e}")
                if expected is not None:      # packets that never arrived: libopus conceals them
                    missing = (h["seq"] - expected) & 0xFFFFFFFF
                    if 0 < missing <= MAX_PLC:
                        for _ in range(missing):
                            push(decoder.conceal())
                try:
                    samples = decoder.decode(payload)
                except ValueError as e:
                    raise ProtocolError(str(e))
                stats.codec = "Opus"
            else:
                samples = deinterleave(payload, channels)
                stats.codec = "PCM"
            if channels == 2:
                if detector.feed(samples) is not None and detector.fake:
                    stats.stereo_note = "both sides carry the same sound: the phone gave one mic"
                elif detector.fake is False and out_channels == 2:
                    stats.stereo_note = ""
            elif h["sample_rate"] != args.rate:
                k, rem = divmod(args.rate, h["sample_rate"])
                if rem == 0 and k > 1:   # e.g. Bluetooth PCM: 16 kHz -> 48 kHz
                    samples, prev = upsample(samples, k, prev)
                elif not stats.rate_warned:
                    print(f"\n[warn] phone sends {h['sample_rate']} Hz but output is {args.rate} Hz; "
                          f"restart with --rate {h['sample_rate']}")
                    stats.rate_warned = True
            if expected is not None and h["seq"] != expected:
                stats.gaps += (h["seq"] - expected) & 0xFFFFFFFF
            expected = (h["seq"] + 1) & 0xFFFFFFFF
            stats.packets += 1
            stats.muted = h["muted"]
            push(samples)
    finally:
        if playing:
            stats.channels = 1     # the note stays; it is cleared by the next connection's first frame
            stats.codec = ""

def serve(args, jb, stats, stop_event):
    adb = AdbReverse(args.port, enabled=not args.no_adb)
    adb.ensure(force=True)
    host = "0.0.0.0" if args.wifi else args.host   # Wi-Fi needs the LAN interface too
    try:
        # On Windows SO_REUSEADDR lets a second copy bind the same port (two receivers, one a
        # leftover "zombie"); EXCLUSIVEADDRUSE makes the second copy fail instead.
        srv = listen_socket(host, args.port, exclusive=True)
    except OSError as e:
        stats.fatal = (f"Port {args.port} is already in use ({e.strerror or e}). "
                       f"Droidboard is probably already running.")
        print(f"[net] {stats.fatal}")
        return
    srv.listen(4)
    srv.settimeout(1.0)
    print(f"[net] waiting for the phone on {host}:{args.port} ...")
    if args.wifi:
        print(f"[net] Wi-Fi: the phone finds this laptop by itself; type the code {stats.code} in the app")
    while not stop_event.is_set():
        try:
            conn, addr = srv.accept()
        except socket.timeout:
            adb.ensure()  # re-create the tunnel after a replug
            continue
        peer = peer_host(addr[0])
        local = is_local(peer)
        if not local:  # LAN peers must prove they know the pairing code
            try:
                conn.settimeout(3.0)
                ok = check_hello(recv_exact(conn, HELLO_SIZE), stats.code)
            except (ConnectionError, socket.timeout, OSError):
                ok = False
            if not ok:
                print(f"\n[net] rejected {peer}: wrong or missing pairing code")
                conn.close()
                time.sleep(1.0)  # slows down code guessing
                continue
        threading.Thread(target=_phone, args=(conn, peer, local, args, jb, stats), daemon=True,
                         name="mic-phone").start()
    srv.close()
    adb.remove()


def _phone(conn, peer, local, args, jb, stats):
    """One phone on the mic, in its own thread: heard, or waiting while another phone is heard."""
    transport = "USB" if local else "Wi-Fi"
    hub = stats.hub
    name = phone_name(stats.devices.devices() if stats.devices else [], peer, transport)
    token = hub.join(name, transport)
    print(f"\n[net] phone connected ({peer}, {transport})" + ("" if hub.is_active(token) else ": standby"))

    def heard():
        if not hub.is_active(token):
            return False
        stats.connected, stats.transport = True, transport
        return True

    was_heard = False
    try:
        was_heard = heard()
        handle_connection(conn, args, jb, stats, heard=heard, transport=transport)
    except (ConnectionError, socket.timeout, OSError, ProtocolError) as e:
        print(f"\n[net] disconnected ({name}): {e}")
    finally:
        was_heard = was_heard or hub.is_active(token)
        hub.leave(token)
        conn.close()
        if not hub.has_active():
            stats.connected = False
        if was_heard:
            jb.clear()  # silence while nobody is heard, no stale audio later


def print_stats(jb, stats, stop_event):
    last_packets = 0
    while not stop_event.wait(1.0):
        rate = stats.packets - last_packets
        last_packets = stats.packets
        state = "CONNECTED" if stats.connected else "waiting  "
        mute = " MUTED" if stats.muted and stats.connected else ""
        sys.stdout.write(
            f"\r{state} | buffer {jb.buffered_ms():5.1f} ms | {rate:3d} pkt/s | "
            f"gaps {stats.gaps} | underruns {jb.underruns} | "
            f"dropped {jb.dropped_samples * 1000 // jb.rate} ms{mute}      ")
        sys.stdout.flush()
