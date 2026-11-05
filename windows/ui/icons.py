"""The Droidboard icon set (Lucide, see shared/LICENSE-lucide.txt): load it and draw it with Pillow.

Icons are stroke-only SVG paths on a 24x24 grid. There is no SVG library: `flatten` turns a path
into polylines (lines, curves and arcs) and `render` draws them. The tablet app reads the same
shared/icons.json.
"""
import json
import math
import re

import paths


PATH = paths.shared("icons.json")
_CMD = re.compile(r"([MmLlHhVvCcSsQqTtAaZz])|(-?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?)")
_ARGS = {"M": 2, "L": 2, "H": 1, "V": 1, "C": 6, "S": 4, "Q": 4, "T": 2, "A": 7, "Z": 0}

_cache = None


def load(path=PATH):
    """{'groups': [{'name', 'icons': [names]}], 'icons': {name: {'d': [paths], 'tags': []}}}"""
    global _cache
    if _cache is None or path != PATH:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        if path != PATH:
            return data
        _cache = data
    return _cache


def names():
    return list(load()["icons"])


def exists(name):
    return name in load()["icons"]


_NUM = re.compile(r"-?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?")
_SEP = re.compile(r"[\s,]*")


def _commands(d):
    """Yield (command letter, [numbers]) for every argument group. Arc flags are single characters
    and may be glued to the next number ('a1 1 0 011 1'), so they are read one character at a time."""
    pos, n, cmd = 0, len(d), None
    while True:
        pos = _SEP.match(d, pos).end()
        if pos >= n:
            return
        ch = d[pos]
        if ch.isalpha():
            if ch not in _ARGS and ch.upper() not in _ARGS:
                raise ValueError(f"unknown command {ch}")
            cmd = ch
            pos += 1
            if cmd in "Zz":
                yield cmd, []
                continue
        elif cmd is None or cmd in "Zz":
            raise ValueError("path data must start with a command")
        args = []
        for k in range(_ARGS[cmd.upper()]):
            pos = _SEP.match(d, pos).end()
            if cmd in "Aa" and k in (3, 4):
                if pos >= n or d[pos] not in "01":
                    raise ValueError("bad arc flag")
                args.append(float(d[pos]))
                pos += 1
                continue
            m = _NUM.match(d, pos)
            if not m:
                raise ValueError(f"bad arguments for {cmd}")
            args.append(float(m.group()))
            pos = m.end()
        yield cmd, args
        if cmd in "Mm":
            cmd = "l" if cmd == "m" else "L"        # extra pairs after a move are lines


def _arc(x1, y1, rx, ry, phi, fa, fs, x2, y2, steps=24):
    """Points of an SVG elliptical arc (endpoint form), excluding the start point."""
    if rx == 0 or ry == 0 or (x1 == x2 and y1 == y2):
        return [(x2, y2)]
    rx, ry = abs(rx), abs(ry)
    cp, sp = math.cos(math.radians(phi)), math.sin(math.radians(phi))
    dx, dy = (x1 - x2) / 2, (y1 - y2) / 2
    x1p, y1p = cp * dx + sp * dy, -sp * dx + cp * dy
    lam = x1p ** 2 / rx ** 2 + y1p ** 2 / ry ** 2
    if lam > 1:
        rx, ry = rx * math.sqrt(lam), ry * math.sqrt(lam)
    num = rx ** 2 * ry ** 2 - rx ** 2 * y1p ** 2 - ry ** 2 * x1p ** 2
    den = rx ** 2 * y1p ** 2 + ry ** 2 * x1p ** 2
    co = math.sqrt(max(0.0, num / den)) * (-1 if fa == fs else 1)
    cxp, cyp = co * rx * y1p / ry, -co * ry * x1p / rx
    cx, cy = cp * cxp - sp * cyp + (x1 + x2) / 2, sp * cxp + cp * cyp + (y1 + y2) / 2

    def ang(ux, uy, vx, vy):
        a = math.atan2(ux * vy - uy * vx, ux * vx + uy * vy)
        return a

    t1 = ang(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry)
    dt = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
    if not fs and dt > 0:
        dt -= 2 * math.pi
    elif fs and dt < 0:
        dt += 2 * math.pi
    pts = []
    for i in range(1, steps + 1):
        t = t1 + dt * i / steps
        x, y = rx * math.cos(t), ry * math.sin(t)
        pts.append((cp * x - sp * y + cx, sp * x + cp * y + cy))
    pts[-1] = (x2, y2)
    return pts


