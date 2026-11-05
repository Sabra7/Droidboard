"""Droidboard on Windows: the entry point. Reads the options and starts every part.

The voice comes in through audio/server.py and plays into a virtual mic (audio/output.py);
the deck, camera and second screen have their own folders.

    python receiver.py                  # normal use
    python receiver.py --list-devices   # show audio devices
    python receiver.py --null-output    # no audio device (testing)
"""
import argparse
import os
import sys
import threading
import time

import paths
from audio.jitter import JitterBuffer
from audio.measure import run_measurement
from audio.output import NullOutput, SoundDeviceOutput
from audio.server import Stats, handle_connection, print_stats, serve
from connection.adb import find_adb
from connection.pairing import pair_code
from connection.reverse import keep_reversed


def parse_args():
    """The command line options -> (args, the raw option list, kept for "Start with Windows")."""
    parser = argparse.ArgumentParser(description="Droidboard receiver (prototype)")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=50505)
    parser.add_argument("--rate", type=int, default=48000, help="sample rate in Hz")
    parser.add_argument("--device", default="CABLE Input", help="output device name (substring)")
    parser.add_argument("--buffer-ms", type=int, default=None,
                        help="jitter buffer target in ms (default: 30 over USB, 40 over Wi-Fi / Bluetooth)")
    parser.add_argument("--max-buffer-ms", type=int, default=120)
    parser.add_argument("--blocksize-ms", type=int, choices=(5, 10), default=10,
                        help="size of the audio blocks played to the virtual mic (5 = a little less latency)")
    parser.add_argument("--no-adb", action="store_true", help="don't run adb reverse")
    parser.add_argument("--null-output", action="store_true", help="no sound device (testing)")
    parser.add_argument("--gui", action="store_true", help="show a small status window")
    parser.add_argument("--wifi", action="store_true",
                        help="also accept the phone over Wi-Fi (listens on all interfaces; "
                             "needs the pairing code)")
    parser.add_argument("--bluetooth", action="store_true",
                        help="also connect to the phone over Bluetooth (phone must be paired)")
    parser.add_argument("--list-devices", action="store_true")
    parser.add_argument("--duration", type=float, default=0, help="stop after N seconds (testing)")
    parser.add_argument("--measure", type=int, default=0, metavar="N",
                        help="measure latency with N chirps on the laptop speaker")
    parser.add_argument("--measure-speaker", default="Speakers",
                        help="speaker that plays the chirps (name substring)")
    parser.add_argument("--measure-interval", type=float, default=1.5, help="seconds between chirps")
    parser.add_argument("--self-test", type=int, default=0, metavar="N",
                        help="measure the Windows side alone with N chirps from a built-in fake "
                             "phone (no phone, no speaker; adb is disabled)")
    argv = sys.argv[1:]
    if paths.FROZEN and not argv:
        argv = ["--gui", "--wifi"]      # Droidboard.exe opened with a double-click: the window, Wi-Fi on
    args = parser.parse_args(argv)
    if args.measure and args.null_output:
        parser.error("--measure needs a real speaker; don't combine it with --null-output")
    if args.measure and args.self_test:
        parser.error("use either --measure or --self-test, not both")
    if args.self_test:
        args.no_adb = True  # keep the real phone out of the way
    return args, argv


def open_output(args, settings, jb, block):
    """The sound device the voice plays into (the virtual mic), or none for --null-output."""
    if args.null_output:
        out = NullOutput(args.rate, jb, block)
    else:
        try:       # the device picked in the tray menu last time, else the --device option
            out = SoundDeviceOutput(settings.get("output_device") or args.device, args.rate, jb, block)
        except SystemExit:
            if not settings.get("output_device"):
                raise
            print(f"[audio] saved device '{settings.get('output_device')}' not available: "
                  f"using '{args.device}'")
            out = SoundDeviceOutput(args.device, args.rate, jb, block)
    return out


def start_tablet_servers(args, settings, stats):
    """The deck, the second screen and the webcam. One that cannot listen is skipped with a note."""
    from deck.layout import DeckStore
    from deck.prompts import ask_yes_no
    from deck.server import DeckServer
    try:      # the button deck for the tablet: on every interface only with --wifi
        stats.deck_hooks["confirm"] = ask_yes_no
        stats.deck = DeckServer(DeckStore(), stats.code, stats.deck_hooks, approver=ask_yes_no,
                                registry=stats.devices,
                                host="0.0.0.0" if args.wifi else "127.0.0.1").start()
    except OSError as e:
        print(f"[deck] could not listen: {e}")
    from screen.cast import ScreenServer
    try:      # the tablet as a second screen (the laptop picture streamed to it)
        stats.screen = ScreenServer(stats.code, settings, host="0.0.0.0" if args.wifi else "127.0.0.1",
                                    registry=stats.devices)
        if stats.screen._virtual is None:
            from screen.vdd import VirtualDisplay
            stats.screen._virtual = VirtualDisplay()
            if stats.screen._virtual.cleanup_stale():
                print("[screen] switched off a virtual display left over from an earlier run")
        stats.screen.start()
    except OSError as e:
        print(f"[screen] could not listen: {e}")
    from camera.server import CameraServer, PreviewSink, VirtualCamSink, parse_size
    try:      # the tablet as a webcam
        stats.camera_preview = PreviewSink()
        stats.camera = CameraServer(
            stats.code, [stats.camera_preview, VirtualCamSink(settings.get("camera_fps"))],
            parse_size(settings.get("camera_size")), settings.get("camera_fit"),
            host="0.0.0.0" if args.wifi else "127.0.0.1",
            always_on=settings.get("camera_always_on"), registry=stats.devices).start()
    except OSError as e:
        print(f"[camera] could not listen: {e}")


