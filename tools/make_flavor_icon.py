#!/usr/bin/env python3
"""Draws a game flavor's launcher icon set into app/src/<flavor>/res.

Usage: make_flavor_icon.py [retail|octo] [font.ttf]

Original artwork (no Blizzard assets): a gold-rimmed medallion with "WoW" and a
ribbon naming the flavor, in the same dark/gold palette as the Forever icon.
Needs Pillow. Pass a bold serif .ttf to use a different font.
"""
import math
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

REPO = Path(__file__).resolve().parent.parent
FLAVOR = sys.argv[1] if len(sys.argv) > 1 else "retail"
FONT = sys.argv[2] if len(sys.argv) > 2 else "/usr/share/fonts/truetype/dejavu/DejaVuSerif-Bold.ttf"
RES = REPO / f"app/src/{FLAVOR}/res"

# Ribbon text and the medallion face colour, from centre (t=0) to rim (t=1).
FLAVORS = {
    "retail": ("RETAIL", lambda t: (40 - 32 * t, 150 - 115 * t, 215 - 140 * t)),  # sapphire
    "octo": ("OCTO", lambda t: (34 - 26 * t, 168 - 120 * t, 138 - 96 * t)),  # teal
}
RIBBON, FACE = FLAVORS[FLAVOR]
BACKGROUND = (0x0A, 0x0E, 0x17)
S = 1024  # working canvas = the full 108dp adaptive-icon foreground

DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}


def vertical_gradient(size, stops):
    w, h = size
    img = Image.new("RGBA", size)
    px = img.load()
    for y in range(h):
        t = y / max(h - 1, 1)
        for i in range(len(stops) - 1):
            (t0, c0), (t1, c1) = stops[i], stops[i + 1]
            if t0 <= t <= t1:
                f = (t - t0) / (t1 - t0)
                c = tuple(round(a + (b - a) * f) for a, b in zip(c0, c1))
                break
        for x in range(w):
            px[x, y] = c + (255,)
    return img


GOLD = [(0.0, (255, 236, 170)), (0.45, (214, 166, 82)), (0.55, (176, 124, 52)), (1.0, (240, 200, 120))]
BRONZE = [(0.0, (196, 146, 78)), (0.5, (120, 78, 36)), (1.0, (170, 118, 58))]


def paint(canvas, mask, stops, box=None):
    """Fill `mask` on `canvas` with a vertical gradient spanning `box` (defaults to the mask's bounds)."""
    x0, y0, x1, y1 = box or mask.getbbox()
    grad = vertical_gradient((x1 - x0, y1 - y0), stops)
    layer = Image.new("RGBA", canvas.size)
    layer.paste(grad, (x0, y0))
    canvas.paste(layer, (0, 0), mask)


