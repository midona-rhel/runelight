#!/usr/bin/env python3
"""
Compute which way the water flows on every water tile of the world, from the world map image, and write it as the
plugin's water_flow.dat resource (read by WaterFlow.java).

1. Water mask: map pixels in the water colors, voted per tile (worldmap.py). Bridges, docks and map icons over
   water are closed over so a river stays one piece; specks smaller than an icon are dropped.
2. Open water: tiles at least OPEN_RADIUS from any shore. Large connected open areas (the sea, big lakes) are
   sinks: rivers run into them.
3. Everywhere: water runs down the geodesic distance to the nearest sink, smoothed; narrow channels run faster,
   wide ones slower, and the shallows along a sea or lake shore stay still (the plugin pushes them toward the shore
   itself). Water with no sink (ponds) is still, and so are channels between two different sinks (straits, rivers
   between two lakes), which would otherwise run in from both ends.
4. Annotations (annotations.json, made by reading grid crops of the map, see crop.py) override that inside their
   box: a river runs from its sources to its outlets (geodesic distance to the outlets minus distance from the
   sources), still boxes (moats, ponds, swamp pools) don't flow at all.

Output: "RLWF", u16 version 2, u16 region count, then per region u16 id (x >> 6 << 8 | y >> 6), u32 length and that
many bytes of zlib-deflated 64 x 64 pairs of signed bytes (east, north) in hundredths of a tile per second, row by
row from the south-west, x fastest; -128 marks tiles the map knows nothing about. Only regions with water are
listed. Big-endian.

Usage: tools/water/build_flow.py <cache dir> <out water_flow.dat> [debug flow.npz]
"""
import json
import os
import struct
import sys
import zlib

import numpy as np
from scipy import ndimage
from skimage.graph import MCP_Geometric

import worldmap

HERE = os.path.dirname(os.path.abspath(__file__))

MIN_VOTES = 8          # of 16 pixels
CLOSE_RADIUS = 2       # tiles of land a river may be interrupted by (bridges, icons)
MIN_SPECK = 10         # smaller water specks are map icons
OPEN_RADIUS = 5        # tiles from shore for open water
SINK_AREA = 400        # open area (tiles) of a sea or lake rivers run into
SHALLOWS = 6           # geodesic tiles from open water before a channel runs at full speed
RIVER_SPEED = 0.55     # tiles per second in a channel
EXTEND = 3             # tiles past the mapped water that still get its flow (the map and the scene differ a bit)
SMOOTH = 1.6           # flow smoothing, tiles
UNKNOWN = -128


def disk(r):
    y, x = np.ogrid[-r:r + 1, -r:r + 1]
    return x * x + y * y <= r * r + r


def load_annotations():
    with open(os.path.join(HERE, "annotations.json")) as f:
        return json.load(f)


def to_rc(p):
    x, y = p
    return worldmap.YTOP - y, x - worldmap.X0


def snap(mask, rc, radius=6):
    """Nearest water tile to an annotated point, or None when it isn't near water (a wrong annotation)."""
    r, c = rc
    r0, c0 = max(r - radius, 0), max(c - radius, 0)
    win = mask[r0:r + radius + 1, c0:c + radius + 1]
    ys, xs = np.nonzero(win)
    if len(ys) == 0:
        return None
    d = (ys + r0 - r) ** 2 + (xs + c0 - c) ** 2
    i = int(np.argmin(d))
    return int(ys[i] + r0), int(xs[i] + c0)


def geodesic(domain, seeds):
    """Distance through `domain` (bool array) from seed tiles (bool array), inf where unreachable, and for every
    tile the flat index of the seed it is nearest to (-1 where unreachable)."""
    h, w = domain.shape
    cost = np.where(domain, 1.0, np.inf)
    starts = np.argwhere(seeds & domain)
    if len(starts) == 0:
        return np.full(domain.shape, np.inf), np.full(domain.shape, -1)
    mcp = MCP_Geometric(cost, fully_connected=True)
    dist, tb = mcp.find_costs(starts)
    dist[~domain] = np.inf
    off = np.asarray(mcp.offsets)
    # each tile's traceback points one step back toward its seed: pointer jumping finds the seed
    parent = np.arange(h * w).reshape(h, w)
    rr, cc = np.nonzero(np.isfinite(dist) & (tb >= 0))
    t = tb[rr, cc]
    parent[rr, cc] = (rr - off[t, 0]) * w + (cc - off[t, 1])
    parent = parent.ravel()
    for _ in range(48):
        nxt = parent[parent]
        if np.array_equal(nxt, parent):
            break
        parent = nxt
    origin = np.where(np.isfinite(dist).ravel(), parent, -1).reshape(h, w)
    return dist, origin


