#!/usr/bin/env python3
"""
Compute which way the water flows on every water tile of the world, from the world map image, and write it as the
plugin's water_flow.dat resource (read by WaterFlow.java).

1. Water mask per tile: map pixels in the water colors, voted per tile (worldmap.py). Bridges, docks and map icons
   over water are closed over so a river stays one piece; specks smaller than an icon are dropped.
2. Open water: tiles at least OPEN_RADIUS from any shore. Large connected open areas (the sea, big lakes) are
   sinks: rivers run into them.
3. Which water is a river (tile resolution): a channel reaching at least MIN_RIVER tiles from open water, open to the
   sea or a big lake at exactly one place (a channel open at both ends is a strait, not a river), its source inland
   and the water narrow. Its sources are its far end and any long dead-end arm (tributaries); short arms and round
   bulges are pools, not sources. Everything else (sea, lakes, coves, straits, ponds) is still.
4. Annotations (annotations.json, made by reading grid crops of the map, see crop.py) add rivers the rules miss or get
   backwards, with their sources and outlets, and still boxes (moats, ponds, swamp pools, coasts) that don't flow.
5. Each river is then solved at the map's full resolution (channel_flow.py): potential flow from its sources to its
   outlets through the pixel water mask, so the current runs along the channel, faster in narrows and the middle,
   slow across lakes and nearly still in side pools, and fades out where the river meets open water.

Output: "RLWF", u16 version 3, u16 region count, then per region u16 id (x >> 6 << 8 | y >> 6), u32 length and that
many bytes of zlib-deflated 128 x 128 pairs of signed bytes (east, north) in hundredths of a tile per second, two by
two samples per tile, row by row from the south-west, x fastest; -128 marks samples the map knows nothing about.
Only regions with water are listed. Big-endian. The speeds already include the channel's shape (middle, narrows,
lakes); the plugin only adds what the scene knows better (drops).

Usage: tools/water/build_flow.py <cache dir> <out water_flow.dat> [debug flow.npz]
With FLOW_ONLY=x0,y0,x1,y1 in the environment only the rivers touching that box are solved (for tuning one area).
"""
import json
import os
import struct
import sys
import time
import zlib

import numpy as np
from scipy import ndimage
from skimage.graph import MCP_Geometric
from skimage.morphology import skeletonize

import channel_flow
import worldmap

HERE = os.path.dirname(os.path.abspath(__file__))

OPEN_RADIUS = 5        # tiles from shore for open water
SINK_AREA = 400        # open area (tiles) of a sea or lake rivers run into
SHALLOWS = 6           # geodesic tiles from open water where a channel's mouth begins
RIVER_SPEED = 0.55     # tiles per second in a river's typical channel, middle and banks averaged
EXTEND = 3             # tiles past the mapped water that still get its flow (the map and the scene differ a bit)
MIN_RIVER = 20         # tiles: a channel reaching less far than this from open water is an inlet or cove: still
MIN_RIVER_TILES = 20   # moving water in a piece smaller than this many tiles is a speck, not a river
MIN_INLAND = 14        # tiles in a straight line from the sea a river's source must lie
RIVER_WIDTH = 3.0      # median tiles to the bank: wider water is a lagoon or bay, not a river
ARM_MIN = 8            # tiles: a dead-end arm of a river at least this long is a tributary with its own source
ARM_RATIO = 3.0        # ... and this many times longer than it is wide (shorter ones are pools hanging off it)
OUTLET_RADIUS = 8      # tiles around an annotated outlet within which the river drains into open water
SEA_SHALLOWS = 5       # geodesic tiles from open water that are the sea's shallows, not a river's, away from its outlets
CALM_FROM, CALM_TO = 1.0, 5.0  # geodesic tiles from open water over which a river's current dies away
SUB = 2                # samples per tile each way in the output
UNKNOWN = -128


def load_annotations():
    with open(os.path.join(HERE, "annotations.json")) as f:
        return json.load(f)


def to_rc(p):
    x, y = p
    return worldmap.YTOP - y, x - worldmap.X0


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


