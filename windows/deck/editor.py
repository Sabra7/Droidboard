"""The deck editor window on the laptop (CustomTkinter): pick programs and files, record shortcuts,
choose icons. Opened from the tray menu. Changes go to the tablet when you press Save."""
import copy
import threading
import tkinter as tk
from tkinter import filedialog

import customtkinter as ctk

from deck import apps
from deck.actions import SYSTEM_LABELS
from deck.keys import format_shortcut
from deck.layout import COLORS, MAX_BUTTONS, MAX_COLS, MIN_COLS, validate_deck
from ui import icons


BG, SURFACE, RAISED, LINE = "#0e0f12", "#16171b", "#202228", "#2c2e36"
TEXT, MUTED, PRIMARY = "#e8e9ec", "#9a9da6", "#4c8dff"
ACCENT = {"slate": "#8a94a6", "blue": "#4c8dff", "green": "#39d353", "amber": "#ffb020",
          "red": "#ff6b5e", "purple": "#a77bff", "pink": "#ff7eb6", "teal": "#2cd1c0"}
FONT = "Segoe UI"

KINDS = [("media", "Media"), ("hotkey", "Shortcut"), ("text", "Type text"), ("open", "Open"),
         ("system", "System"), ("phonemic", "Droidboard")]
MEDIA_LABELS = {"play_pause": "Play / Pause", "next": "Next track", "prev": "Previous track", "stop": "Stop",
                "vol_up": "Volume up", "vol_down": "Volume down", "vol_mute": "Mute sound"}

_images = {}


def icon_image(name, size, color=TEXT):
    key = (name, size, color)
    if key not in _images:
        img = icons.render(name, size * 2, color)
        _images[key] = ctk.CTkImage(light_image=img, dark_image=img, size=(size, size))
    return _images[key]


def _label(master, text, muted=True, size=12, **kw):
    return ctk.CTkLabel(master, text=text, font=(FONT, size), text_color=MUTED if muted else TEXT, anchor="w", **kw)


def _entry(master, **kw):
    return ctk.CTkEntry(master, fg_color=SURFACE, border_color=LINE, text_color=TEXT, height=36,
                        corner_radius=10, font=(FONT, 13), **kw)


def _button(master, text, command, primary=False, danger=False, width=None, **kw):
    fg = PRIMARY if primary else RAISED
    return ctk.CTkButton(master, text=text, command=command, height=36, corner_radius=10, font=(FONT, 13),
                         fg_color=fg, hover_color="#6aa0ff" if primary else LINE,
                         text_color="#ff6b5e" if danger else (BG if primary else TEXT),
                         **({"width": width} if width else {}), **kw)


