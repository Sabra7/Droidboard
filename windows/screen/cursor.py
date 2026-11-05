"""Draws the Windows mouse pointer into the cast picture.

Desktop duplication (DXGI) delivers the screen without the pointer, so the tablet would show a screen
where nothing seems to move when you touch it. CursorOverlay asks Windows where the pointer is and what
it looks like and blends it into the frame, in the real shape (arrow, hand, text bar, resize ...).
"""
import numpy as np

from screen.touch import use_physical_pixels


CURSOR_SHOWING = 0x1
_MAX_CACHED = 48


def blend(frame, sprite, x, y):
    """Blends `sprite` ((h, w, 4) uint8 as B, G, R, alpha) into `frame` ((H, W, 4) BGRA) with its corner at
    (x, y). Parts outside the frame are cut off. Changes `frame` in place; returns False when nothing was drawn."""
    sh, sw = sprite.shape[:2]
    fh, fw = frame.shape[:2]
    x0, y0 = max(x, 0), max(y, 0)
    x1, y1 = min(x + sw, fw), min(y + sh, fh)
    if x0 >= x1 or y0 >= y1:
        return False
    part = sprite[y0 - y:y1 - y, x0 - x:x1 - x]
    alpha = part[..., 3:4].astype(np.uint16)
    target = frame[y0:y1, x0:x1, :3].astype(np.uint16)
    frame[y0:y1, x0:x1, :3] = ((part[..., :3].astype(np.uint16) * alpha + target * (255 - alpha)) // 255).astype(np.uint8)
    return True


def arrow_sprite(scale=1.0):
    """A plain white arrow with a black edge, for when the real pointer shape cannot be read.
    Returns (sprite, hot_x, hot_y)."""
    from PIL import Image, ImageDraw
    k = 4
    w, h = int(20 * scale), int(30 * scale)
    img = Image.new("RGBA", (w * k, h * k), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    pts = [(1, 1), (1, 24), (6.5, 19), (10.5, 28), (14, 26.5), (10, 18), (17.5, 18)]
    pts = [(px * scale * k, py * scale * k) for px, py in pts]
    d.polygon(pts, fill=(0, 0, 0, 255))
    inner = [(1 + 2, 1 + 4.5), (1 + 2, 19.2), (6.4, 15.2), (11.6, 25.2), (12.4, 24.6), (7.6, 14.4), (13, 14.4)]
    d.polygon([(px * scale * k, py * scale * k) for px, py in inner], fill=(255, 255, 255, 255))
    img = img.resize((w, h), Image.LANCZOS)
    rgba = np.asarray(img)
    sprite = np.dstack((rgba[..., 2], rgba[..., 1], rgba[..., 0], rgba[..., 3]))      # RGBA -> BGRA
    return np.ascontiguousarray(sprite), 0, 0


def alpha_from_renders(on_black, on_white):
    """The pointer drawn twice by Windows, once on black and once on white -> (sprite B, G, R, alpha).

    On black each pixel is alpha * colour, on white it is alpha * colour + (1 - alpha) * 255: the difference
    gives the alpha, and dividing the black render by it gives back the colour."""
    b = on_black[..., :3].astype(np.int32)
    w = on_white[..., :3].astype(np.int32)
    alpha = np.clip(255 - (w - b).mean(axis=2), 0, 255)
    safe = np.maximum(alpha, 1)[..., None]
    color = np.clip(b * 255 // safe, 0, 255)
    out = np.dstack((color, alpha)).astype(np.uint8)
    out[alpha < 4] = 0
    return out


class CursorOverlay:
    """draw(frame) for the display whose top-left corner is at `rect`'s (left, top) on the desktop."""

    def __init__(self, rect):
        self.left, self.top = rect[0], rect[1]
        self._cache = {}
        self._aware = False

    def _physical_pixels(self):
        """Once per overlay: pointer positions in real pixels, like the display rectangle."""
        if not self._aware:
            use_physical_pixels()
            self._aware = True

    def draw(self, frame):
        """The frame with the pointer in it (a copy when the pointer is on this display, else the frame itself)."""
        try:
            import win32gui
            self._physical_pixels()
            flags, handle, (x, y) = win32gui.GetCursorInfo()
        except Exception:
            return frame
        if not flags & CURSOR_SHOWING:
            return frame
        sprite, hot_x, hot_y = self._sprite(handle)
        px, py = x - self.left - hot_x, y - self.top - hot_y
        if px >= frame.shape[1] or py >= frame.shape[0] or px + sprite.shape[1] <= 0 or py + sprite.shape[0] <= 0:
            return frame
        frame = frame.copy()                 # the capture owns the original: never draw on it
        blend(frame, sprite, px, py)
        return frame

    def _sprite(self, handle):
        if handle not in self._cache:
            if len(self._cache) >= _MAX_CACHED:
                self._cache.clear()
            try:
                self._cache[handle] = self._render(handle)
            except Exception:
                self._cache[handle] = arrow_sprite(1.25)
        return self._cache[handle]

    @staticmethod
    def _render(handle):
        """The real pointer image: Windows draws it on black and on white, and the alpha comes from the two."""
        import win32api
        import win32con
        import win32gui
        import win32ui
        _, hot_x, hot_y, mask, color = win32gui.GetIconInfo(handle)
        try:
            size = max(win32api.GetSystemMetrics(win32con.SM_CXCURSOR), win32api.GetSystemMetrics(win32con.SM_CYCURSOR))
        finally:
            for bmp in (mask, color):
                if bmp:
                    win32gui.DeleteObject(bmp)
        screen_dc = win32gui.GetDC(0)
        memory_dc = win32gui.CreateCompatibleDC(screen_dc)
        renders = []
        try:
            for shade in (0x000000, 0xFFFFFF):
                bitmap = win32gui.CreateCompatibleBitmap(screen_dc, size, size)
                old = win32gui.SelectObject(memory_dc, bitmap)
                brush = win32gui.CreateSolidBrush(shade)
                win32gui.FillRect(memory_dc, (0, 0, size, size), brush)
                win32gui.DeleteObject(brush)
                win32gui.DrawIconEx(memory_dc, 0, 0, handle, size, size, 0, 0, win32con.DI_NORMAL)
                raw = win32ui.CreateBitmapFromHandle(bitmap).GetBitmapBits(True)
                renders.append(np.frombuffer(raw, dtype=np.uint8).reshape(size, size, 4).copy())
                win32gui.SelectObject(memory_dc, old)
                win32gui.DeleteObject(bitmap)
        finally:
            win32gui.DeleteDC(memory_dc)
            win32gui.ReleaseDC(0, screen_dc)
        sprite = alpha_from_renders(renders[0], renders[1])
        if not sprite[..., 3].any():
            raise ValueError("an empty pointer")
        return sprite, hot_x, hot_y