def box_slice(box):
    x0, y0, x1, y1 = box
    return worldmap.tile_slice(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1))


class World:
    def __init__(self, cache):
        self.cache = cache
        water = worldmap.water_tiles(cache)
        print("water tiles", int(water.sum()))
        self.water = water
        self.shore = ndimage.distance_transform_edt(water)
        open_ = self.shore >= OPEN_RADIUS
        olab, on = ndimage.label(open_, structure=np.ones((3, 3)))
        oarea = ndimage.sum_labels(open_, olab, np.arange(on + 1))
        sink_label = oarea >= SINK_AREA
        sink_label[0] = False
        self.sinks = sink_label[olab] & open_
        self.domain = water & ~self.sinks
        # straight-line distance to the sea or a big lake, to tell real inland sources from coves
        self.sea_dist = ndimage.distance_transform_edt(~self.sinks)
        h, w = water.shape
        self.fx = np.zeros((h * SUB, w * SUB), np.float32)
        self.fy = np.zeros((h * SUB, w * SUB), np.float32)
        self.windows = []

    def window(self, r0, c0, r1, c1, margin):
        h, w = self.water.shape
        return slice(max(r0 - margin, 0), min(r1 + margin, h)), slice(max(c0 - margin, 0), min(c1 + margin, w))


# ---------------------------------------------------------------------------------------------------------------
# Rivers found by the rules

def auto_rivers(world):
    jobs = []
    conflicts = []
    labels, n = ndimage.label(world.water, structure=np.ones((3, 3)))
    for lab, sl in enumerate(ndimage.find_objects(labels), start=1):
        if sl is not None:
            jobs += component_rivers(world, sl, labels, lab, conflicts)
    conflicts.sort(reverse=True)
    for cells, sw, ne in conflicts:
        print("still: channel between two sinks, %d tiles, %s - %s" % (cells, sw, ne))
    return jobs


def component_rivers(world, sl, labels, lab, conflicts):
    """The rivers in one connected piece of water: channels running into the sea or a large lake."""
    # one tile of margin so sinks around the component's own tiles are seen
    sl = tuple(slice(max(s.start - 1, 0), s.stop + 1) for s in sl)
    comp = labels[sl] == lab
    dom = world.domain[sl] & comp
    if not dom.any():
        return []
    seed = ndimage.binary_dilation(world.sinks[sl] & comp, structure=np.ones((3, 3))) & dom
    d_sink, origin = geodesic(dom, seed)
    if not np.isfinite(d_sink).any():
        return []  # a pond, or a lake with no river out
    # Which stretch of shore each tile drains to: channels that reach open water at two separate places (a strait,
    # a channel around an island, a river with both ends on the same sea) aren't rivers.
    seed_lab, _ = ndimage.label(seed, structure=np.ones((3, 3)))
    sink_of = np.where(origin >= 0, seed_lab.ravel()[np.maximum(origin, 0)], 0)
    chan, _ = ndimage.label(dom & (d_sink > SHALLOWS), structure=np.ones((3, 3)))
    shore = world.shore[sl]
    sea_dist = world.sea_dist[sl]
    jobs = []
    for c, csl in enumerate(ndimage.find_objects(chan), start=1):
        here = chan[csl] == c
        ends = np.unique(sink_of[csl][here])
        ends = ends[ends != 0]
        if len(ends) > 1:
            cells = int(here.sum())
            if cells >= 40:
                r0, c0 = sl[0].start + csl[0].start, sl[1].start + csl[1].start
                r1, c1 = sl[0].start + csl[0].stop - 1, sl[1].start + csl[1].stop - 1
                conflicts.append((cells, worldmap.world(r1, c0), worldmap.world(r0, c1)))
            continue
        dmax = np.nanmax(np.where(here, d_sink[csl], 0))
        if len(ends) == 0 or dmax < MIN_RIVER:
            continue
        # the source must lie inland, not just at the far end of a winding lagoon or cove a few tiles from the
        # sea, and a river is narrow water, not a lagoon or a bay
        src = np.unravel_index(np.argmax(np.where(here, d_sink[csl], -1)), here.shape)
        if sea_dist[csl][src] < MIN_INLAND or np.median(shore[csl][here]) > RIVER_WIDTH:
            continue
        gr0, gc0 = sl[0].start + csl[0].start, sl[1].start + csl[1].start
        sources = [(gr0 + src[0], gc0 + src[1])]
        sources += arm_sources(here, d_sink[csl], shore[csl], sea_dist[csl], src, gr0, gc0)
        # the mouth: the channel's tile nearest open water; the river drains into the open water around it
        dch = np.where(here, d_sink[csl], np.inf)
        mouth = np.unravel_index(np.argmin(dch), here.shape)
        mouth_d = float(dch[mouth])
        mouth = (gr0 + mouth[0], gc0 + mouth[1])
        jobs.append(dict(name="river at %d, %d" % worldmap.world(*sources[0]), channel=(gr0, gc0, here),
                         sources=sources, outlets=[mouth], outlet_radius=mouth_d + 3, speed=1.0, clear=False))
    return jobs