class DeckEditor(ctk.CTkToplevel):
    def __init__(self, master, deck, on_save):
        super().__init__(master, fg_color=BG)
        self.title("Droidboard - Deck")
        self.geometry("1000x600+40+30")
        self.minsize(900, 540)
        self.deck = copy.deepcopy(deck)
        self.on_save = on_save
        self.sel = 0 if self.deck["buttons"] else None

        self.columnconfigure(0, weight=1)
        self.columnconfigure(1, weight=0)
        self.rowconfigure(0, weight=1)
        self.left = ctk.CTkFrame(self, fg_color=BG)
        self.left.grid(row=0, column=0, sticky="nsew", padx=(20, 10), pady=20)
        self.right = ctk.CTkFrame(self, fg_color=SURFACE, corner_radius=18, width=400)
        self.right.grid(row=0, column=1, sticky="ns", padx=(10, 20), pady=20)
        self.right.grid_propagate(False)
        self.right.configure(width=400)
        self._build_left()
        self._render()

    # ---------------------------------------------------------------- left: the grid
    def _build_left(self):
        head = ctk.CTkFrame(self.left, fg_color="transparent")
        head.pack(fill="x")
        ctk.CTkLabel(head, text="Deck", font=(FONT, 22, "bold"), text_color=TEXT).pack(side="left")
        self.status = _label(head, "", size=12)
        self.status.pack(side="left", padx=14)
        self.save_btn = _button(head, "Save to tablet", self._save, primary=True, width=130)
        self.save_btn.pack(side="right")
        row = ctk.CTkFrame(self.left, fg_color="transparent")
        row.pack(fill="x", pady=(14, 10))
        _label(row, "Columns").pack(side="left", padx=(0, 10))
        self.cols = ctk.CTkSegmentedButton(
            row, values=[str(n) for n in range(MIN_COLS, MAX_COLS + 1)], command=self._set_cols,
            fg_color=SURFACE, selected_color=PRIMARY, unselected_color=SURFACE, selected_hover_color=PRIMARY,
            unselected_hover_color=RAISED, text_color=TEXT, font=(FONT, 12))
        self.cols.pack(side="left")
        self.grid_frame = ctk.CTkScrollableFrame(self.left, fg_color="transparent")
        self.grid_frame.pack(fill="both", expand=True)

    def _set_cols(self, value):
        self.deck["cols"] = int(value)
        self._changed()
        self._render_grid()

    def _changed(self):
        self.status.configure(text="Unsaved changes", text_color="#ffb020")

    def _render(self):
        self.cols.set(str(self.deck["cols"]))
        self._render_grid()
        self._render_form()

    def _render_grid(self):
        for w in self.grid_frame.winfo_children():
            w.destroy()
        cols = self.deck["cols"]
        for c in range(cols):
            self.grid_frame.columnconfigure(c, weight=1, uniform="cell")
        items = self.deck["buttons"] + ([None] if len(self.deck["buttons"]) < MAX_BUTTONS else [])
        for i, b in enumerate(items):
            cell = ctk.CTkFrame(self.grid_frame, fg_color=SURFACE if b else BG, corner_radius=16, height=104,
                                border_width=2 if i == self.sel else 1,
                                border_color=PRIMARY if i == self.sel else LINE)
            cell.grid(row=i // cols, column=i % cols, padx=5, pady=5, sticky="nsew")
            cell.grid_propagate(False)
            cell.pack_propagate(False)
            if b is None:
                lab = ctk.CTkLabel(cell, text="+", font=(FONT, 26), text_color=MUTED)
                lab.place(relx=0.5, rely=0.5, anchor="center")
                widgets = (cell, lab)
                handler = self._add
            else:
                col = ACCENT.get(b["color"], ACCENT["slate"])
                glyph = ctk.CTkLabel(cell, text="", image=icon_image(b["icon"] or "zap", 30, col))
                glyph.pack(pady=(20, 6))
                name = ctk.CTkLabel(cell, text=b["label"] or " ", font=(FONT, 12), text_color=TEXT)
                name.pack()
                widgets = (cell, glyph, name)
                handler = (lambda i=i: self._select(i))
            for w in widgets:
                w.bind("<Button-1>", lambda _e, h=handler: h())

    def _select(self, i):
        self.sel = i
        self._render_grid()
        self._render_form()

    def _add(self):
        n = 1
        ids = {b["id"] for b in self.deck["buttons"]}
        while f"b{n}" in ids:
            n += 1
        self.deck["buttons"].append({"id": f"b{n}", "label": "New button", "icon": "zap", "color": "blue",
                                     "action": {"type": "media", "key": "play_pause"}})
        self._changed()
        self._select(len(self.deck["buttons"]) - 1)

    # ---------------------------------------------------------------- right: the form
    def _button(self):
        return self.deck["buttons"][self.sel] if self.sel is not None else None

    def _render_form(self):
        for w in self.right.winfo_children():
            w.destroy()
        b = self._button()
        pad = {"padx": 20}
        if b is None:
            _label(self.right, "Pick a button on the left, or press + to add one.", size=13).pack(pady=30, **pad)
            return
        _label(self.right, "NAME").pack(fill="x", pady=(20, 4), **pad)
        name = _entry(self.right)
        name.insert(0, b["label"])
        name.pack(fill="x", **pad)
        name.bind("<KeyRelease>", lambda _e: self._edit("label", name.get()[:24]))

        _label(self.right, "ICON AND COLOUR").pack(fill="x", pady=(16, 4), **pad)
        row = ctk.CTkFrame(self.right, fg_color="transparent")
        row.pack(fill="x", **pad)
        ctk.CTkButton(row, text="", image=icon_image(b["icon"] or "zap", 26, ACCENT.get(b["color"], TEXT)),
                      width=48, height=48, corner_radius=12, fg_color=RAISED, hover_color=LINE,
                      command=self._pick_icon).pack(side="left")
        for c in COLORS:
            ctk.CTkButton(row, text="", width=26, height=26, corner_radius=13, fg_color=ACCENT[c],
                          hover_color=ACCENT[c], border_width=2 if c == b["color"] else 0, border_color=TEXT,
                          command=lambda c=c: self._edit("color", c)).pack(side="left", padx=(8, 0))

        _label(self.right, "WHAT IT DOES").pack(fill="x", pady=(16, 4), **pad)
        kinds = ctk.CTkSegmentedButton(
            self.right, values=[l for _, l in KINDS], command=self._set_kind, fg_color=SURFACE,
            selected_color=PRIMARY, unselected_color=RAISED, selected_hover_color=PRIMARY,
            unselected_hover_color=LINE, text_color=TEXT, font=(FONT, 11))
        kinds.set(dict(KINDS)[b["action"]["type"]])
        kinds.pack(fill="x", **pad)
        panel = ctk.CTkFrame(self.right, fg_color="transparent")
        panel.pack(fill="x", pady=(12, 0), **pad)
        self._action_panel(panel, b["action"])

        self.error = _label(self.right, "", size=12)
        self.error.configure(text_color="#ff6b5e", wraplength=360, justify="left")
        self.error.pack(fill="x", pady=(10, 0), **pad)
        foot = ctk.CTkFrame(self.right, fg_color="transparent")
        foot.pack(side="bottom", fill="x", pady=20, **pad)
        _button(foot, "Earlier", lambda: self._move(-1), width=80).pack(side="left")
        _button(foot, "Later", lambda: self._move(1), width=80).pack(side="left", padx=8)
        _button(foot, "Delete", self._delete, danger=True, width=80).pack(side="right")

    def _edit(self, key, value):
        self._button()[key] = value
        self._changed()
        if key == "label":
            self._render_grid()
        else:
            self._render_grid()
            self._render_form()

    def _set_kind(self, label):
        kind = next(k for k, l in KINDS if l == label)
        b = self._button()
        if b["action"]["type"] != kind:
            defaults = {"media": {"key": "play_pause"}, "hotkey": {"keys": ""}, "text": {"text": ""},
                        "open": {"target": ""}, "system": {"what": "lock"}, "phonemic": {"what": "mute"}}
            b["action"] = {"type": kind, **defaults[kind]}
            self._changed()
            self._render_form()

    def _action_panel(self, panel, a):
        kind = a["type"]
        if kind in ("media", "system", "phonemic"):
            field = {"media": "key", "system": "what", "phonemic": "what"}[kind]
            labels = {"media": MEDIA_LABELS, "system": SYSTEM_LABELS, "phonemic": {"mute": "Mute / unmute the mic"}}[kind]
            inverse = {v: k for k, v in labels.items()}
            menu = ctk.CTkOptionMenu(panel, values=list(labels.values()), fg_color=RAISED, button_color=RAISED,
                                     button_hover_color=LINE, text_color=TEXT, dropdown_fg_color=SURFACE,
                                     dropdown_text_color=TEXT, height=36, corner_radius=10, font=(FONT, 13),
                                     command=lambda v: self._set_action(**{field: inverse[v]}))
            menu.set(labels[a[field]])
            menu.pack(fill="x")
            if kind == "system":
                _label(panel, "Restart, shut down and sign out ask on this laptop first.", size=11).pack(
                    fill="x", pady=(8, 0))
        elif kind == "hotkey":
            e = _entry(panel, placeholder_text="Ctrl+Shift+M")
            e.insert(0, a["keys"])
            e.pack(fill="x")
            e.bind("<KeyRelease>", lambda _e: self._set_action(keys=e.get().strip()))
            _button(panel, "Record a shortcut", lambda: self._record(e)).pack(anchor="w", pady=(8, 0))
        elif kind == "text":
            t = ctk.CTkTextbox(panel, height=110, fg_color=SURFACE, border_width=1, border_color=LINE,
                               text_color=TEXT, corner_radius=10, font=(FONT, 13))
            t.insert("1.0", a["text"])
            t.pack(fill="x")
            t.bind("<KeyRelease>", lambda _e: self._set_action(text=t.get("1.0", "end-1c")))
            _label(panel, "Typed where the cursor is, like a keyboard.", size=11).pack(fill="x", pady=(6, 0))
        elif kind == "open":
            e = _entry(panel, placeholder_text="Website, file, folder or program")
            e.insert(0, a["target"])
            e.pack(fill="x")
            e.bind("<KeyRelease>", lambda _e: self._set_action(target=e.get().strip()))
            row = ctk.CTkFrame(panel, fg_color="transparent")
            row.pack(fill="x", pady=(8, 0))
            _button(row, "Installed apps", self._pick_app).pack(side="left")
            _button(row, "File", self._pick_file, width=60).pack(side="left", padx=6)
            _button(row, "Folder", self._pick_folder, width=70).pack(side="left")
            _label(panel, "ARGUMENTS (OPTIONAL)").pack(fill="x", pady=(12, 4))
            args = _entry(panel)
            args.insert(0, a.get("args", ""))
            args.pack(fill="x")
            args.bind("<KeyRelease>", lambda _e: self._set_action(args=args.get().strip()))

    def _set_action(self, **fields):
        a = self._button()["action"]
        for k, v in fields.items():
            if v == "" and k == "args":
                a.pop("args", None)
            else:
                a[k] = v
        self._changed()

    def _move(self, by):
        i, j = self.sel, self.sel + by
        b = self.deck["buttons"]
        if 0 <= j < len(b):
            b[i], b[j] = b[j], b[i]
            self.sel = j
            self._changed()
            self._render_grid()

    def _delete(self):
        del self.deck["buttons"][self.sel]
        self.sel = min(self.sel, len(self.deck["buttons"]) - 1) if self.deck["buttons"] else None
        self._changed()
        self._render()

    # ---------------------------------------------------------------- pickers
    def _popup(self, title, w=520, h=560):
        top = ctk.CTkToplevel(self, fg_color=BG)
        top.title(title)
        top.geometry(f"{w}x{h}")
        top.transient(self)
        top.after(120, top.grab_set)
        return top

    def _pick_icon(self):
        top = self._popup("Pick an icon")
        search = _entry(top, placeholder_text="Search icons")
        search.pack(fill="x", padx=16, pady=(16, 8))
        groups = ["All"] + [g["name"] for g in icons.load()["groups"]]
        group = ctk.CTkSegmentedButton(top, values=groups, fg_color=SURFACE, selected_color=PRIMARY,
                                       unselected_color=RAISED, unselected_hover_color=LINE, text_color=TEXT,
                                       selected_hover_color=PRIMARY, font=(FONT, 11))
        group.set("All")
        group.pack(fill="x", padx=16)
        body = ctk.CTkScrollableFrame(top, fg_color="transparent")
        body.pack(fill="both", expand=True, padx=10, pady=10)
        data = icons.load()

        def show(*_):
            for w in body.winfo_children():
                w.destroy()
            query = search.get().strip().lower()
            pool = (data["icons"].keys() if group.get() == "All"
                    else next(g["icons"] for g in data["groups"] if g["name"] == group.get()))
            names = [name for name in pool
                     if not query or query in name or any(query in tag for tag in data["icons"][name]["tags"])]
            for i, name in enumerate(names[:300]):
                ctk.CTkButton(body, text="", image=icon_image(name, 26), width=52, height=52, corner_radius=12,
                              fg_color=SURFACE, hover_color=RAISED,
                              command=lambda name=name: (self._edit("icon", name), top.destroy())).grid(
                    row=i // 8, column=i % 8, padx=3, pady=3)

        search.bind("<KeyRelease>", show)
        group.configure(command=show)
        show()

    def _pick_app(self):
        top = self._popup("Installed apps")
        search = _entry(top, placeholder_text="Search apps")
        search.pack(fill="x", padx=16, pady=(16, 8))
        body = ctk.CTkScrollableFrame(top, fg_color="transparent")
        body.pack(fill="both", expand=True, padx=10, pady=(0, 10))
        note = _label(body, "Loading the Start menu...", size=13)
        note.pack(pady=20)
        state = {"apps": []}

        def show(*_):
            for w in body.winfo_children():
                w.destroy()
            q = search.get().strip().lower()
            for name, target in [a for a in state["apps"] if q in a[0].lower()][:200]:
                ctk.CTkButton(body, text=name, anchor="w", height=34, corner_radius=8, fg_color="transparent",
                              hover_color=RAISED, text_color=TEXT, font=(FONT, 13),
                              command=lambda n=name, t=target: self._use_app(n, t, top)).pack(fill="x")

        def load():
            state["apps"] = apps.installed_apps()
            try:
                top.after(0, show)
            except tk.TclError:
                pass

        search.bind("<KeyRelease>", show)
        threading.Thread(target=load, daemon=True).start()

    def _use_app(self, name, target, top):
        b = self._button()
        b["action"] = {"type": "open", "target": target}
        if not b["label"].strip() or b["label"] == "New button":
            b["label"] = name[:24]
        self._changed()
        top.destroy()
        self._render()

    def _pick_file(self):
        try:
            import win32con
            import win32gui
            path, _c, _f = win32gui.GetOpenFileNameW(
                hwndOwner=self.winfo_id(), Title="Choose a program or file",
                Filter="Programs\0*.exe;*.lnk;*.bat;*.cmd\0All files\0*.*\0\0",
                Flags=win32con.OFN_FILEMUSTEXIST | win32con.OFN_EXPLORER | win32con.OFN_NOCHANGEDIR)
        except Exception:               # cancelled
            return
        self._use_path(path)

    def _pick_folder(self):
        path = filedialog.askdirectory(parent=self, title="Choose a folder")
        if path:
            self._use_path(path.replace("/", "\\"))

    def _use_path(self, path):
        import os
        b = self._button()
        b["action"] = {"type": "open", "target": path}
        if not b["label"].strip() or b["label"] == "New button":
            b["label"] = os.path.splitext(os.path.basename(path.rstrip("\\")))[0][:24]
        self._changed()
        self._render()

    def _record(self, entry):
        top = self._popup("Record a shortcut", 380, 220)
        _label(top, "Press the shortcut now", size=15, muted=False).pack(pady=(30, 6))
        shown = _label(top, "", size=22, muted=False)
        shown.pack()
        win = tk.BooleanVar(value=False)
        ctk.CTkCheckBox(top, text="Add the Windows key", variable=win, text_color=MUTED,
                        font=(FONT, 12)).pack(pady=14)

        def on_key(e):
            ctrl, shift, alt = bool(e.state & 0x4), bool(e.state & 0x1), bool(e.state & 0x20000)
            text = format_shortcut(e.keysym, ctrl, alt, shift, win.get())
            if text:
                entry.delete(0, "end")
                entry.insert(0, text)
                self._set_action(keys=text)
                top.destroy()
            return "break"

        top.bind("<KeyPress>", on_key)
        top.focus_force()

    # ---------------------------------------------------------------- save
    def _save(self):
        try:
            clean = validate_deck(self.deck)
        except ValueError as e:
            self.status.configure(text=str(e), text_color="#ff6b5e")
            return
        self.on_save(clean)
        self.status.configure(text="Saved on the tablet", text_color="#39d353")


_open = {"win": None}


def open_editor(master, deck, on_save):
    """Show the editor, or bring the one that is already open to the front."""
    ctk.set_appearance_mode("dark")
    win = _open["win"]
    if win is not None:
        try:
            win.deiconify()
            win.lift()
            win.focus_force()
            return win
        except tk.TclError:
            pass
    win = DeckEditor(master, deck, on_save)
    _open["win"] = win
    return win


if __name__ == "__main__":
    from deck.layout import DeckStore
    store = DeckStore()
    root = ctk.CTk()
    root.withdraw()
    open_editor(root, store.get(), store.set).protocol("WM_DELETE_WINDOW", root.destroy)
    root.mainloop()
