"""The pieces the status window is built from: colours, icons, cards, number tiles, the chart, rows, the log."""
import tkinter as tk

import customtkinter as ctk

from ui import dashboard
from ui import icons


# (light, dark): the app's colours. Black page and dark-grey cards at night, pearl and white by day.
BG = ("#F5F2EC", "#000000")
CARD = ("#FFFFFF", "#1C1C1E")
RAISED = ("#EEEAE3", "#2C2C2E")
TEXT = ("#1B1B1F", "#F2F2F5")
MUTED = ("#6B6B73", "#9A9AA2")
GOOD = ("#1A7F37", "#39D353")
BAD = ("#C62828", "#FF6B5E")
WARN = ("#9A6700", "#FFB020")
FONT = "Segoe UI"
SEMI = "Segoe UI Semibold"     # titles and numbers: 600, not bold
MONO = "Consolas"


def windows_accent():
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Microsoft\Windows\DWM") as k:
            value, _ = winreg.QueryValueEx(k, "AccentColor")
        return dashboard.accent_pair(value)
    except OSError:
        return dashboard.accent_pair(None)


_images = {}


def icon(name, size, color=TEXT):
    """A Lucide icon that follows light and dark (color is a (light, dark) pair)."""
    key = (name, size, color)
    if key not in _images:
        light, dark = (color, color) if isinstance(color, str) else color
        _images[key] = ctk.CTkImage(light_image=icons.render(name, size * 2, light),
                                    dark_image=icons.render(name, size * 2, dark), size=(size, size))
    return _images[key]


def make_card(master, **kw):
    return ctk.CTkFrame(master, fg_color=CARD, corner_radius=18, **kw)


def card_title(master, text, glyph, accent):
    return ctk.CTkLabel(master, text=f"  {text}", image=icon(glyph, 18, accent), compound="left",
                        font=(SEMI, 15), text_color=TEXT, anchor="w")


def small_button(master, text, glyph, command, accent):
    return ctk.CTkButton(master, text=text, image=icon(glyph, 16, accent), compound="left", command=command,
                         height=30, corner_radius=15, font=(FONT, 12), fg_color=RAISED, hover_color=RAISED,
                         text_color=TEXT, width=0 if text else 30)


def mode_index():
    return 1 if ctk.get_appearance_mode() == "Dark" else 0


class Lamp(ctk.CTkFrame):
    """The small green lamp of something live (ON AIR), the same one the phone app shows."""

    def __init__(self, master, text):
        super().__init__(master, fg_color="transparent", border_width=1, border_color=GOOD, corner_radius=4)
        ctk.CTkLabel(self, text=text, font=(MONO, 10), text_color=GOOD, height=14).pack(padx=5, pady=1)


class Tile:
    """A number with a caption and a small line under it."""

    def __init__(self, master, caption, glyph, size=24):
        self.frame = make_card(master)
        ctk.CTkLabel(self.frame, text=f"  {caption}", image=icon(glyph, 16, MUTED), compound="left",
                     font=(FONT, 12), text_color=MUTED, anchor="w").pack(fill="x", padx=16, pady=(16, 0))
        self.value = ctk.CTkLabel(self.frame, text="-", font=(SEMI, size), text_color=TEXT, anchor="w")
        self.value.pack(fill="x", padx=16)
        self.sub = ctk.CTkLabel(self.frame, text="", font=(FONT, 11), text_color=MUTED, anchor="w")
        self.sub.pack(fill="x", padx=16, pady=(0, 16))

    def set(self, value, sub, alert=False):
        self.value.configure(text=value, text_color=BAD if alert else TEXT)
        self.sub.configure(text=sub)


class Chart:
    """The jitter buffer of the last minute (line), the target (dashed) and gaps / underruns (marks)."""

    def __init__(self, master, accent):
        self.accent = accent
        self.canvas = tk.Canvas(master, height=160, highlightthickness=0, bd=0)
        self.canvas.pack(fill="both", expand=True, padx=16, pady=(4, 16))

    def draw(self, history, target_ms):
        c, m = self.canvas, mode_index()
        c.configure(bg=CARD[m])
        c.delete("all")
        w, h = c.winfo_width(), c.winfo_height()
        if w < 20 or h < 20:
            return
        left, bottom = 44, h - 18
        top = history.top(target_ms)
        y = lambda v: bottom - (bottom - 6) * min(v, top) / top          # noqa: E731
        for v in (0, top / 2, top):
            c.create_line(left, y(v), w, y(v), fill=RAISED[m])
            c.create_text(left - 8, y(v), text=f"{v:g}", anchor="e", fill=MUTED[m], font=(FONT, 9))
        c.create_text(left, h - 4, text="60 s ago", anchor="sw", fill=MUTED[m], font=(FONT, 9))
        c.create_text(w, h - 4, text="now", anchor="se", fill=MUTED[m], font=(FONT, 9))
        c.create_line(left, y(target_ms), w, y(target_ms), fill=MUTED[m], dash=(4, 4))
        step = (w - left) / max(1, history.size - 1)
        start = history.size - len(history.buffer)
        line = []
        for i, (v, event) in enumerate(zip(history.buffer, history.events)):
            x = left + (start + i) * step
            if event:
                c.create_line(x, 6, x, bottom, fill=(BAD if event == "underrun" else WARN)[m], width=2)
            if v is None:
                if len(line) >= 4:
                    c.create_line(*line, fill=self.accent[m], width=2, smooth=True)
                line = []
            else:
                line += [x, y(v)]
        if len(line) >= 4:
            c.create_line(*line, fill=self.accent[m], width=2, smooth=True)