def build(cache, debug=None):
    votes = worldmap.water_votes(cache)
    raw = votes >= MIN_VOTES
    water = ndimage.binary_closing(raw, structure=disk(CLOSE_RADIUS)) | raw
    labels, n = ndimage.label(water, structure=np.ones((3, 3)))
    sizes = ndimage.sum_labels(water, labels, np.arange(n + 1))
    water &= sizes[labels] >= MIN_SPECK
    print("water tiles", int(water.sum()))

    shore = ndimage.distance_transform_edt(water)
    open_ = shore >= OPEN_RADIUS
    olab, on = ndimage.label(open_, structure=np.ones((3, 3)))
    oarea = ndimage.sum_labels(open_, olab, np.arange(on + 1))
    sink_label = oarea >= SINK_AREA
    sink_label[0] = False
    sinks = sink_label[olab] & open_
    domain = water & ~sinks

    fx = np.zeros(water.shape, np.float32)
    fy = np.zeros(water.shape, np.float32)
    conflicts = []
    labels, n = ndimage.label(water, structure=np.ones((3, 3)))
    for lab, sl in enumerate(ndimage.find_objects(labels), start=1):
        if sl is not None:
            auto_flow(sl, labels, lab, domain, sinks, olab, shore, fx, fy, conflicts)
    conflicts.sort(reverse=True)
    for cells, sw, ne in conflicts:
        print("still: channel between two sinks, %d tiles, %s - %s" % (cells, sw, ne))

    ann = load_annotations()
    for river in ann["rivers"]:
        annotated_flow(river, water, sinks, shore, fx, fy)
    for s in ann["still"]:
        rows, cols = box_slice(s["box"])
        fx[rows, cols] = 0
        fy[rows, cols] = 0

    # tiles near the mapped water take the flow of the nearest water tile
    dist, (ir, ic) = ndimage.distance_transform_edt(~water, return_indices=True)
    known = dist <= EXTEND
    fx = np.where(known, fx[ir, ic], 0)
    fy = np.where(known, fy[ir, ic], 0)
    bx = np.where(known, np.clip(np.round(fx * 100), -127, 127), UNKNOWN).astype(np.int8)
    by = np.where(known, np.clip(np.round(fy * 100), -127, 127), UNKNOWN).astype(np.int8)
    if debug:
        np.savez_compressed(debug, fx=bx, fy=by, water=water, sinks=sinks)
    return bx, by, known


def box_slice(box):
    x0, y0, x1, y1 = box
    return worldmap.tile_slice(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1))


def auto_flow(sl, labels, lab, domain, sinks, olab, shore, fx, fy, conflicts):
    """Flow of one connected piece of water toward the nearest sea or large lake."""
    # one tile of margin so sinks around the component's own tiles are seen
    sl = tuple(slice(max(s.start - 1, 0), s.stop + 1) for s in sl)
    comp = labels[sl] == lab
    dom = domain[sl] & comp
    if not dom.any():
        return
    seed = ndimage.binary_dilation(sinks[sl] & comp, structure=np.ones((3, 3))) & dom
    d_sink, origin = geodesic(dom, seed)
    if not np.isfinite(d_sink).any():
        return  # a pond, or a lake with no river out
    ramp = np.clip((d_sink - SHALLOWS * 0.5) / SHALLOWS, 0, 1)
    # Channels whose ends run into different sinks (a strait between two seas, a river between two lakes) would
    # flow in from both ends and meet in the middle: keep them still unless an annotation says which way they run.
    seed_sink = ndimage.maximum_filter(np.where(sinks[sl], olab[sl], 0), size=3)
    sink_of = np.where(origin >= 0, seed_sink.ravel()[np.maximum(origin, 0)], 0)
    chan, _ = ndimage.label(dom & (d_sink > SHALLOWS), structure=np.ones((3, 3)))
    for c, csl in enumerate(ndimage.find_objects(chan), start=1):
        here = chan[csl] == c
        ends = np.unique(sink_of[csl][here])
        if len(ends[ends != 0]) > 1:
            cells = int(here.sum())
            if cells >= 40:
                r0, c0 = sl[0].start + csl[0].start, sl[1].start + csl[1].start
                r1, c1 = sl[0].start + csl[0].stop - 1, sl[1].start + csl[1].stop - 1
                conflicts.append((cells, worldmap.world(r1, c0), worldmap.world(r0, c1)))
            grow = ndimage.binary_dilation(chan == c, structure=np.ones((3, 3)), iterations=SHALLOWS)
            ramp = np.where(grow, 0, ramp)
    apply(sl, dom, d_sink, ramp, shore, fx, fy)


