"""Status window (CustomTkinter) + tray icon (pystray).

One dashboard: the state of the mic, the jitter buffer over the last minute, what is connected, checks
that tell what is wrong, and the live log. Light or dark follows Windows, the accent is the Windows
accent colour (like Material You on the phone). What it shows is worked out in ui/dashboard.py.
"""
import os
import subprocess
import sys
import threading
import time
from tkinter import messagebox

import customtkinter as ctk

import paths
from ui import dashboard
from ui import winstartup
from ui.checks import Checks
from ui.control import PRESET_LABELS, PRESETS, MuteState, icon_state, parse_latency_summary, preset_buffers, tooltip
from ui.widgets import BAD, BG, CARD, FONT, GOOD, MONO, MUTED, RAISED, SEMI, TEXT, WARN
from ui.widgets import (Chart, Lamp, LogView, Rows, Tile, card_title, icon, make_card, small_button,


                        windows_accent)
from winproc import NO_WINDOW

try:
    import pystray
    from PIL import Image, ImageDraw
except ImportError:  # tray is optional
    pystray = None

PAD, GAP = 20, 12

COLORS = {"connected": (57, 211, 83), "muted": (220, 50, 50), "waiting": (110, 119, 129)}


def _icon_image(state):
    """The tray microphone icon: green connected, red muted, grey waiting."""
    color = COLORS[state]
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((22, 6, 42, 38), 10, fill=color)          # mic capsule
    d.arc((14, 20, 50, 50), 0, 180, fill=color, width=4)          # holder
    d.line((32, 50, 32, 58), fill=color, width=4)
    d.line((22, 58, 42, 58), fill=color, width=4)
    if state == "muted":
        d.line((10, 54, 54, 10), fill=(255, 255, 255), width=5)   # slash
    return img


