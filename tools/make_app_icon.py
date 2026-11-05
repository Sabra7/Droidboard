"""Draws the Windows icon (shared/droidboard.ico) from the same shapes as the Android launcher icon
(android/app/src/main/res/drawable/ic_launcher_foreground.xml): three deck keys and a green mic on #1C1C1E.

    python tools/make_app_icon.py
"""
import os

from PIL import Image, ImageDraw

S = 1024                     # drawn large, then scaled down for every size of the .ico
K = S / 66                   # the Android safe zone (66 of 108 dp) fills the Windows icon
BG, KEY, MIC = (0x1C, 0x1C, 0x1E), (0xF2, 0xF2, 0xF5), (0x39, 0xD3, 0x53)


def at(v):
    """Android dp (inside the 108 dp canvas) -> pixels of this picture."""
    return (v - 21) * K


def main(out):
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((0, 0, S - 1, S - 1), radius=S * 0.24, fill=BG)
    for x, y in ((36.84, 36.84), (56.64, 36.84), (36.84, 56.64)):
        d.rounded_rectangle((at(x), at(y), at(x + 14.52), at(y + 14.52)), radius=4 * K, fill=KEY)

    def mic(u, v):               # Lucide mic units (24 grid) -> pixels, like the Android group transform
        return at(55.98 + 0.66 * u), at(55.32 + 0.66 * v)

    w = round(3 * 0.66 * K)
    x0, y0 = mic(9, 2)
    x1, y1 = mic(15, 15)
    d.rounded_rectangle((x0, y0, x1, y1), radius=(x1 - x0) / 2, outline=MIC, width=w)
    cx, cy = mic(12, 12)
    r = 7 * 0.66 * K
    d.arc((cx - r, cy - r, cx + r, cy + r), 0, 180, fill=MIC, width=w)
    for u in (5, 19):
        d.line((mic(u, 10), mic(u, 12)), fill=MIC, width=w)
    d.line((mic(12, 19), mic(12, 22)), fill=MIC, width=w)
    img.save(out, sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    print("wrote", out)


if __name__ == "__main__":
    main(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "shared", "droidboard.ico"))
