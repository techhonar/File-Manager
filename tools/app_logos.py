#!/usr/bin/env python3
"""Draw the app logos offered under Theme > App logo.

Writes each logo as Android resources - a background, a foreground and a
monochrome layer (for themed icons on Android 13 and newer), and the adaptive
icon combining them - plus preview.html beside this script, to look at them
all together in a browser before committing.

    tools/app_logos.py app/src/main/res

The shapes are drawn on the 108dp adaptive-icon canvas and kept inside the
middle 66dp, which every launcher's mask leaves visible. AppLogos.kt lists the
same logos in the same order, and the manifest has an activity-alias for each.
"""
import math, os, sys

RES = sys.argv[1] if len(sys.argv) > 1 else None
OUT = os.path.dirname(os.path.abspath(__file__))  # preview.html goes here

W = "#FFFFFF"

# ---- glyphs: list of (pathData, role) ; role: "main", "soft" (alpha .55), "detail" (drawn in bg colour, not in monochrome)
FOLDER_BACK = "M30,36h18l6,7h24a4,4 0 0 1 4,4v30a4,4 0 0 1 -4,4h-48a4,4 0 0 1 -4,-4v-37a4,4 0 0 1 4,-4z"
FOLDER_FRONT = "M34,54h48a3,3 0 0 1 3,3v20a4,4 0 0 1 -4,4h-46a4,4 0 0 1 -4,-4v-20a3,3 0 0 1 3,-3z"
OPEN_FRONT = "M37,53H83Q86.5,53 85.7,56.4L80.6,77.6Q79.8,81 76.3,81H29.5Q26,81 26.8,77.6L31.9,56.4Q32.7,53 37,53Z"

def star(cx, cy, r_out, r_in, points=5, rot=-90):
    pts = []
    for i in range(points * 2):
        r = r_out if i % 2 == 0 else r_in
        a = math.radians(rot + i * 180 / points)
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return "M" + "L".join(f"{x:.2f},{y:.2f}" for x, y in pts) + "Z"

def circle(cx, cy, r):
    return f"M{cx - r},{cy}a{r},{r} 0 1 0 {2*r},0a{r},{r} 0 1 0 {-2*r},0z"

def rrect(x, y, w, h, r):
    return (f"M{x+r},{y}h{w-2*r}a{r},{r} 0 0 1 {r},{r}v{h-2*r}a{r},{r} 0 0 1 {-r},{r}"
            f"h{-(w-2*r)}a{r},{r} 0 0 1 {-r},{-r}v{-(h-2*r)}a{r},{r} 0 0 1 {r},{-r}z")

GLYPHS = {
    "folder": [(FOLDER_BACK, "main"), (FOLDER_FRONT, "soft")],
    "open": [(FOLDER_BACK, "soft"), (OPEN_FRONT, "main")],
    "star": [(FOLDER_BACK, "main"), (FOLDER_FRONT, "soft"), (star(58, 66, 10, 4.2), "detail")],
    "docs": [(rrect(42, 27, 34, 44, 5), "soft"),
             (rrect(32, 36, 36, 46, 5), "main"),
             (rrect(39, 48, 22, 4, 2), "detail"), (rrect(39, 57, 22, 4, 2), "detail"), (rrect(39, 66, 14, 4, 2), "detail")],
    "box": [(rrect(27, 32, 54, 13, 4), "main"), (rrect(31, 47, 46, 34, 5), "soft"), (rrect(46, 54, 16, 6, 3), "detail")],
    "cloud": [(circle(41, 63, 12), "main"), (circle(56, 54, 15), "main"), (circle(69, 64, 11), "main"), (rrect(41, 59, 28, 16, 0.01), "main")],
    "grid": [(rrect(31, 31, 21, 21, 6), "main"), (rrect(56, 31, 21, 21, 6), "soft"), (rrect(31, 56, 21, 21, 6), "soft"), (rrect(56, 56, 21, 21, 6), "main")],
    "monogram": [(rrect(35, 29, 12, 51, 5), "main"), (rrect(35, 29, 40, 12, 5), "main"), (rrect(35, 50, 32, 11, 5), "main")],
    "search": [(FOLDER_BACK, "main"), (FOLDER_FRONT, "soft"), (circle(59, 64, 8.5), "ring"), ("M65.2,70.2L72.5,77.5", "handle")],
    "bolt": [(FOLDER_BACK, "main"), (FOLDER_FRONT, "soft"), ("M60,56L50,68H57L54,79L65,66H58Z", "detail")],
    "heart": [(FOLDER_BACK, "main"), (FOLDER_FRONT, "soft"),
              ("M58,78C58,78 47,71 47,64.5C47,61 49.7,58.5 52.8,58.5C55,58.5 57,59.8 58,61.6C59,59.8 61,58.5 63.2,58.5C66.3,58.5 69,61 69,64.5C69,71 58,78 58,78Z", "detail")],
    "stack": [(rrect(30, 30, 48, 12, 5), "soft"), (rrect(30, 47, 48, 12, 5), "main"), (rrect(30, 64, 48, 14, 5), "main"), (circle(70, 71, 2.6), "detail"), (circle(70, 53, 2.6), "detail")],
}