def shadow(canvas, mask, offset=10, blur=14, alpha=170):
    sh = Image.new("L", canvas.size)
    sh.paste(mask, (0, offset))
    sh = sh.filter(ImageFilter.GaussianBlur(blur)).point(lambda v: v * alpha // 255)
    canvas.paste(Image.new("RGBA", canvas.size, (0, 0, 0, 255)), (0, 0), sh)


def text_mask(text, font, center, tracking=0):
    m = Image.new("L", (S, S))
    d = ImageDraw.Draw(m)
    widths = [d.textlength(ch, font=font) for ch in text]
    total = sum(widths) + tracking * (len(text) - 1)
    x = center[0] - total / 2
    for ch, w in zip(text, widths):
        d.text((x, center[1]), ch, font=font, fill=255, anchor="lm")
        x += w + tracking
    return m


def emblem():
    img = Image.new("RGBA", (S, S))
    c = S // 2

    # Four compass points behind the medallion.
    spikes = Image.new("L", (S, S))
    d = ImageDraw.Draw(spikes)
    for angle in range(0, 360, 90):
        a = math.radians(angle)
        tip = (c + 330 * math.sin(a), c - 330 * math.cos(a))
        left = (c + 150 * math.sin(a - 0.42), c - 150 * math.cos(a - 0.42))
        right = (c + 150 * math.sin(a + 0.42), c - 150 * math.cos(a + 0.42))
        d.polygon([tip, left, (c, c), right], fill=255)
    shadow(img, spikes)
    paint(img, spikes, BRONZE)

    # Gold rim, then the sapphire face.
    rim = Image.new("L", (S, S))
    ImageDraw.Draw(rim).ellipse([c - 262, c - 262, c + 262, c + 262], fill=255)
    shadow(img, rim)
    paint(img, rim, GOLD)
    inner_rim = Image.new("L", (S, S))
    ImageDraw.Draw(inner_rim).ellipse([c - 236, c - 236, c + 236, c + 236], fill=255)
    paint(img, inner_rim, BRONZE)

    face = Image.new("RGBA", (S, S))
    fp = face.load()
    for y in range(c - 226, c + 227):
        for x in range(c - 226, c + 227):
            r = math.hypot(x - c, y - c + 60) / 290
            if math.hypot(x - c, y - c) <= 226:
                t = min(r, 1.0)
                fp[x, y] = tuple(round(v) for v in FACE(t)) + (255,)
    img.alpha_composite(face)

    # A thin gold inner ring framing the face.
    ring = Image.new("L", (S, S))
    rd = ImageDraw.Draw(ring)
    rd.ellipse([c - 212, c - 212, c + 212, c + 212], outline=255, width=6)
    paint(img, ring, GOLD, (c - 212, c - 212, c + 212, c + 212))

    # "WoW" in gold with a dark outline.
    big = ImageFont.truetype(FONT, 190)
    wow = text_mask("WoW", big, (c, c - 52), tracking=-6)
    outline = wow.filter(ImageFilter.MaxFilter(13))
    shadow(img, outline, offset=8, blur=8)
    img.paste(Image.new("RGBA", (S, S), (40, 24, 10, 255)), (0, 0), outline)
    paint(img, wow, GOLD)

    # Flavor ribbon across the lower half, with notched tails.
    top, bottom = c + 70, c + 180
    half = 268  # keeps the ribbon corners inside a circular launcher mask
    notch = 30
    ribbon = Image.new("L", (S, S))
    ImageDraw.Draw(ribbon).polygon(
        [(c - half, top), (c + half, top), (c + half - notch, (top + bottom) / 2), (c + half, bottom),
         (c - half, bottom), (c - half + notch, (top + bottom) / 2)],
        fill=255,
    )
    shadow(img, ribbon)
    paint(img, ribbon, GOLD)
    band = Image.new("L", (S, S))
    ImageDraw.Draw(band).rectangle([c - half + 40, top + 12, c + half - 40, bottom - 12], fill=255)
    paint(img, band, BRONZE)

    small = ImageFont.truetype(FONT, 72)
    label = text_mask(RIBBON, small, (c, (top + bottom) / 2 + 2), tracking=8)
    img.paste(Image.new("RGBA", (S, S), (40, 24, 10, 255)), (0, 0), label.filter(ImageFilter.MaxFilter(7)))
    paint(img, label, GOLD)
    return img


def save(img, path, size):
    path.parent.mkdir(parents=True, exist_ok=True)
    out = img.resize((size, size), Image.LANCZOS)
    if path.suffix == ".webp":
        out.save(path, "WEBP", lossless=True)
    else:
        out.save(path, optimize=True)


def main():
    fg = emblem()

    full = Image.new("RGBA", (S, S), BACKGROUND + (255,))
    full.alpha_composite(fg)
    # Legacy icons show the middle 72dp of the 108dp adaptive canvas.
    crop = round(S * 18 / 108)
    legacy = full.crop((crop, crop, S - crop, S - crop))
    rounded = legacy.copy()
    circle = Image.new("L", legacy.size)
    ImageDraw.Draw(circle).ellipse([0, 0, legacy.width - 1, legacy.height - 1], fill=255)
    rounded.putalpha(circle)

    mono = Image.new("RGBA", (S, S))
    mono.paste(Image.new("RGBA", (S, S), (255, 255, 255, 255)), (0, 0), fg.getchannel("A"))
    mono = mono.crop((crop, crop, S - crop, S - crop))

    for name, scale in DENSITIES.items():
        folder = RES / f"mipmap-{name}"
        save(fg, folder / "ic_launcher_foreground.png", round(108 * scale))
        save(legacy, folder / "ic_launcher.webp", round(48 * scale))
        save(rounded, folder / "ic_launcher_round.webp", round(48 * scale))
        save(mono, folder / "ic_launcher_monochrome.png", round(48 * scale))

    # TV / handheld-frontend banner, 320x180 like the Forever one.
    banner = vertical_gradient((320, 180), [(0.0, (16, 25, 42)), (1.0, (8, 12, 20))])
    mark = fg.crop((crop, crop, S - crop, S - crop)).resize((170, 170), Image.LANCZOS)
    banner.alpha_composite(mark, (75, 5))
    (RES / "drawable").mkdir(parents=True, exist_ok=True)
    banner.save(RES / "drawable/ic_banner.png", optimize=True)

    full.resize((512, 512), Image.LANCZOS).save(REPO / f"app/src/{FLAVOR}/ic_launcher-playstore.png", optimize=True)


if __name__ == "__main__":
    main()
