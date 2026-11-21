#!/usr/bin/env python3
"""Draws the PWA icons (original pixel art: Pebbit from the Pebble edition) with Pillow.

Run from the repository root:  python3 web/tools/make_icons.py
"""
import json
import re
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
DATA = (ROOT / "web/js/data.js").read_text()
OUT = ROOT / "web/icons"

editions = json.loads(re.search(r"export const EDITIONS = (\[.*?\n\]);", DATA, re.S).group(1))
pebble = editions[0]
SPRITE = pebble["forms"]["child"]["sprite"]
LCD = pebble["lcdColor"]
INK = pebble["inkColor"]
SHELL = pebble["shellColor"]
ACCENT = pebble["shellAccent"]


def draw(size, maskable=False, rounded=True):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    if maskable or not rounded:
        d.rectangle([0, 0, size, size], fill=SHELL)
    else:
        d.rounded_rectangle([0, 0, size - 1, size - 1], radius=size * 0.22, fill=SHELL)
    # LCD window; maskable icons keep everything inside the central 80% safe zone.
    inset = size * (0.2 if maskable else 0.12)
    d.rounded_rectangle([inset, inset, size - inset, size - inset], radius=size * 0.08, fill=ACCENT)
    pad = size * 0.035
    d.rounded_rectangle([inset + pad, inset + pad, size - inset - pad, size - inset - pad], radius=size * 0.05, fill=LCD)
    w = max(len(r) for r in SPRITE)
    h = len(SPRITE)
    area = size - 2 * (inset + pad) - size * 0.12
    cell = area / max(w, h)
    ox = (size - cell * w) / 2
    oy = (size - cell * h) / 2
    for y, row in enumerate(SPRITE):
        for x, c in enumerate(row):
            if c in "#Ee":
                x0, y0 = ox + x * cell, oy + y * cell
                d.rectangle([x0, y0, x0 + cell * 0.88, y0 + cell * 0.88], fill=INK)
    return img


OUT.mkdir(exist_ok=True)
draw(192).save(OUT / "icon-192.png")
draw(512).save(OUT / "icon-512.png")
draw(512, maskable=True).save(OUT / "icon-maskable-512.png")
draw(180, rounded=False).convert("RGB").save(OUT / "apple-touch-icon.png")
print("icons written to", OUT.relative_to(ROOT))
