"""Builds shared/icons.json, the icon set used by the tablet app and the Windows editor.

The icons come from Lucide (ISC license, https://lucide.dev). Every shape is turned into an SVG
path, so both sides only need a path parser. Run after `npm pack lucide-static`:

    python tools/build_icons.py <folder of the unpacked lucide-static package>
"""
import json
import os
import shutil
import sys

GROUPS = {
    "Media": "mic mic-off mic-vocal headphones headset volume volume-1 volume-2 volume-x speaker bell bell-off "
             "megaphone music music-2 music-3 music-4 piano guitar drum radio tv film clapperboard video "
             "video-off camera camera-off play pause square skip-back skip-forward rewind fast-forward repeat "
             "repeat-1 shuffle circle-play circle-pause circle-stop podcast disc-3 audio-lines "
             "audio-waveform captions",
    "Devices": "laptop monitor monitor-smartphone smartphone tablet keyboard mouse mouse-pointer-2 printer "
               "hard-drive usb cable plug plug-zap battery-full battery-charging cpu memory-stick server "
               "router wifi wifi-off bluetooth signal gamepad-2 joystick webcam scan qr-code lightbulb lamp "
               "fan thermometer power power-off",
    "Files": "folder folder-open file file-text file-code file-image file-video file-audio archive clipboard "
             "clipboard-copy clipboard-paste copy scissors trash-2 save download upload paperclip pin "
             "bookmark tag calendar calendar-days clock timer alarm-clock hourglass mail inbox send "
             "briefcase notebook book-open library graduation-cap pencil pen-tool brush ruler calculator "
             "chart-bar chart-line chart-pie wallet credit-card receipt package",
    "System": "settings settings-2 sliders-horizontal wrench hammer terminal square-terminal code braces bug "
              "git-branch github database cloud cloud-upload shield shield-check lock lock-open key-round "
              "fingerprint eye eye-off search zoom-in zoom-out refresh-cw rotate-cw undo-2 redo-2 log-out "
              "log-in house menu layout-grid app-window panel-left maximize minimize expand shrink move",
    "Web": "globe link external-link share-2 rss message-circle message-square messages-square phone "
           "phone-call at-sign hash user users user-plus heart star thumbs-up thumbs-down flag map-pin map "
           "compass navigation shopping-cart shopping-bag store gift",
    "Actions": "arrow-up arrow-down arrow-left arrow-right chevron-up chevron-down chevrons-up chevrons-down "
               "corner-down-left plus minus x check circle-check circle-x circle-alert triangle-alert info "
               "circle-help ban zap flame sparkles rocket target trophy crown",
    "Nature": "sun moon sun-moon cloud-rain cloud-lightning snowflake wind droplet leaf tree-pine flower "
              "sprout bird cat dog fish paw-print smile laugh frown ghost skull bot brain",
    "Food": "coffee cup-soda beer wine pizza sandwich croissant cookie ice-cream-cone apple cherry carrot "
            "egg-fried soup utensils cake candy popcorn",
    "Travel": "car bus bike plane train-front ship fuel tent mountain building-2 hospital school dice-5 puzzle "
              "swords sword crosshair volleyball dumbbell medal ticket",
}


def ell(cx, cy, rx, ry):
    return (f"M{cx - rx} {cy}a{rx} {ry} 0 1 0 {2 * rx} 0a{rx} {ry} 0 1 0 {-2 * rx} 0z")


def rect(x, y, w, h, rx, ry):
    rx, ry = min(rx or ry or 0, w / 2), min(ry or rx or 0, h / 2)
    if not rx and not ry:
        return f"M{x} {y}h{w}v{h}h{-w}z"
    return (f"M{x + rx} {y}h{w - 2 * rx}a{rx} {ry} 0 0 1 {rx} {ry}v{h - 2 * ry}a{rx} {ry} 0 0 1 {-rx} {ry}"
            f"h{-(w - 2 * rx)}a{rx} {ry} 0 0 1 {-rx} {-ry}v{-(h - 2 * ry)}a{rx} {ry} 0 0 1 {rx} {-ry}z")


def to_path(tag, a):
    f = lambda k, d=0: float(a.get(k, d))
    if tag == "path":
        return a["d"]
    if tag == "circle":
        return ell(f("cx"), f("cy"), f("r"), f("r"))
    if tag == "ellipse":
        return ell(f("cx"), f("cy"), f("rx"), f("ry"))
    if tag == "rect":
        return rect(f("x"), f("y"), f("width"), f("height"), f("rx"), f("ry"))
    if tag == "line":
        return f"M{a['x1']} {a['y1']}L{a['x2']} {a['y2']}"
    if tag in ("polyline", "polygon"):
        pts = a["points"].replace(",", " ").split()
        d = "M" + " ".join(pts[:2]) + "".join(f"L{pts[i]} {pts[i + 1]}" for i in range(2, len(pts), 2))
        return d + ("z" if tag == "polygon" else "")
    raise ValueError(tag)


def nodes_from_svg(path):
    """Fallback for icons that icon-nodes.json does not list: read the .svg file itself."""
    import re
    import xml.etree.ElementTree as ET
    text = re.sub(r"<!--.*?-->", "", open(path, encoding="utf-8").read(), flags=re.S)
    root = ET.fromstring(text)
    return [(el.tag.split("}")[-1], dict(el.attrib)) for el in root]


def main(pkg, out):
    nodes = json.load(open(os.path.join(pkg, "icon-nodes.json"), encoding="utf-8"))
    tags = json.load(open(os.path.join(pkg, "tags.json"), encoding="utf-8"))
    icons, groups, seen = {}, [], set()
    for group, names in GROUPS.items():
        picked = []
        for name in names.split():
            if name in seen:
                continue
            svg = os.path.join(pkg, "icons", name + ".svg")
            if name not in nodes and not os.path.exists(svg):
                print(f"missing in this Lucide version: {name}")
                continue
            seen.add(name)
            picked.append(name)
            shapes = nodes[name] if name in nodes else nodes_from_svg(svg)
            icons[name] = {"d": [to_path(t, a) for t, a in shapes], "tags": tags.get(name, [])}
        groups.append({"name": group, "icons": picked})
    data = {"source": "Lucide (ISC license), see LICENSE-lucide.txt", "viewBox": 24, "groups": groups,
            "icons": icons}
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, "icons.json"), "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
    shutil.copy(os.path.join(pkg, "LICENSE"), os.path.join(out, "LICENSE-lucide.txt"))
    print(f"{len(icons)} icons in {len(groups)} groups -> {out}")


if __name__ == "__main__":
    main(sys.argv[1], os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "shared"))