def arm_sources(chan, d_sink, shore, sea_dist, main, gr0, gc0):
    """Tributaries: the far ends of long dead-end arms of a river's channel (tiles, global)."""
    sk = skeletonize(chan)
    nb = ndimage.convolve(sk.astype(np.int32), np.ones((3, 3), np.int32), mode="constant") - 1
    out = []
    for r, c in np.argwhere(sk & (nb == 1)):
        if d_sink[r, c] < SHALLOWS + 4 or sea_dist[r, c] < MIN_INLAND * 0.5:
            continue
        if (r - main[0]) ** 2 + (c - main[1]) ** 2 <= 9:
            continue
        # walk the arm to the junction it hangs off
        prev, cur = None, (r, c)
        length, width = 0.0, shore[r, c]
        while True:
            nbrs = [(cur[0] + dr, cur[1] + dc) for dr in (-1, 0, 1) for dc in (-1, 0, 1)
                    if (dr or dc) and 0 <= cur[0] + dr < sk.shape[0] and 0 <= cur[1] + dc < sk.shape[1]
                    and sk[cur[0] + dr, cur[1] + dc]]
            if len(nbrs) >= 3 or length > 200:
                break
            nxt = [p for p in nbrs if p != prev]
            if not nxt or (prev is not None and len(nbrs) != 2):
                break
            step = nxt[0]
            length += np.hypot(step[0] - cur[0], step[1] - cur[1])
            prev, cur = cur, step
            width = max(width, shore[cur])
        if length >= ARM_MIN and length >= ARM_RATIO * width:
            out.append((gr0 + r, gc0 + c))
    return out


# ---------------------------------------------------------------------------------------------------------------
# Rivers from the annotations

def annotated_rivers(world, ann):
    jobs = []
    only = os.environ.get("FLOW_ONLY")
    for river in ann["rivers"]:
        if only and not touches(dict(box=box_slice(river["box"])), [int(v) for v in only.split(",")]):
            continue
        rows, cols = box_slice(river["box"])
        jobs.append(dict(name=river["name"], box=(rows, cols),
                         sources=[to_rc(p) for p in river.get("sources", [])],
                         outlets=[to_rc(p) for p in river.get("outlets", [])],
                         outlet_radius=OUTLET_RADIUS, speed=river.get("speed", 1.0), clear=True))
    return jobs


# ---------------------------------------------------------------------------------------------------------------
# Solving a river at pixel resolution