# ---- logos: (name, glyph, background, glyph colour)
# background: ("solid", "#hex") or ("linear", "#from", "#to") diagonal
LOGOS = [
    ("Classic", "folder", ("solid", "#1565C0"), W),
    ("Midnight", "folder", ("solid", "#0F172A"), "#60A5FA"),
    ("Paper", "folder", ("solid", "#FFFFFF"), "#1565C0"),
    ("Sunset", "folder", ("linear", "#F97316", "#DB2777"), W),
    ("Aurora", "folder", ("linear", "#6366F1", "#EC4899"), W),
    ("Mint", "open", ("linear", "#34D399", "#0F766E"), W),
    ("Grape", "open", ("solid", "#7C3AED"), W),
    ("Coral", "open", ("solid", "#F4511E"), W),
    ("Favourite", "star", ("solid", "#F59E0B"), W),
    ("Gold", "star", ("solid", "#111111"), "#FBBF24"),
    ("Documents", "docs", ("solid", "#2563EB"), W),
    ("Archive", "box", ("solid", "#B45309"), W),
    ("Cloud", "cloud", ("linear", "#38BDF8", "#1D4ED8"), W),
    ("Tiles", "grid", ("linear", "#8B5CF6", "#EC4899"), W),
    ("Emerald", "grid", ("solid", "#059669"), W),
    ("Letter", "monogram", ("solid", "#DC2626"), W),
    ("Ink", "monogram", ("solid", "#000000"), W),
    ("Explorer", "search", ("solid", "#0284C7"), W),
    ("Charge", "bolt", ("solid", "#16A34A"), W),
    ("Storage", "stack", ("solid", "#475569"), W),
]
assert len(LOGOS) == 20

def bg_colour(bg):
    return bg[1] if bg[0] == "solid" else None

def glyph_elems(glyph, colour, bg, mono=False):
    """(pathData, fill, alpha, stroke?) per element."""
    out = []
    solid = bg_colour(bg) or "#000000"
    for path, role in GLYPHS[glyph]:
        if role == "main":
            out.append((path, W if mono else colour, 1.0, None))
        elif role == "soft":
            out.append((path, W if mono else colour, 0.55, None))
        elif role == "detail":
            if not mono:
                out.append((path, solid, 1.0, None))
        elif role == "ring":
            if not mono:
                out.append((path, None, 1.0, (solid, 4)))
        elif role == "handle":
            if not mono:
                out.append((path, None, 1.0, (solid, 4.5)))
    return out

def android_vector(elems):
    lines = ['<?xml version="1.0" encoding="utf-8"?>',
             '<!-- Generated by tools/app_logos.py: edit that, not this. -->',
             '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
             '    android:width="108dp"', '    android:height="108dp"',
             '    android:viewportWidth="108"', '    android:viewportHeight="108">']
    for path, fill, alpha, stroke in elems:
        attrs = [f'android:pathData="{path}"']
        if fill:
            attrs.append(f'android:fillColor="{fill}"')
            if alpha < 1:
                attrs.append(f'android:fillAlpha="{alpha}"')
        if stroke:
            attrs += [f'android:strokeColor="{stroke[0]}"', f'android:strokeWidth="{stroke[1]}"', 'android:strokeLineCap="round"']
        lines.append("    <path\n        " + "\n        ".join(attrs) + " />")
    lines.append("</vector>")
    return "\n".join(lines) + "\n"