def run(jb, stats, net, stop_event, port, wifi=False, settings=None, startup_args=(),
        out=None, args=None):
    ctk.set_appearance_mode("system")
    accent = windows_accent()
    root = ctk.CTk(fg_color=BG)
    root.title("Droidboard")
    if os.path.exists(paths.shared("droidboard.ico")):
        # CustomTkinter puts its own icon on the window shortly after it opens, so set ours after that
        root.after(300, lambda: root.iconbitmap(paths.shared("droidboard.ico")))
    root.geometry("1100x830")
    root.minsize(900, 640)
    try:
        from PIL import ImageTk
        root._app_icon = ImageTk.PhotoImage(_icon_image("connected"))
        root.iconphoto(True, root._app_icon)
    except Exception:
        pass

    request = {"action": None}      # set from other threads (tray, deck), done by the tk loop
    checks = Checks(stop_event)
    history = dashboard.History()
    ctl = MuteState()          # Windows-side mute: silence into the virtual mic
    jb.muted = ctl.muted
    icon_ref = [None]
    last_icon = [None]

    def notify(message):
        tray = icon_ref[0]
        if tray is not None and settings is not None and settings.get("notifications"):
            try:
                tray.notify(message, "Droidboard")
            except Exception:
                pass

    def refresh_icon():
        tray = icon_ref[0]
        if tray is None:
            return
        online = stats.connected
        key = icon_state(online, ctl.muted or (stats.muted and online))
        if key != last_icon[0]:
            tray.icon = _icon_image(key)
            last_icon[0] = key

    def apply_mute():
        jb.muted = ctl.muted
        refresh_icon()
        update_mute_button()

    def toggle_mute():
        ctl.toggle()
        apply_mute()

    stats.deck_hooks["mute"] = lambda: request.update(action="mute")   # the deck's mute button (another thread)

    # ------------------------------------------------------------ header
    header = ctk.CTkFrame(root, fg_color="transparent")
    header.pack(fill="x", padx=PAD, pady=(PAD, GAP))
    ctk.CTkLabel(header, text="  Droidboard", image=icon("mic", 26, accent), compound="left",
                 font=(SEMI, 22), text_color=TEXT).pack(side="left")
    pill = ctk.CTkFrame(header, fg_color=CARD, corner_radius=16, height=32)
    pill.pack(side="left", padx=(16, 0))
    dot = ctk.CTkLabel(pill, text="●", font=(FONT, 14), text_color=MUTED, width=10)
    dot.pack(side="left", padx=(12, 8))
    status = ctk.CTkLabel(pill, text="Waiting for phone", font=(FONT, 13), text_color=TEXT)
    status.pack(side="left", padx=(0, 14), pady=4)
    on_air = Lamp(pill, "ON AIR")

    def hide():
        if icon_ref[0] is not None:
            root.withdraw()
        else:
            root.iconify()

    ctk.CTkButton(header, text="Hide", image=icon("eye-off", 16, TEXT), compound="left", command=hide,
                  height=34, width=0, corner_radius=17, font=(FONT, 13), fg_color=CARD, hover_color=RAISED,
                  text_color=TEXT).pack(side="right")
    mute_btn = ctk.CTkButton(header, text="Mute", command=toggle_mute, height=34, width=0, corner_radius=17,
                             font=(FONT, 13), hover_color=RAISED, compound="left")
    mute_btn.pack(side="right", padx=(0, 8))

    def update_mute_button():
        if ctl.muted:
            mute_btn.configure(text="Unmute", image=icon("mic-off", 16, "#FFFFFF"), fg_color=BAD,
                               hover_color=BAD, text_color="#FFFFFF")
        else:
            mute_btn.configure(text="Mute", image=icon("mic", 16, TEXT), fg_color=CARD, hover_color=RAISED,
                               text_color=TEXT)

    update_mute_button()

    if wifi:    # the code the phone asks for, readable from a distance
        def copy_code():
            root.clipboard_clear()
            root.clipboard_append(stats.code or "")
            copy_btn.configure(text="Copied")
            root.after(1500, lambda: copy_btn.configure(text="Copy"))

        code_box = ctk.CTkFrame(header, fg_color=CARD, corner_radius=16)
        code_box.pack(side="right", padx=(0, 8))
        ctk.CTkLabel(code_box, text="Pairing code", font=(FONT, 11), text_color=MUTED).pack(side="left",
                                                                                           padx=(14, 8))
        ctk.CTkLabel(code_box, text=stats.code or "------", font=(MONO, 18, "bold"),
                     text_color=TEXT).pack(side="left")
        copy_btn = small_button(code_box, "Copy", "copy", copy_code, accent)
        copy_btn.pack(side="left", padx=8, pady=4)

    # ------------------------------------------------------------ number tiles
    tiles_row = ctk.CTkFrame(root, fg_color="transparent")
    tiles_row.pack(fill="x", padx=PAD)
    tiles = {}
    for i, (key, caption, glyph) in enumerate((("buffer", "Buffer", "timer"),
                                                ("packets", "Packets", "audio-lines"),
                                                ("gaps", "Gaps", "circle-alert"),
                                                ("underruns", "Underruns", "hourglass"),
                                                ("dropped", "Dropped", "triangle-alert"))):
        tiles[key] = Tile(tiles_row, caption, glyph, size=32 if i == 0 else 24)
        tiles[key].frame.grid(row=0, column=i, sticky="nsew", padx=(0 if i == 0 else GAP, 0))
        tiles_row.grid_columnconfigure(i, weight=2 if i == 0 else 1, uniform="tile")   # Buffer leads

    # ------------------------------------------------------------ chart
    chart_card = make_card(root)
    chart_card.pack(fill="x", padx=PAD, pady=GAP)
    chart_head = ctk.CTkFrame(chart_card, fg_color="transparent")
    chart_head.pack(fill="x", padx=16, pady=(16, 0))
    card_title(chart_head, "Buffer, last minute (ms)", "audio-waveform", accent).pack(side="left")
    legend = ctk.CTkFrame(chart_head, fg_color="transparent")
    legend.pack(side="right")
    for text, color in (("buffer", accent), ("target", MUTED), ("gap", WARN), ("underrun", BAD)):
        ctk.CTkLabel(legend, text="━", font=(FONT, 12, "bold"), text_color=color).pack(side="left", padx=(12, 4))
        ctk.CTkLabel(legend, text=text, font=(FONT, 11), text_color=MUTED).pack(side="left")
    chart = Chart(chart_card, accent)

    # ------------------------------------------------------------ devices + diagnostics | log
    bottom = ctk.CTkFrame(root, fg_color="transparent")
    bottom.pack(fill="both", expand=True, padx=PAD, pady=(0, PAD))
    bottom.grid_columnconfigure(0, weight=2, uniform="bottom")
    bottom.grid_columnconfigure(1, weight=3, uniform="bottom")
    bottom.grid_rowconfigure(0, weight=1)
    left = ctk.CTkFrame(bottom, fg_color="transparent")
    left.grid(row=0, column=0, sticky="nsew", padx=(0, GAP // 2))

    dev_card = make_card(left)
    dev_card.pack(fill="x")
    card_title(dev_card, "Devices", "smartphone", accent).pack(fill="x", padx=16, pady=(16, 0))
    dev_rows = Rows(dev_card, wrap=280)

    diag_card = make_card(left)
    diag_card.pack(fill="both", expand=True, pady=(GAP, 0))
    diag_head = ctk.CTkFrame(diag_card, fg_color="transparent")
    diag_head.pack(fill="x", padx=16, pady=(16, 0))
    card_title(diag_head, "Diagnostics", "cpu", accent).pack(side="left")
    diag_buttons = ctk.CTkFrame(diag_card, fg_color="transparent")
    diag_buttons.pack(fill="x", padx=16, pady=(8, 0))
    button_row = ctk.CTkFrame(diag_buttons, fg_color="transparent")
    button_row.pack(fill="x")
    diag_scroll = ctk.CTkScrollableFrame(diag_card, fg_color="transparent")
    diag_scroll.pack(fill="both", expand=True, padx=0, pady=0)
    diag_rows = Rows(diag_scroll, wrap=280)

    log_card = make_card(bottom)
    log_card.grid(row=0, column=1, sticky="nsew", padx=(GAP // 2, 0))

    def open_log_folder():
        from ui import logsetup
        os.makedirs(logsetup.LOG_DIR, exist_ok=True)
        os.startfile(logsetup.LOG_DIR)

    def numbers():
        return [("Buffer", f"{jb.buffered_ms():.1f} ms (target {round(jb.target * 1000 / jb.rate)} ms)"),
                ("Packets", f"{rate[0]}/s"), ("Gaps", stats.gaps), ("Underruns", jb.underruns),
                ("Dropped", f"{jb.dropped_samples * 1000 // jb.rate} ms"),
                ("Output", getattr(out, "name", "") or "-"), ("Muted on Windows", ctl.muted)]

    def all_checks():
        from audio import opus
        found = dashboard.adb_checks(checks.adb, checks.devices, checks.reverse)
        found += dashboard.app_checks(stats, getattr(out, "name", ""),
                                      "" if stats.opus_ok else opus.unavailable_reason(),
                                      checks.virtual_display, wifi, checks.addresses)
        if wifi:
            found.append(dashboard.firewall_check(*checks.firewall))
        from ui import logsetup
        err = dashboard.last_error(logsetup.RECENT.since(0))
        if err:
            found.append(dashboard.Check("Last error", False, f"{err[0]:%H:%M:%S}  {err[1].strip()[:160]}"))
        return found

    def device_lines():
        lines = []
        if stats.connected:
            lines.append(f"Phone mic: {dashboard.mic_line(stats)}")
        lines += [f"Standby: {p['name']} ({p['transport']})" for p in stats.hub.phones() if not p["active"]]
        lines += [dashboard.describe_device(d) for d in (stats.devices.devices() if stats.devices else [])]
        return lines

    def copy_report():
        from ui import logsetup
        text = dashboard.report(status.cget("text"), numbers(), all_checks(), device_lines(),
                                logsetup.RECENT.since(0)[-120:])
        root.clipboard_clear()
        root.clipboard_append(text)
        print("[gui] report copied to the clipboard")

    def copy_log():
        from ui import logsetup
        entries = [e for e in logsetup.RECENT.since(0) if dashboard.shows(log.filter.get(), e[2])]
        root.clipboard_clear()
        root.clipboard_append("".join(f"{t:%H:%M:%S} {line}\n" for _, t, line in entries))

    log = LogView(log_card, accent, copy_log, open_log_folder)

    def current_device():
        return getattr(out, "query", "") or getattr(args, "device", "")

    def run_latency_test():
        """The --self-test of receiver.py in its own process (another port, so it never
        disturbs this one). It plays test chirps into the virtual mic for about 25 seconds."""
        def work():
            cmd = paths.self_command(console=True) + ["--device", current_device(),
                   "--self-test", "10", "--duration", "25", "--port", "50507"]
            notify("Latency test running (about 30 s)...")
            print("[gui] latency test started (plays chirps into the mic for about 25 s)")
            try:
                r = subprocess.run(cmd, capture_output=True, text=True, timeout=90, creationflags=NO_WINDOW)
                text = (r.stdout or "") + (r.stderr or "")
                print(text)
                summary = parse_latency_summary(text)
                print(f"[gui] {summary}")
                notify(summary)
            except (OSError, subprocess.TimeoutExpired) as e:
                print(f"[gui] latency test failed: {e}")
                notify(f"Latency test failed: {e}")
        threading.Thread(target=work, daemon=True).start()

    small_button(button_row, "adb reverse", "refresh-cw",
                  lambda: threading.Thread(target=checks.reverse_all, daemon=True).start(),
                  accent).pack(side="left")
    small_button(button_row, "Latency test", "timer", run_latency_test, accent).pack(side="left", padx=8)
    small_button(button_row, "Copy report", "clipboard-copy", copy_report, accent).pack(side="left")
    # a line of its own, shown only while the firewall blocks phones on Wi-Fi (refresh_cards)
    firewall_bar = ctk.CTkFrame(diag_buttons, fg_color="transparent")
    small_button(firewall_bar, "Allow in firewall", "shield",
                 lambda: threading.Thread(target=checks.allow_firewall, daemon=True).start(), accent).pack(side="left")

    # ------------------------------------------------------------ tray
    def current_preset():
        ms = round(jb.target * 1000 / jb.rate)
        return next((name for name, v in PRESETS.items() if v == ms), "")

    def set_preset(name):
        stats.auto_buffer = False        # the user chose: stop following the connection type
        jb.set_target(*preset_buffers(name))
        settings.set("latency_preset", name)

    def set_device(name):
        if out.switch(name):
            settings.set("output_device", name)
            notify(f"Output: {name}")
        else:
            notify(f"Could not open {name}")

    def open_log():
        from ui import logsetup
        path = logsetup.latest_log()
        if path:
            os.startfile(path)
        else:
            notify("No log file yet")

    # The tray callbacks run on another thread; simple ones act directly, showing/hiding the
    # window only sets a flag that the tk loop below acts on.
    if pystray is not None:
        script = None if paths.FROZEN else os.path.abspath(sys.argv[0])

        def toggle(key):
            settings.set(key, not settings.get(key))

        def toggle_windows_start():
            winstartup.set_enabled(not winstartup.is_enabled(), script, startup_args)

        def act(fn, *a):
            """A menu action that calls fn(*a). pystray hands the icon to a one-argument function, so a
            `lambda n=name: ...` would get the icon in n; this takes no arguments."""
            return lambda: fn(*a)

        def device_items():
            names = out.device_names()
            cur = current_device().lower()
            chosen = next((n for n in names if cur and cur in n.lower()), "")   # the query is a substring
            for name in names:
                yield pystray.MenuItem(name, act(set_device, name),
                                       checked=lambda item, n=name: n == chosen, radio=True)

        def tray_mic_items():
            """The phones on the mic: the heard one is ticked, picking another one makes it heard."""
            phones = stats.hub.phones()
            if not phones:
                yield pystray.MenuItem("No phone connected", None, enabled=False)
            for p in phones:
                yield pystray.MenuItem(f"{p['name']} ({p['transport']})", act(stats.hub.use, p["token"]),
                                       checked=lambda item, t=p["token"]: stats.hub.is_active(t), radio=True)

        def tray_device_lines():
            """The devices connected right now (deck, camera, screen), one line each."""
            from connection.devices import describe
            found = stats.devices.devices() if getattr(stats, "devices", None) else []
            if not found:
                yield pystray.MenuItem("No device connected", None, enabled=False)
            for d in found:
                yield pystray.MenuItem(describe(d), None, enabled=False)

        items = [
            pystray.MenuItem("Show", lambda: request.update(action="show"), default=True),
            pystray.MenuItem("Mic from", pystray.Menu(tray_mic_items)),
            pystray.MenuItem("Devices", pystray.Menu(tray_device_lines)),
            pystray.MenuItem("Edit deck...", lambda: request.update(action="deck")),
            pystray.MenuItem("Camera preview", lambda: request.update(action="camera")),
            pystray.MenuItem(lambda item: "Unmute" if ctl.muted else "Mute",
                             lambda: request.update(action="mute")),
        ]
        if settings is not None:
            items += [
                pystray.Menu.SEPARATOR,
                pystray.MenuItem("Output device", pystray.Menu(device_items)),
                pystray.MenuItem("Latency", pystray.Menu(*[
                    pystray.MenuItem(PRESET_LABELS[n], act(set_preset, n),
                                     checked=lambda item, n=n: current_preset() == n, radio=True)
                    for n in PRESETS])),
                pystray.MenuItem("Run latency test (plays chirps into the mic)",
                                 lambda: run_latency_test()),
                pystray.Menu.SEPARATOR,
                pystray.MenuItem("Notifications", lambda: toggle("notifications"),
                                 checked=lambda item: settings.get("notifications")),
                pystray.MenuItem("Auto-start phone", lambda: toggle("auto_start_phone"),
                                 checked=lambda item: settings.get("auto_start_phone")),
                pystray.MenuItem("Start with Windows", lambda: toggle_windows_start(),
                                 checked=lambda item: winstartup.is_enabled()),
                pystray.MenuItem("Open log", lambda: open_log()),
                pystray.Menu.SEPARATOR,
            ]
        items.append(pystray.MenuItem("Quit", lambda: request.update(action="quit")))
        tray = pystray.Icon("droidboard", _icon_image("waiting"), "Droidboard", pystray.Menu(*items))
        tray.run_detached()
        icon_ref[0] = tray
        if getattr(stats, "devices", None) is not None:        # the menu follows devices coming and going
            stats.devices.listeners.append(lambda: tray.update_menu())

    def open_deck_editor():
        server = getattr(stats, "deck", None)
        if server is None:
            notify("The deck is not running.")
            return
        from deck.editor import open_editor
        open_editor(root, server.store.get(), server.apply_local)

    def open_camera_view():
        if getattr(stats, "camera_preview", None) is None:
            notify("The camera is not running.")
            return
        from camera.view import open_view
        open_view(root, stats.camera_preview)

    cam_seen = [False]

    def watch_camera():
        """A tablet camera started: tell the user, and show the picture when Windows has no virtual camera."""
        cam = getattr(stats, "camera", None)
        if cam is None:
            return
        if cam.streaming and not cam_seen[0]:
            cam_seen[0] = True
            virtual = next((s for s in cam.sinks if getattr(s, "name", "") and s is not stats.camera_preview), None)
            if virtual is None or not getattr(virtual, "name", ""):
                note = next((getattr(s, "note", "") for s in cam.sinks if getattr(s, "note", "")), "")
                notify("Tablet camera on. " + (note or "Showing it in a window."))
                open_camera_view()
            else:
                notify(f"Tablet camera on as {virtual.name}.")
        elif not cam.streaming:
            cam_seen[0] = False

    # ------------------------------------------------------------ refresh
    rate = [0]                       # packets per second
    last = {"packets": 0, "time": time.monotonic(), "slow": 0.0}
    was_online = [False]
    online_since = [None]

    def act_use(token):
        return lambda: stats.hub.use(token)

    def refresh_cards():
        now = time.time()
        rows = []
        phones = stats.hub.phones()
        heard = not (stats.muted or ctl.muted)
        for p in phones:
            if p["active"] and stats.connected:
                since = dashboard.duration(now - online_since[0]) if online_since[0] else ""
                rows.append(("smartphone", MUTED, "Phone mic" if len(phones) == 1 else p["name"],
                             dashboard.mic_line(stats), since, "ON AIR" if heard else ""))
            elif not p["active"]:     # waiting: a click makes it the phone the mic plays
                rows.append(("smartphone", MUTED, p["name"], f"{p['transport']} · standby, click to use it",
                             dashboard.duration(now - p["since"]), "", act_use(p["token"])))
        if stats.connected and not any(p["active"] for p in phones):     # Bluetooth is not on the hub
            since = dashboard.duration(now - online_since[0]) if online_since[0] else ""
            rows.append(("smartphone", MUTED, "Phone mic", dashboard.mic_line(stats), since, "ON AIR" if heard else ""))
        for d in (stats.devices.devices() if stats.devices else []):
            since = min((c["since"] for c in stats.devices.connections() if c["id"] == d["id"]), default=now)
            rows.append(("tablet", MUTED, d["name"], f"{', '.join(d['kinds'])} · {d['addr']}",
                         dashboard.duration(now - since)))
        if not rows:
            rows.append(("minus", MUTED, "Nothing connected", "Plug the phone in or open the app on it.", ""))
        dev_rows.show(rows)
        glyph = {True: ("circle-check", MUTED), False: ("circle-x", BAD), None: ("info", MUTED)}
        found = all_checks()
        diag_rows.show([(*glyph[c.ok], c.name, dashboard.short_detail(c), "") for c in found])
        blocked = any(c.name == "Firewall" and c.ok is False for c in found)
        if blocked and not firewall_bar.winfo_manager():
            firewall_bar.pack(fill="x", pady=(8, 0))
        elif not blocked and firewall_bar.winfo_manager():
            firewall_bar.pack_forget()

    def update():
        watch_camera()
        act, request["action"] = request["action"], None
        if act == "show":
            root.deiconify()
            root.lift()
        elif act == "deck":
            open_deck_editor()
        elif act == "camera":
            open_camera_view()
        elif act == "mute":
            toggle_mute()
        elif act == "quit":
            stop_event.set()
        if stats.fatal:     # e.g. a second copy is already running
            stop_event.set()
            messagebox.showerror("Droidboard", stats.fatal)
        if stop_event.is_set() or not net.is_alive():
            if icon_ref[0] is not None:
                icon_ref[0].stop()
            root.destroy()
            return

        t = time.monotonic()
        if t - last["time"] >= 1.0:
            rate[0] = round((stats.packets - last["packets"]) / (t - last["time"]))
            last["packets"], last["time"] = stats.packets, t
        online = stats.connected
        muted = (stats.muted and online) or ctl.muted
        if online != was_online[0]:
            notify(f"Phone connected via {stats.transport}" if online else "Phone disconnected")
            online_since[0] = time.time() if online else None
            was_online[0] = online
        if muted:
            dot.configure(text_color=BAD)
            status.configure(text=(f"Muted · {stats.transport}" if online else "Muted · waiting for phone")
                             + (" (on the phone)" if stats.muted and online and not ctl.muted else ""))
        elif online:
            dot.configure(text_color=GOOD)
            status.configure(text=f"Connected via {stats.transport}")
        else:
            dot.configure(text_color=MUTED)
            status.configure(text="Waiting for phone")
        heard = online and not muted
        if heard and not on_air.winfo_manager():
            on_air.pack(side="left", padx=(0, 12))
        elif not heard and on_air.winfo_manager():
            on_air.pack_forget()

        buffered = jb.buffered_ms()
        target = round(jb.target * 1000 / jb.rate)
        history.add(buffered, online, stats.gaps, jb.underruns)
        recent_gaps, recent_under = history.recent_events(history.size)
        preset = PRESET_LABELS.get(current_preset(), f"{target} ms").split(" (")[0]
        tiles["buffer"].set(f"{buffered:.1f} ms", f"target {target} ms · "
                            + ("auto" if stats.auto_buffer else preset))
        tiles["packets"].set(f"{rate[0]}/s", dashboard.mic_line(stats) or "no phone")
        tiles["gaps"].set(str(stats.gaps), f"{recent_gaps} in the last minute", alert=recent_gaps > 0)
        tiles["underruns"].set(str(jb.underruns), f"{recent_under} in the last minute", alert=recent_under > 0)
        tiles["dropped"].set(f"{jb.dropped_samples * 1000 // jb.rate} ms", "old audio removed")
        chart.draw(history, target)
        log.pull()
        if t - last["slow"] >= 1.0:          # rows of the cards: once a second is plenty
            last["slow"] = t
            refresh_cards()
        tray = icon_ref[0]
        if tray is not None:
            tray.title = tooltip(online, muted, stats.transport, buffered, current_preset(), rate[0],
                                 stats.gaps, jb.underruns)
            refresh_icon()
        root.after(500, update)

    root.protocol("WM_DELETE_WINDOW", stop_event.set)   # X quits the whole program, not just the window
    update()
    root.mainloop()
