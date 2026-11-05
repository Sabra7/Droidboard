"""Touch from the tablet to mouse input on Windows.

TouchMapper is pure (no Windows calls, a clock you can replace) and turns finger events into mouse
operations. WindowsInjector performs those operations through pywin32.

Gestures: one finger tap = click, hold = right click, drag = drag, two fingers = scroll.
Positions are fractions of the picture (0..1).
"""
import time


TAP_SLOP = 0.012         # a finger that stays inside this distance (fraction of the picture) is still a tap
LONG_PRESS_S = 0.55
SCROLL_STEP = 0.02       # how far two fingers move for one wheel notch (fraction of the height)
WHEEL_NOTCH = 120

DOWN, UP, MOVE, CANCEL = 0, 1, 2, 3

DPI_AWARENESS_PER_MONITOR_V2 = -4      # DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2


def use_physical_pixels():
    """Makes the calling thread count in real pixels, like the display rectangles do.

    Without it, positions are off on a scaled (125 %) screen. Old Windows without the call: nothing happens."""
    import ctypes
    try:
        ctypes.windll.user32.SetThreadDpiAwarenessContext(ctypes.c_void_p(DPI_AWARENESS_PER_MONITOR_V2))
    except (AttributeError, OSError):
        pass


class TouchMapper:
    """Feed it the finger events; it returns a list of operations:
    ("move", x, y) | ("down",) | ("up",) | ("click",) | ("right_click",) | ("wheel", delta)."""

    def __init__(self, clock=time.monotonic):
        self.clock = clock
        self.pointers = {}               # id -> [x, y]
        self.mode = "idle"               # idle | press | drag | scroll | ignore
        self.start = (0.0, 0.0)
        self.start_t = 0.0
        self._scroll_y = 0.0
        self._scroll_rest = 0.0

    def _center_y(self):
        return sum(p[1] for p in self.pointers.values()) / len(self.pointers)

    def handle(self, action, pointer, x, y):
        ops = []
        if action == DOWN:
            self.pointers[pointer] = [x, y]
            if len(self.pointers) == 1:
                self.mode, self.start, self.start_t = "press", (x, y), self.clock()
                ops.append(("move", x, y))
            else:
                if self.mode == "drag":
                    ops.append(("up",))              # a second finger turns a drag into a scroll
                self.mode = "scroll"
                self._scroll_y, self._scroll_rest = self._center_y(), 0.0
        elif action == MOVE and pointer in self.pointers:
            self.pointers[pointer] = [x, y]
            if self.mode == "press":
                if abs(x - self.start[0]) > TAP_SLOP or abs(y - self.start[1]) > TAP_SLOP:
                    self.mode = "drag"
                    ops += [("move", *self.start), ("down",), ("move", x, y)]
            elif self.mode == "drag":
                ops.append(("move", x, y))
            elif self.mode == "scroll":
                cy = self._center_y()
                self._scroll_rest += cy - self._scroll_y
                self._scroll_y = cy
                notches = int(self._scroll_rest / SCROLL_STEP)
                if notches:
                    self._scroll_rest -= notches * SCROLL_STEP
                    ops.append(("wheel", notches * WHEEL_NOTCH))     # fingers up (y gets smaller) = content down
        elif action in (UP, CANCEL) and pointer in self.pointers:
            del self.pointers[pointer]
            if self.mode == "press" and not self.pointers:
                ops.append(("click",) if action == UP else ("move", *self.start))
            elif self.mode == "drag":
                ops.append(("up",))
            if not self.pointers:
                self.mode = "idle"
            elif self.mode == "scroll" and len(self.pointers) == 1:
                self.mode = "ignore"                 # one finger is left after a scroll: do nothing until it lifts
        return ops

    def tick(self):
        """Call a few times a second: a finger that has not moved for a while is a right click."""
        if self.mode == "press" and self.clock() - self.start_t >= LONG_PRESS_S:
            self.mode = "ignore"
            return [("right_click",)]
        return []


class WindowsInjector:
    """Performs mouse operations on the monitor at `rect` (left, top, right, bottom in pixels)."""

    def __init__(self, rect):
        self.rect = rect
        self._aware = False

    def _physical_pixels(self):
        """Once per injector, or SetCursorPos is off on a scaled screen."""
        if not self._aware:
            use_physical_pixels()
            self._aware = True

    def apply(self, ops):
        import win32api
        import win32con
        self._physical_pixels()
        left, top, right, bottom = self.rect
        for op in ops:
            kind = op[0]
            if kind == "move":
                win32api.SetCursorPos((int(left + op[1] * (right - left)), int(top + op[2] * (bottom - top))))
            elif kind == "down":
                win32api.mouse_event(win32con.MOUSEEVENTF_LEFTDOWN, 0, 0, 0, 0)
            elif kind == "up":
                win32api.mouse_event(win32con.MOUSEEVENTF_LEFTUP, 0, 0, 0, 0)
            elif kind == "click":
                win32api.mouse_event(win32con.MOUSEEVENTF_LEFTDOWN, 0, 0, 0, 0)
                win32api.mouse_event(win32con.MOUSEEVENTF_LEFTUP, 0, 0, 0, 0)
            elif kind == "right_click":
                win32api.mouse_event(win32con.MOUSEEVENTF_RIGHTDOWN, 0, 0, 0, 0)
                win32api.mouse_event(win32con.MOUSEEVENTF_RIGHTUP, 0, 0, 0, 0)
            elif kind == "wheel":
                win32api.mouse_event(win32con.MOUSEEVENTF_WHEEL, 0, 0, op[1], 0)
