#!/usr/bin/env python3
"""
Render a piece of the world map with a labelled world tile grid, optionally with the computed flow on top, for
annotating rivers by eye (or by a model reading the image) and for checking the result.

Usage: tools/water/crop.py <cache dir> x0 y0 x1 y1 out.png [pixels per tile] [grid step] [flow.npz]
"""
import sys

import numpy as np
from PIL import Image, ImageDraw

import worldmap


def render(cache, x0, y0, x1, y1, scale=4, grid=16, flow=None):
    full = worldmap.full_image(cache)
    px = worldmap.PX
    rows, cols = worldmap.tile_slice(x0, y0, x1, y1)
    a = np.ascontiguousarray(full[rows.start * px:rows.stop * px, cols.start * px:cols.stop * px])
    w, h = (x1 - x0 + 1) * scale, (y1 - y0 + 1) * scale
    im = Image.fromarray(a).resize((w, h), Image.NEAREST if scale >= px else Image.BOX)
    d = ImageDraw.Draw(im)
    if flow is not None:
        sub = int(flow["sub"]) if "sub" in flow else 1
        srows = slice(rows.start * sub, rows.stop * sub)
        scols = slice(cols.start * sub, cols.stop * sub)
        fx = flow["fx"][srows, scols].astype(np.float32)
        fy = flow["fy"][srows, scols].astype(np.float32)
        # arrows only on the map's water (the file also carries flow a few tiles onto the banks)
        k = px // sub
        wpx = worldmap.is_water(a)
        water = wpx.reshape(wpx.shape[0] // k, k, wpx.shape[1] // k, k).sum(axis=(1, 3)) >= 2
        # an arrow every `step` tiles, at the sample nearest the middle of the tile
        step = max(1, 16 // scale)
        for r in range(step // 2, rows.stop - rows.start, step):
            for c in range(step // 2, cols.stop - cols.start, step):
                sr, sc = r * sub + sub // 2, c * sub + sub // 2
                vx, vy = fx[sr, sc], fy[sr, sc]
                if vx == -128 or not water[sr, sc]:
                    continue
                cx, cy = (sc + 0.5) * scale / sub, (sr + 0.5) * scale / sub
                if vx == 0 and vy == 0:
                    d.rectangle([cx - 1, cy - 1, cx + 1, cy + 1], fill=(255, 255, 255))
                    continue
                n = (vx * vx + vy * vy) ** 0.5
                if n < 5:
                    # barely moving (under a tile per 20 s): a pale blue dot
                    d.rectangle([cx - 1, cy - 1, cx + 1, cy + 1], fill=(150, 200, 255))
                    continue
                # slow water short and pale, fast water long and red
                t = min(1.0, n / 100)
                L = step * scale * 0.9 * (0.35 + 0.65 * t)
                ex, ey = cx + vx / n * L, cy - vy / n * L
                d.line([(cx, cy), (ex, ey)], fill=(255, int(220 * (1 - t)) + 30, int(220 * (1 - t)) + 30), width=2)
                d.ellipse([ex - 1.5, ey - 1.5, ex + 1.5, ey + 1.5], fill=(255, 255, 0))
    for x in range((x0 + grid - 1) // grid * grid, x1 + 1, grid):
        p = (x - x0) * scale
        d.line([(p, 0), (p, h)], fill=(255, 0, 255))
        d.text((p + 2, 2), str(x), fill=(255, 255, 0))
    for y in range((y0 + grid - 1) // grid * grid, y1 + 1, grid):
        p = (y1 - y + 1) * scale  # south edge of tile row y
        d.line([(0, p), (w, p)], fill=(255, 0, 255))
        d.text((2, p - 12), str(y), fill=(255, 255, 0))
    return im


if __name__ == "__main__":
    args = sys.argv[1:]
    cache = args[0]
    x0, y0, x1, y1 = map(int, args[1:5])
    out = args[5]
    scale = int(args[6]) if len(args) > 6 else 4
    grid = int(args[7]) if len(args) > 7 else 16
    flow = np.load(args[8]) if len(args) > 8 else None
    render(cache, x0, y0, x1, y1, scale, grid, flow).save(out)
