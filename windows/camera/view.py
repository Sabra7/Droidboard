"""A window that shows what the tablet camera sends (for checking it, or when no virtual camera is installed)."""
import tkinter as tk

import customtkinter as ctk
from PIL import Image


BG = "#0e0f12"
_open = {"win": None}


class CameraView(ctk.CTkToplevel):
    def __init__(self, master, preview):
        super().__init__(master, fg_color=BG)
        self.title("Droidboard - Camera")
        self.geometry("800x470+80+60")
        self.minsize(320, 200)
        self.preview = preview
        self.serial = -1
        self.label = ctk.CTkLabel(self, text="Waiting for the tablet camera...", text_color="#9a9da6",
                                  font=("Segoe UI", 14))
        self.label.pack(fill="both", expand=True)
        self.protocol("WM_DELETE_WINDOW", self._close)
        self.after(50, self._tick)

    def _close(self):
        _open["win"] = None
        self.destroy()

    def _tick(self):
        if _open["win"] is not self:
            return
        frame, serial = self.preview.latest()
        if frame is not None and serial != self.serial:
            self.serial = serial
            h, w = frame.shape[:2]
            ww, hh = max(self.label.winfo_width(), 160), max(self.label.winfo_height(), 90)
            k = min(ww / w, hh / h)
            size = (max(1, int(w * k)), max(1, int(h * k)))
            img = Image.fromarray(frame).resize(size, Image.BILINEAR)
            self._image = ctk.CTkImage(light_image=img, dark_image=img, size=size)
            self.label.configure(image=self._image, text="")
        self.after(33, self._tick)


def open_view(master, preview):
    """Show the preview window, or bring the open one to the front."""
    ctk.set_appearance_mode("dark")
    win = _open["win"]
    if win is not None:
        try:
            win.deiconify()
            win.lift()
            return win
        except tk.TclError:
            pass
    win = CameraView(master, preview)
    _open["win"] = win
    return win