def main():
    args, argv = parse_args()

    if args.list_devices:
        import sounddevice as sd
        print(sd.query_devices())
        return

    from ui.single_instance import stop_previous
    for pid in stop_previous(args.port):   # a new launch replaces the old one
        print(f"[main] stopped the previous Droidboard (pid {pid})")

    if args.gui:
        from ui import logsetup
        logsetup.setup()      # no console under pythonw: keep a log file instead

    from ui.control import preset_buffers
    from ui.settings import Settings
    settings = Settings()
    block = args.rate * args.blocksize_ms // 1000
    jb = JitterBuffer(args.rate, args.buffer_ms or 40, args.max_buffer_ms)
    preset = preset_buffers(settings.get("latency_preset"))
    if preset:                # chosen in the tray menu earlier
        jb.set_target(*preset)
    stats = Stats()
    stats.auto_buffer = args.buffer_ms is None and not preset   # nobody chose: the default depends on the way
    out = open_output(args, settings, jb, block)

    stats.set_output_channels = out.set_channels
    from audio import opus
    stats.opus_ok = opus.available()
    if not stats.opus_ok:
        print(f"[opus] {opus.unavailable_reason()} Bluetooth stays on 16 kHz PCM.")
    stop_event = threading.Event()
    if not args.gui:
        threading.Thread(target=print_stats, args=(jb, stats, stop_event), daemon=True).start()
    stats.code = pair_code()
    from connection.devices import DeviceRegistry
    stats.devices = DeviceRegistry()
    from connection.devices import describe
    stats.devices.listeners.append(lambda: print(       # one write, so other threads' lines cannot cut into it
        "[devices] " + ("; ".join(describe(d) for d in stats.devices.devices()) or "none connected") + "\n", end=""))
    stats.deck_hooks = {"mute": lambda: setattr(jb, "muted", not jb.muted)}   # the tray replaces it
    if not (args.measure or args.self_test):
        start_tablet_servers(args, settings, stats)
    net = threading.Thread(target=serve, args=(args, jb, stats, stop_event), daemon=True)
    net.start()
    if args.wifi:
        from connection.discovery import answer, beacon
        threading.Thread(target=beacon, args=(args.port, stop_event), daemon=True).start()
        threading.Thread(target=answer, args=(args.port, stop_event), daemon=True).start()
    if args.bluetooth:
        from connection.bluetooth import serve_bluetooth

        def handle_bt(conn):
            try:
                handle_connection(conn, args, jb, stats)
            finally:
                jb.clear()

        threading.Thread(target=serve_bluetooth, args=(stats, stop_event, handle_bt),
                         daemon=True).start()
    adb_path = None if args.no_adb else find_adb()
    if adb_path and not (args.measure or args.self_test):
        threading.Thread(target=keep_reversed, args=(stop_event,), daemon=True).start()
        from connection.autostart import watch
        threading.Thread(target=watch, args=(adb_path, settings, stats, stop_event),
                         daemon=True).start()
    if args.measure or args.self_test:
        threading.Thread(target=run_measurement, args=(args, out, jb, stats, stop_event),
                         daemon=True).start()
    try:
        if args.gui:
            from ui import window
            if args.duration:
                threading.Timer(args.duration, stop_event.set).start()
            window.run(jb, stats, net, stop_event, args.port, args.wifi,
                    settings=settings, startup_args=argv, out=out, args=args)
        elif args.duration:
            time.sleep(args.duration)
        else:
            while net.is_alive():
                net.join(0.5)
    except KeyboardInterrupt:
        pass
    finally:
        stats.stopping = True
        stop_event.set()
        net.join(3.0)       # serve() ends its connection and removes the adb tunnel
        out.close()
        print(f"\n[done] packets={stats.packets} gaps={stats.gaps} "
              f"underruns={jb.underruns} dropped_ms={jb.dropped_samples * 1000 // jb.rate}")


if __name__ == "__main__":
    main()
    # Quit means quit: never leave a thread (tray icon, Bluetooth call, ...) keeping the process alive.
    sys.stdout and sys.stdout.flush()
    os._exit(0)
