#!/usr/bin/env python3
"""Draws the controller button glyphs for the OctoPad addon as 32x32 TGA files.

WoW 1.12 loads uncompressed 32-bit TGAs with power-of-two sizes; rows are written bottom-up.
Usage: python3 tools/make_octopad_glyphs.py app/src/octo/assets/addons/OctoPad/img
"""
import struct
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

SIZE = 32
SCALE = 4  # drawn large and downsampled for smooth edges

FACE = {"a": (96, 186, 70), "b": (214, 72, 64), "x": (60, 132, 220), "y": (232, 196, 52)}
GREY = (70, 70, 78)
LIGHT = (235, 235, 235)


def font(px):
    for name in ("DejaVuSans-Bold.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"):
        try:
            return ImageFont.truetype(name, px)
        except OSError:
            pass
    return ImageFont.load_default()


def canvas():
    img = Image.new("RGBA", (SIZE * SCALE, SIZE * SCALE), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img)


def centered_text(draw, text, px, fill, box):
    f = font(px)
    left, top, right, bottom = draw.textbbox((0, 0), text, font=f)
    x0, y0, x1, y1 = box
    draw.text(((x0 + x1 - (right - left)) / 2 - left, (y0 + y1 - (bottom - top)) / 2 - top), text, font=f, fill=fill)


def face(letter):
    img, d = canvas()
    s = SIZE * SCALE
    d.ellipse((4, 4, s - 4, s - 4), fill=(20, 20, 24, 255))
    d.ellipse((12, 12, s - 12, s - 12), fill=FACE[letter] + (255,))
    centered_text(d, letter.upper(), 72, (255, 255, 255, 255), (0, 0, s, s))
    return img


def pill(text, rounded=40):
    img, d = canvas()
    s = SIZE * SCALE
    d.rounded_rectangle((4, 22, s - 4, s - 22), radius=rounded, fill=(20, 20, 24, 255))
    d.rounded_rectangle((10, 28, s - 10, s - 28), radius=rounded - 6, fill=GREY + (255,))
    centered_text(d, text, 52 if len(text) <= 2 else 40, LIGHT + (255,), (0, 22, s, s - 22))
    return img


def dpad(direction):
    img, d = canvas()
    s = SIZE * SCALE
    arm = s // 3
    d.rectangle((arm, 6, 2 * arm, s - 6), fill=(20, 20, 24, 255))
    d.rectangle((6, arm, s - 6, 2 * arm), fill=(20, 20, 24, 255))
    d.rectangle((arm + 6, 12, 2 * arm - 6, s - 12), fill=GREY + (255,))
    d.rectangle((12, arm + 6, s - 12, 2 * arm - 6), fill=GREY + (255,))
    boxes = {
        "up": (arm + 6, 12, 2 * arm - 6, arm),
        "down": (arm + 6, 2 * arm, 2 * arm - 6, s - 12),
        "left": (12, arm + 6, arm, 2 * arm - 6),
        "right": (2 * arm, arm + 6, s - 12, 2 * arm - 6),
    }
    d.rectangle(boxes[direction], fill=LIGHT + (255,))
    return img


def stick(text):
    img, d = canvas()
    s = SIZE * SCALE
    d.ellipse((4, 4, s - 4, s - 4), fill=(20, 20, 24, 255))
    d.ellipse((14, 14, s - 14, s - 14), fill=GREY + (255,))
    centered_text(d, text, 44, LIGHT + (255,), (0, 0, s, s))
    return img


def menu(lines):
    img, d = canvas()
    s = SIZE * SCALE
    d.ellipse((4, 4, s - 4, s - 4), fill=(20, 20, 24, 255))
    d.ellipse((12, 12, s - 12, s - 12), fill=GREY + (255,))
    for i in range(lines):
        y = s // 2 + (i - (lines - 1) / 2) * 18
        d.rectangle((s // 2 - 22, y - 4, s // 2 + 22, y + 4), fill=LIGHT + (255,))
    return img


def write_tga(img, path):
    img = img.resize((SIZE, SIZE), Image.LANCZOS)
    header = struct.pack("<BBBHHBHHHHBB", 0, 0, 2, 0, 0, 0, 0, 0, SIZE, SIZE, 32, 8)
    rows = []
    for y in range(SIZE - 1, -1, -1):
        for x in range(SIZE):
            r, g, b, a = img.getpixel((x, y))
            rows.append(struct.pack("BBBB", b, g, r, a))
    path.write_bytes(header + b"".join(rows))


def main():
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    glyphs = {letter: face(letter) for letter in FACE}
    glyphs.update({name: dpad(name) for name in ("up", "down", "left", "right")})
    glyphs.update({"lb": pill("LB"), "rb": pill("RB"), "lt": pill("LT"), "rt": pill("RT")})
    glyphs.update({"l3": stick("L3"), "r3": stick("R3"), "start": menu(3), "select": menu(2)})
    for name, img in glyphs.items():
        write_tga(img, out / f"{name}.tga")


if __name__ == "__main__":
    main()
