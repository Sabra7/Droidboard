"""--measure and --self-test: play chirps and time how long they take to come back."""
import time

import numpy as np

from audio.output import pick_output, wasapi_settings


def open_chirp_player(args):
    import sounddevice as sd
    from audio.latency import ChirpPlayer
    for _, idx, api, name in pick_output(sd, args.measure_speaker):
        try:
            return ChirpPlayer(sd, idx, args.rate, wasapi_settings(sd, api)), name
        except Exception:
            continue
    return None, None


def run_measurement(args, out, jb, stats, stop_event):
    """--measure: chirps on the laptop speaker, heard by the phone (full path).
    --self-test: chirps from a fake phone inside this process (Windows side only)."""
    from audio.latency import (FULL_PATH, WINDOWS_ONLY, Recorder, SelfTestSender,
                         estimate_latencies, make_chirp, ms_avg, summarize)

    self_test = args.self_test > 0
    count = args.self_test if self_test else args.measure
    source = SelfTestSender("127.0.0.1", args.port, args.rate) if self_test else None

    print("\n[latency] waiting for the " + ("built-in fake phone" if self_test else "phone")
          + " to connect ...")
    while not stop_event.is_set() and not (stats.connected and jb.buffered_ms() > 0):
        time.sleep(0.2)
    if stop_event.wait(1.5):  # let the buffer settle
        return

    if self_test:
        print(f"\n[latency] self-test: {count} chirps over localhost (no phone, no speaker)")
    else:
        source, name = open_chirp_player(args)
        if source is None:
            print(f"\n[latency] no speaker matching '{args.measure_speaker}' "
                  f"(see --list-devices, then use --measure-speaker)")
            return
        print(f"\n[latency] playing {count} chirps on '{name}' - "
              f"keep the phone close to the laptop speaker and stay quiet")

    recorder = Recorder()
    buffer_levels = []
    out.offsets.clear()
    out.tap = recorder.add
    for _ in range(count):
        buffer_levels.append(jb.buffered_ms() / 1000.0)
        source.trigger()
        if stop_event.wait(args.measure_interval):
            break
    stop_event.wait(1.0)  # catch the last chirp
    out.tap = None
    source.close()

    audio, times = recorder.snapshot(args.rate)
    if audio.ndim == 2:              # the phone sends stereo: the chirp is found in the mix of both sides
        audio = audio.astype(np.float32).mean(axis=1)
    results = estimate_latencies(audio, times, source.emit_times, make_chirp(args.rate))
    print("\n" + summarize(results, WINDOWS_ONLY if self_test else FULL_PATH))
    print("[latency] breakdown (reported by Windows):")
    print(f"[latency]   jitter buffer level      avg {ms_avg(buffer_levels)}"
          f"  (target {jb.target * 1000 // jb.rate} ms)")
    print(f"[latency]   virtual mic output       avg {ms_avg(out.offsets)}")
    if not self_test:
        print(f"[latency]   laptop speaker output    avg {ms_avg(source.offsets)}"
              f"  (already subtracted)")
    print()