def annotated_flow(river, water, sinks, shore, fx, fy):
    """Inside the river's box, water runs from its sources to its outlets, whatever the sinks say."""
    sl = box_slice(river["box"])
    dom = water[sl] & ~sinks[sl]
    outs = np.zeros_like(dom)
    srcs = np.zeros_like(dom)
    r0, c0 = sl[0].start, sl[1].start
    for kind, target in (("outlets", outs), ("sources", srcs)):
        for p in river.get(kind, []):
            r, c = to_rc(p)
            rc = snap(dom, (r - r0, c - c0))
            if rc is None:
                print("annotation not on water, ignored:", river["name"], kind, p)
                continue
            target[rc] = True
    d_out = geodesic(dom, outs)[0]
    d_src = geodesic(dom, srcs)[0]
    fo, fs = np.isfinite(d_out), np.isfinite(d_src)
    if not (fo.any() or fs.any()):
        print("annotation without sources or outlets on water:", river["name"])
        return
    with np.errstate(invalid="ignore"):
        phi = np.where(fo & fs, d_out - d_src, np.where(fo, d_out, np.where(fs, -d_src, np.inf)))
    # the shallows along the sea and big lakes stay calm, as everywhere
    d_sink = geodesic(dom, ndimage.binary_dilation(sinks[sl], structure=np.ones((3, 3))) & dom)[0]
    ramp = np.where(np.isfinite(d_sink), np.clip((d_sink - SHALLOWS * 0.5) / SHALLOWS, 0, 1), 1.0)
    ramp = ramp * river.get("speed", 1.0)
    reach = dom & np.isfinite(phi)
    fx[sl] = np.where(reach, 0, fx[sl])
    fy[sl] = np.where(reach, 0, fy[sl])
    apply(sl, reach, phi, ramp, shore, fx, fy)


def apply(sl, dom, phi, ramp, shore, fx, fy):
    reach = dom & np.isfinite(phi)
    gx, gy = gradient(np.where(reach, phi, np.nan))
    # map rows run south: north is -row
    vx, vy = -gx, gy
    length = np.hypot(vx, vy)
    ok = reach & (length > 1e-6)
    vx = np.where(ok, vx / np.maximum(length, 1e-6), 0)
    vy = np.where(ok, vy / np.maximum(length, 1e-6), 0)
    vx, vy = smooth(vx, vy, ok)
    # narrow channels run faster than wide ones
    speed = RIVER_SPEED * np.clip(2.5 / np.maximum(shore[sl], 1), 0.12, 1.4) * ramp
    fx[sl] = np.where(ok, vx * speed, fx[sl])
    fy[sl] = np.where(ok, vy * speed, fy[sl])


def gradient(phi):
    """Central differences that fall back to one-sided ones at the edge of the water (nan outside)."""
    def diff(axis):
        f = np.moveaxis(phi, axis, 0)
        p = np.full_like(f, np.nan)
        m = np.full_like(f, np.nan)
        p[:-1] = f[1:] - f[:-1]
        m[1:] = f[1:] - f[:-1]
        both = np.isfinite(p) & np.isfinite(m)
        g = np.where(both, (p + m) * 0.5, np.where(np.isfinite(p), p, np.where(np.isfinite(m), m, 0)))
        return np.moveaxis(g, 0, axis)
    return diff(1), diff(0)


def smooth(vx, vy, ok):
    w = ndimage.gaussian_filter(ok.astype(np.float32), SMOOTH)
    sx = ndimage.gaussian_filter(vx.astype(np.float32), SMOOTH)
    sy = ndimage.gaussian_filter(vy.astype(np.float32), SMOOTH)
    sx = np.where(ok, sx / np.maximum(w, 1e-6), 0)
    sy = np.where(ok, sy / np.maximum(w, 1e-6), 0)
    n = np.hypot(sx, sy)
    good = n > 1e-3
    return np.where(good, sx / np.maximum(n, 1e-6), 0), np.where(good, sy / np.maximum(n, 1e-6), 0)


def write(out, bx, by, known):
    rows, cols = bx.shape
    regions = []
    x_lo = worldmap.X0 >> 6
    x_hi = (worldmap.X0 + cols - 1) >> 6
    y_lo = (worldmap.YTOP - rows + 1) >> 6
    y_hi = worldmap.YTOP >> 6
    for rx in range(x_lo, x_hi + 1):
        for ry in range(y_lo, y_hi + 1):
            rs, cs = worldmap.tile_slice(rx << 6, ry << 6, (rx << 6) + 63, (ry << 6) + 63)
            if not known[rs, cs].any():
                continue
            # rows from the south: flip the map's north-first rows
            ex = bx[rs, cs][::-1]
            ny = by[rs, cs][::-1]
            payload = np.stack([ex, ny], axis=-1).astype(np.int8).tobytes()
            regions.append((rx << 8 | ry, payload))
    # each region deflated on its own: the plugin keeps them packed and unpacks only the ones a scene touches
    data = b"RLWF" + struct.pack(">HH", 2, len(regions))
    for rid, payload in regions:
        packed = zlib.compress(payload, 9)
        data += struct.pack(">HI", rid, len(packed)) + packed
    with open(out, "wb") as f:
        f.write(data)
    print("regions", len(regions), "bytes", os.path.getsize(out))


if __name__ == "__main__":
    cache, out = sys.argv[1], sys.argv[2]
    debug = sys.argv[3] if len(sys.argv) > 3 else None
    bx, by, known = build(cache, debug)
    write(out, bx, by, known)