def run(world, job):
    t0 = time.time()
    rad = int(np.ceil(job["outlet_radius"]))
    if "box" in job:
        br, bc = job["box"]
        rows, cols = world.window(br.start, bc.start, br.stop, bc.stop, rad + 2)
        allowed = np.zeros((rows.stop - rows.start, cols.stop - cols.start), bool)
        allowed[br.start - rows.start:br.stop - rows.start, bc.start - cols.start:bc.stop - cols.start] = \
            world.domain[br, bc]
    else:
        gr0, gc0, here = job["channel"]
        r1, c1 = gr0 + here.shape[0], gc0 + here.shape[1]
        mr, mc = job["outlets"][0]
        rows, cols = world.window(min(gr0, mr - rad), min(gc0, mc - rad), max(r1, mr + rad + 1), max(c1, mc + rad + 1), 3)
        allowed = np.zeros((rows.stop - rows.start, cols.stop - cols.start), bool)
        allowed[gr0 - rows.start:r1 - rows.start, gc0 - cols.start:c1 - cols.start] = here
        allowed = ndimage.binary_dilation(allowed, structure=np.ones((3, 3)))
    water_t = world.water[rows, cols]
    sinks_t = world.sinks[rows, cols]
    dom_t = world.domain[rows, cols]
    px = channel_flow.PX
    water = channel_flow.pixel_water(world.cache, rows, cols, water_t)
    sinks_px = channel_flow.upsample(sinks_t)

    # calm toward open water: geodesic distance (tiles) from the sinks through the river's water
    d_sink = geodesic(dom_t, ndimage.binary_dilation(sinks_t, structure=np.ones((3, 3))) & dom_t)[0]
    d_sink = np.where(sinks_t, 0, np.where(np.isfinite(d_sink), d_sink, 1e3))
    t = np.clip((d_sink - CALM_FROM) / (CALM_TO - CALM_FROM), 0, 1)
    ramp = ndimage.gaussian_filter(channel_flow.upsample((t * t * (3 - 2 * t)).astype(np.float32)), 2.0)

    warned = set()

    def warn(msg):
        if msg not in warned:
            warned.add(msg)
            print(msg)

    def to_px(rc):
        return (rc[0] - rows.start) * px + px // 2, (rc[1] - cols.start) * px + px // 2

    # The shallows along a coast are sea, not river: a river that reaches the coast at more than its outlet (an arm
    # ending on the shore) would otherwise also drain along the coast to its outlet. Only around its outlets does a
    # river run through the shallows into open water.
    rr, cc = np.ogrid[rows.start:rows.stop, cols.start:cols.stop]
    near_out = np.zeros(allowed.shape, bool)
    for p in job["outlets"]:
        near_out |= (rr - p[0]) ** 2 + (cc - p[1]) ** 2 <= rad * rad
    full = allowed | dom_t & near_out
    cut = full & ((d_sink > SEA_SHALLOWS) | near_out)

    def attempt(allowed_t):
        # bank tiles the tile mask calls land still hold some of the channel's pixels
        allowed_px = channel_flow.upsample(ndimage.binary_dilation(allowed_t, structure=np.ones((3, 3))) & ~sinks_t
                                           | allowed_t) & ~sinks_px
        cand = water & allowed_px

        def snap(rc):
            r, c = to_px(rc)
            r0, c0 = max(r - 24, 0), max(c - 24, 0)
            ys, xs = np.nonzero(cand[r0:r + 25, c0:c + 25])
            if len(ys) == 0:
                return None
            i = int(np.argmin((ys + r0 - r) ** 2 + (xs + c0 - c) ** 2))
            return int(ys[i] + r0), int(xs[i] + c0)

        pr, pc = np.ogrid[:water.shape[0], :water.shape[1]]
        outlet = np.zeros(water.shape, bool)
        for p in job["outlets"]:
            s = snap(p)
            if s is None:
                warn("annotation not on water, ignored: %s outlet %s" % (job["name"], worldmap.world(*p)))
                continue
            near_sink = sinks_px & ((pr - s[0]) ** 2 + (pc - s[1]) ** 2 <= (rad * px) ** 2)
            if near_sink.any():
                # drains into the open water around the outlet
                outlet |= near_sink
                allowed_px |= near_sink
            else:
                outlet |= cand & ((pr - s[0]) ** 2 + (pc - s[1]) ** 2 <= px * px * 2)
        sources = []
        for p in job["sources"]:
            s = snap(p)
            if s is None:
                if "box" in job:
                    warn("annotation not on water, ignored: %s source %s" % (job["name"], worldmap.world(*p)))
                continue
            sources.append(s)
        if not sources and "box" not in job and cand.any():
            # the tile mask's far end is a gap the pixels don't close (land between a river and a pond): the
            # channel's farthest water pixel instead
            far = np.where(cand, channel_flow.upsample(d_sink), -1)
            sources.append(np.unravel_index(int(np.argmax(far)), far.shape))
        if not outlet.any():
            return None, sources
        lab, _ = ndimage.label(water & allowed_px | outlet)
        joined = set(np.unique(lab[outlet]).tolist())
        if allowed_t is cut and (not sources or not all(lab[s] in joined for s in sources)):
            return None, sources
        return channel_flow.solve(water, allowed_px, outlet, sources, ramp, RIVER_SPEED * job["speed"]), sources

    res, sources = attempt(cut)
    if res is None:
        # the river itself runs through the shallows (an estuary): keep them
        res, sources = attempt(full)
    if res is None:
        print("river without sources or outlets on water:", job["name"])
        return None
    vx, vy, reach = res
    if not reach.any():
        print("river reaches no water:", job["name"])
        return None
    # down to the output's samples: the mean over each sample's river pixels
    k = px // SUB
    h, w = water.shape[0] // k, water.shape[1] // k

    def pool(a):
        return a.reshape(h, k, w, k).sum(axis=(1, 3))
    n = pool(reach.astype(np.float32))
    sx = pool(vx) / np.maximum(n, 1)
    sy = pool(vy) / np.maximum(n, 1)
    srows = slice(rows.start * SUB, rows.stop * SUB)
    scols = slice(cols.start * SUB, cols.stop * SUB)
    print("%-50s sources %d, %8d px, %.1fs" % (job["name"], len(sources), int(reach.sum()), time.time() - t0))
    return dict(job=job, srows=srows, scols=scols, sx=sx, sy=sy, hit=n > 0,
                water=pool(water.astype(np.float32)) >= 2)