def _bezier(pts, steps=16):
    n = len(pts) - 1
    out = []
    for i in range(1, steps + 1):
        t = i / steps
        x = y = 0.0
        for k, (px, py) in enumerate(pts):
            w = math.comb(n, k) * (t ** k) * ((1 - t) ** (n - k))
            x += w * px
            y += w * py
        out.append((x, y))
    return out


def flatten(d):
    """SVG path data -> list of (points, closed). Raises ValueError on a path it cannot read."""
    subs, cur = [], None
    x = y = sx = sy = 0.0
    last_c = last_q = None          # last control point, for S and T
    for cmd, a in _commands(d):
        if cmd in "Zz":
            if cur:
                subs.append((cur, True))
                cur = None
            x, y = sx, sy
            last_c = last_q = None
            continue
        up = cmd.upper()
        rel = cmd.islower()
        ox, oy = (x, y) if rel else (0.0, 0.0)
        if up == "M":
            if cur:
                subs.append((cur, False))
            x, y = a[0] + ox, a[1] + oy
            sx, sy = x, y
            cur = [(x, y)]
            last_c = last_q = None
            continue
        if cur is None:
            cur = [(x, y)]
        if up == "L":
            x, y = a[0] + ox, a[1] + oy
            cur.append((x, y))
            last_c = last_q = None
        elif up == "H":
            x = a[0] + (x if rel else 0)
            cur.append((x, y))
            last_c = last_q = None
        elif up == "V":
            y = a[0] + (y if rel else 0)
            cur.append((x, y))
            last_c = last_q = None
        elif up == "C":
            p = [(x, y), (a[0] + ox, a[1] + oy), (a[2] + ox, a[3] + oy), (a[4] + ox, a[5] + oy)]
            cur += _bezier(p)
            x, y = p[3]
            last_c, last_q = p[2], None
        elif up == "S":
            c1 = (2 * x - last_c[0], 2 * y - last_c[1]) if last_c else (x, y)
            p = [(x, y), c1, (a[0] + ox, a[1] + oy), (a[2] + ox, a[3] + oy)]
            cur += _bezier(p)
            x, y = p[3]
            last_c, last_q = p[2], None
        elif up == "Q":
            p = [(x, y), (a[0] + ox, a[1] + oy), (a[2] + ox, a[3] + oy)]
            cur += _bezier(p)
            x, y = p[2]
            last_q, last_c = p[1], None
        elif up == "T":
            c1 = (2 * x - last_q[0], 2 * y - last_q[1]) if last_q else (x, y)
            p = [(x, y), c1, (a[0] + ox, a[1] + oy)]
            cur += _bezier(p)
            x, y = p[2]
            last_q, last_c = c1, None
        elif up == "A":
            cur += _arc(x, y, a[0], a[1], a[2], int(a[3]), int(a[4]), a[5] + ox, a[6] + oy)
            x, y = a[5] + ox, a[6] + oy
            last_c = last_q = None
    if cur:
        subs.append((cur, False))
    return subs


def render(name, size, color="#ffffff", stroke=2.0):
    """RGBA Pillow image of the icon, `size` pixels square, drawn 4x and scaled down for smooth edges."""
    from PIL import Image, ImageDraw
    big = size * 4
    k = big / 24.0
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    w = max(1, round(stroke * k))
    r = w / 2
    icon = load()["icons"].get(name)
    for d in (icon["d"] if icon else []):
        for pts, closed in flatten(d):
            xy = [(px * k, py * k) for px, py in pts]
            if closed and xy[0] != xy[-1]:
                xy.append(xy[0])
            if len(xy) > 1:
                draw.line(xy, fill=color, width=w, joint="curve")
            for px, py in (xy[0], xy[-1]) if len(xy) > 1 else xy:      # round caps
                draw.ellipse([px - r, py - r, px + r, py + r], fill=color)
    return img.resize((size, size), Image.LANCZOS)


# the emoji icons of the first deck version, mapped to icon names
EMOJI_TO_NAME = {
    "\U0001F399": "mic", "⏯": "circle-play", "⏮": "skip-back", "⏭": "skip-forward",
    "\U0001F509": "volume-1", "\U0001F50A": "volume-2", "\U0001F507": "volume-x", "\U0001F512": "lock",
    "⭐": "star", "\U0001F3A4": "mic-vocal", "\U0001F3A7": "headphones",
}


def migrate_icon(value, fallback="zap"):
    """An icon name is kept; an old emoji becomes the matching name (or `fallback`)."""
    if not value:
        return ""
    if re.fullmatch(r"[a-z0-9-]{1,40}", value):
        return value
    return EMOJI_TO_NAME.get(value.replace("️", ""), fallback)