def android_background(bg):
    head = ['<?xml version="1.0" encoding="utf-8"?>',
            '<!-- Generated by tools/app_logos.py: edit that, not this. -->',
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
            '    xmlns:aapt="http://schemas.android.com/aapt"',
            '    android:width="108dp"', '    android:height="108dp"',
            '    android:viewportWidth="108"', '    android:viewportHeight="108">']
    if bg[0] == "solid":
        body = [f'    <path android:pathData="M0,0h108v108h-108z" android:fillColor="{bg[1]}" />']
    else:
        body = ['    <path android:pathData="M0,0h108v108h-108z">',
                '        <aapt:attr name="android:fillColor">',
                '            <gradient android:type="linear" android:startX="0" android:startY="0" android:endX="108" android:endY="108">',
                f'                <item android:offset="0" android:color="{bg[1]}" />',
                f'                <item android:offset="1" android:color="{bg[2]}" />',
                '            </gradient>', '        </aapt:attr>', '    </path>']
    return "\n".join(head + body + ["</vector>"]) + "\n"

def adaptive(n):
    return (f'<?xml version="1.0" encoding="utf-8"?>\n'
            f'<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            f'    <background android:drawable="@drawable/ic_logo_{n:02d}_background" />\n'
            f'    <foreground android:drawable="@drawable/ic_logo_{n:02d}_foreground" />\n'
            f'    <monochrome android:drawable="@drawable/ic_logo_{n:02d}_monochrome" />\n'
            f'</adaptive-icon>\n')

def svg(bg, elems, n, name):
    defs = ""
    if bg[0] == "solid":
        fill = bg[1]
    else:
        defs = f'<defs><linearGradient id="g{n}" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="{bg[1]}"/><stop offset="1" stop-color="{bg[2]}"/></linearGradient></defs>'
        fill = f"url(#g{n})"
    parts = [f'<rect width="108" height="108" fill="{fill}"/>']
    for path, f, alpha, stroke in elems:
        if stroke:
            parts.append(f'<path d="{path}" fill="none" stroke="{stroke[0]}" stroke-width="{stroke[1]}" stroke-linecap="round"/>')
        else:
            parts.append(f'<path d="{path}" fill="{f}" fill-opacity="{alpha}"/>')
    return (f'<figure><svg viewBox="18 18 72 72" width="96" height="96"><clipPath id="c{n}"><circle cx="54" cy="54" r="36"/></clipPath>{defs}'
            f'<g clip-path="url(#c{n})">{"".join(parts)}</g></svg><figcaption>{n:02d} {name}</figcaption></figure>')

html = ['<html><body style="background:#e5e7eb;font:12px sans-serif;display:grid;grid-template-columns:repeat(5,120px);gap:10px;padding:16px">',
        '<style>figure{margin:0;text-align:center}</style>']
mono_html = []
for i, (name, glyph, bg, colour) in enumerate(LOGOS, start=1):
    elems = glyph_elems(glyph, colour, bg)
    html.append(svg(bg, elems, i, name))
    if RES:
        open(f"{RES}/drawable/ic_logo_{i:02d}_foreground.xml", "w").write(android_vector(elems))
        open(f"{RES}/drawable/ic_logo_{i:02d}_monochrome.xml", "w").write(android_vector(glyph_elems(glyph, colour, bg, mono=True)))
        open(f"{RES}/drawable/ic_logo_{i:02d}_background.xml", "w").write(android_background(bg))
        open(f"{RES}/mipmap-anydpi-v26/ic_logo_{i:02d}.xml", "w").write(adaptive(i))
html.append("</body></html>")
open(os.path.join(OUT, "preview.html"), "w").write("\n".join(html))
print("\n".join(f"{i:02d} {l[0]}" for i, l in enumerate(LOGOS, start=1)))