class Rows:
    """A card body made of rows (icon, name, detail). Rebuilt only when the rows themselves change: the text
    on the right (a ticking 'connected for') is updated in place, so the card does not flicker every second."""

    def __init__(self, master, wrap):
        self.frame = ctk.CTkFrame(master, fg_color="transparent")
        self.frame.pack(fill="both", expand=True, padx=16, pady=(4, 16))
        self.wrap = wrap
        self._shown = None
        self._rights = []
        self._actions = []

    def show(self, rows):
        """rows: [(glyph, color, name, detail, right)], optionally a lamp next to the name ("ON AIR") and a
        seventh item, an action that runs when the row is clicked"""
        rows = [(*row, "", None)[:7] if len(row) == 5 else (*row, None) if len(row) == 6 else tuple(row) for row in rows]
        self._actions = [row[6] for row in rows]          # kept fresh even when the rows are not rebuilt
        shape = [(*row[:4], row[5], row[6] is not None) for row in rows]
        if shape == self._shown:
            for label, (*_, right, _lamp, _action) in zip(self._rights, rows):
                if label.cget("text") != right:
                    label.configure(text=right)
            return
        self._shown = shape
        self._rights = []
        for child in self.frame.winfo_children():
            child.destroy()
        for index, (glyph, color, name, detail, right, lamp, action) in enumerate(rows):
            row = ctk.CTkFrame(self.frame, fg_color="transparent")
            row.pack(fill="x", pady=4)
            ctk.CTkLabel(row, text="", image=icon(glyph, 18, color), width=24).pack(side="left", anchor="n",
                                                                                pady=2)
            body = ctk.CTkFrame(row, fg_color="transparent")
            body.pack(side="left", fill="x", expand=True, padx=(8, 0))
            head = ctk.CTkFrame(body, fg_color="transparent")
            head.pack(fill="x")
            ctk.CTkLabel(head, text=name, font=(SEMI, 13), text_color=TEXT, anchor="w",
                         height=20).pack(side="left")
            if lamp:
                Lamp(head, lamp).pack(side="left", padx=(8, 0))
            if detail:
                ctk.CTkLabel(body, text=detail, font=(FONT, 12), text_color=MUTED, anchor="w", justify="left",
                             wraplength=self.wrap, height=18).pack(fill="x")
            right_label = ctk.CTkLabel(row, text=right, font=(FONT, 11), text_color=MUTED, height=20)
            right_label.pack(side="right", anchor="n")
            self._rights.append(right_label)
            if action is not None:
                self._clickable(row, index)

    def _clickable(self, widget, index):
        """The whole row (every label in it) runs the row's current action on a click."""
        widget.configure(cursor="hand2")
        widget.bind("<Button-1>", lambda _e: self._actions[index] and self._actions[index]())
        for child in widget.winfo_children():
            self._clickable(child, index)


class LogView:
    """The live log with a filter. Errors in red, warnings in amber."""

    MAX_LINES = 1500

    def __init__(self, master, accent, on_copy, on_open):
        head = ctk.CTkFrame(master, fg_color="transparent")
        head.pack(fill="x", padx=16, pady=(16, 8))
        card_title(head, "Log", "file-text", accent).pack(side="left")
        small_button(head, "", "folder-open", on_open, accent).pack(side="right")
        small_button(head, "", "copy", on_copy, accent).pack(side="right", padx=(0, 8))
        self.filter = ctk.CTkSegmentedButton(master, values=list(dashboard.FILTERS), command=lambda _: self.reload(),
                                             font=(FONT, 12), fg_color=RAISED, unselected_color=RAISED,
                                             selected_color=accent, selected_hover_color=accent,
                                             unselected_hover_color=RAISED, text_color=TEXT)
        self.filter.set("All")
        self.filter.pack(fill="x", padx=16)
        self.text = ctk.CTkTextbox(master, font=(MONO, 12), wrap="none", fg_color=BG, text_color=TEXT,
                                   corner_radius=12, activate_scrollbars=True)
        self.text.pack(fill="both", expand=True, padx=16, pady=(8, 16))
        self.text.configure(state="disabled")
        self.seen = 0
        self.lines = 0
        self.mode_index = None

    def _colors(self):
        m = mode_index()
        if m != self.mode_index:
            self.mode_index = m
            self.text.tag_config("error", foreground=BAD[m])
            self.text.tag_config("warn", foreground=WARN[m])
            self.text.tag_config("time", foreground=MUTED[m])

    def reload(self):
        self.seen = 0
        self.lines = 0
        self.text.configure(state="normal")
        self.text.delete("1.0", "end")
        self.text.configure(state="disabled")
        self.pull()

    def pull(self):
        from ui import logsetup
        self._colors()
        new = logsetup.RECENT.since(self.seen)
        if not new:
            return
        self.seen = new[-1][0] + 1
        name = self.filter.get()
        new = [e for e in new if dashboard.shows(name, e[2])]
        if not new:
            return
        at_end = self.text.yview()[1] > 0.98
        self.text.configure(state="normal")
        for _, stamp, line in new:
            self.text.insert("end", f"{stamp:%H:%M:%S} ", "time")
            level = dashboard.classify(line)[1]
            self.text.insert("end", line + "\n", level or None)
        self.lines += len(new)
        if self.lines > self.MAX_LINES:
            self.text.delete("1.0", f"{self.lines - self.MAX_LINES + 1}.0")
            self.lines = self.MAX_LINES
        self.text.configure(state="disabled")
        if at_end:
            self.text.see("end")