def put(world, res):
    srows, scols, hit = res["srows"], res["scols"], res["hit"]
    world.fx[srows, scols] = np.where(hit, res["sx"], world.fx[srows, scols])
    world.fy[srows, scols] = np.where(hit, res["sy"], world.fy[srows, scols])
    world.windows.append((srows, scols, res["water"]))


def touches(job, box):
    """Whether a job lies in a world box (x0, y0, x1, y1): to rebuild one area quickly while tuning."""
    rows, cols = box_slice(box)
    if "box" in job:
        jr, jc = job["box"]
    else:
        gr0, gc0, here = job["channel"]
        jr, jc = slice(gr0, gr0 + here.shape[0]), slice(gc0, gc0 + here.shape[1])
    return jr.start < rows.stop and rows.start < jr.stop and jc.start < cols.stop and cols.start < jc.stop


def build(cache, debug=None):
    world = World(cache)
    ann = load_annotations()
    jobs = auto_rivers(world)
    print("rivers found", len(jobs))
    only = os.environ.get("FLOW_ONLY")
    if only:
        box = [int(v) for v in only.split(",")]
        jobs = [j for j in jobs if touches(j, box)]
    annotated = [res for res in (run(world, j) for j in annotated_rivers(world, ann)) if res]
    # an annotation takes over the rivers it covers: drop found rivers that share water with it
    taken = np.zeros(world.water.shape, bool)
    for res in annotated:
        h = res["hit"]
        tiles = h.reshape(h.shape[0] // SUB, SUB, h.shape[1] // SUB, SUB).any(axis=(1, 3))
        taken[res["srows"].start // SUB:res["srows"].stop // SUB, res["scols"].start // SUB:res["scols"].stop // SUB] |= tiles
    for job in jobs:
        gr0, gc0, here = job["channel"]
        if (taken[gr0:gr0 + here.shape[0], gc0:gc0 + here.shape[1]] & here).any():
            print("%-50s covered by an annotation" % job["name"])
            continue
        res = run(world, job)
        if res:
            put(world, res)
    for res in annotated:
        put(world, res)
    fx, fy = world.fx, world.fy
    for s in ann["still"]:
        rows, cols = box_slice(s["box"])
        rows = slice(rows.start * SUB, rows.stop * SUB)
        cols = slice(cols.start * SUB, cols.stop * SUB)
        fx[rows, cols] = 0
        fy[rows, cols] = 0

    # specks of moving water too small to be a river (left over at cove mouths and around annotations) stay still
    moving = np.hypot(fx, fy) > 0.005
    mlab, mn = ndimage.label(moving, structure=np.ones((3, 3)))
    msize = ndimage.sum_labels(moving, mlab, np.arange(mn + 1))
    speck = moving & (msize[mlab] < MIN_RIVER_TILES * SUB * SUB)
    fx[speck] = 0
    fy[speck] = 0
    del mlab, speck

    # samples near a river's water take the flow of the nearest water sample (the map and the scene differ a bit)
    for srows, scols, wwin in world.windows:
        dist, (ir, ic) = ndimage.distance_transform_edt(~wwin, return_indices=True)
        wx, wy = fx[srows, scols], fy[srows, scols]
        near = (dist > 0) & (dist <= EXTEND * SUB)
        nx, ny = wx[ir, ic], wy[ir, ic]
        take = near & ((nx != 0) | (ny != 0))
        fx[srows, scols] = np.where(take, nx, wx)
        fy[srows, scols] = np.where(take, ny, wy)

    # known: near any water the map shows, even a channel too narrow for the tile mask
    dist = ndimage.distance_transform_edt(~(world.water | (worldmap.water_votes(cache) >= 2)))
    known_t = dist <= EXTEND
    del dist
    known = np.repeat(np.repeat(known_t, SUB, 0), SUB, 1)
    moving = (fx != 0) | (fy != 0)
    known |= moving
    # whole vectors limited to the file's range, so the direction survives
    n = np.hypot(fx, fy)
    scale = np.where(n > 1.27, 1.27 / np.maximum(n, 1e-6), 1.0).astype(np.float32)
    bx = np.where(known, np.clip(np.round(fx * scale * 100), -127, 127), UNKNOWN).astype(np.int8)
    by = np.where(known, np.clip(np.round(fy * scale * 100), -127, 127), UNKNOWN).astype(np.int8)
    if debug:
        np.savez_compressed(debug, fx=bx, fy=by, water=world.water, sinks=world.sinks, sub=SUB)
    return bx, by, known


def write(out, bx, by, known):
    rows, cols = bx.shape
    tr, tc = rows // SUB, cols // SUB
    n = 64 * SUB
    regions = []
    for rx in range(worldmap.X0 >> 6, ((worldmap.X0 + tc - 1) >> 6) + 1):
        for ry in range((worldmap.YTOP - tr + 1) >> 6, (worldmap.YTOP >> 6) + 1):
            rs, cs = worldmap.tile_slice(rx << 6, ry << 6, (rx << 6) + 63, (ry << 6) + 63)
            rs = slice(rs.start * SUB, rs.stop * SUB)
            cs = slice(cs.start * SUB, cs.stop * SUB)
            if not known[rs, cs].any():
                continue
            ex = np.full((n, n), UNKNOWN, np.int8)
            ny = np.full((n, n), UNKNOWN, np.int8)
            # rows from the south: flip the map's north-first rows (regions at the map's edge are cut off)
            r_off = max(-rs.start, 0)
            c_off = max(-cs.start, 0)
            a = bx[max(rs.start, 0):rs.stop, max(cs.start, 0):cs.stop]
            b = by[max(rs.start, 0):rs.stop, max(cs.start, 0):cs.stop]
            ex[r_off:r_off + a.shape[0], c_off:c_off + a.shape[1]] = a
            ny[r_off:r_off + b.shape[0], c_off:c_off + b.shape[1]] = b
            payload = np.stack([ex[::-1], ny[::-1]], axis=-1).astype(np.int8).tobytes()
            regions.append((rx << 8 | ry, payload))
    # each region deflated on its own: the plugin keeps them packed and unpacks only the ones a scene touches
    data = b"RLWF" + struct.pack(">HH", 3, len(regions))
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
