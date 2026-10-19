"""
Flow through one river at the map's full resolution (4 pixels per tile).

The river is solved as potential flow: water enters at its sources, leaves at its outlets, and the banks let none
through. That makes the current follow the channel around every bend, split around islets and rejoin, run faster
through narrows and slower where the channel widens (the same water through a wider cross-section), drift slowly
across a lake between its inflow and outflow, and leave side pools and bulges off the main path almost still. On
top of that the speed falls off toward the banks (fastest in the middle of the channel) and fades out where the
river meets open water.
"""
import numpy as np
from scipy import ndimage, sparse
from scipy.sparse.linalg import spsolve
from skimage.graph import MCP_Geometric

import worldmap

PX = worldmap.PX
CLOSE_PX = 11         # pixels: bridges, map icons (15 px with their rim) and labels over a river are closed over
SPECK_PX = 160        # smaller pixel water specks are icons or decoration
SOURCE_PX = 6         # radius of the disk a source's water wells up from
PROFILE_BANK = 0.3    # speed at the bank relative to the middle (parabolic profile across the channel)
WIDE_FROM = 1.5       # water wider than this many times the river's channel is a pond or lake and calms down
MAX_GAIN = 2.2        # fastest narrows relative to the river's typical channel
MAX_SPEED = 1.27      # tiles per second (the file's limit)


def disk(r):
    y, x = np.ogrid[-r:r + 1, -r:r + 1]
    return x * x + y * y <= r * r + r


def upsample(a):
    return np.repeat(np.repeat(a, PX, 0), PX, 1)


def pixel_water(cache, rows, cols, tile_water):
    """Water pixels of the window (tile slices), with bridges, map icons and labels over the water closed over."""
    m = CLOSE_PX // PX + 1
    big = worldmap.water_pixels(cache, slice(rows.start - m, rows.stop + m), slice(cols.start - m, cols.stop + m))
    # closing as dilation then erosion, by distance: fast for a large disk
    grown = ndimage.distance_transform_edt(~big) <= CLOSE_PX
    closed = ndimage.distance_transform_edt(grown) > CLOSE_PX
    near = upsample(ndimage.binary_dilation(tile_water, structure=np.ones((3, 3)), iterations=3))
    pad = np.zeros(big.shape, bool)
    pad[m * PX:-m * PX, m * PX:-m * PX] = near
    water = big | bridges(big, closed & ~big & pad)
    water = water[m * PX:-m * PX, m * PX:-m * PX]
    lab, n = ndimage.label(water)
    if n:
        size = ndimage.sum_labels(water, lab, np.arange(n + 1))
        water &= size[lab] >= SPECK_PX
    return water


def bridges(water, gaps):
    """
    The gaps a closing found that are things drawn over the water (a bridge or map icon across a channel), not land
    between two waters side by side (a pond next to a river): a bridge meets the water on each side across that
    water's width, a strip of land between two waters runs along them, farther than they are wide.
    """
    lab, n = ndimage.label(gaps, structure=np.ones((3, 3)))
    keep = np.zeros(n + 1, bool)
    for k, sl in enumerate(ndimage.find_objects(lab), start=1):
        if sl is None:
            continue
        r0, c0 = max(sl[0].start - 32, 0), max(sl[1].start - 32, 0)
        r1, c1 = min(sl[0].stop + 32, water.shape[0]), min(sl[1].stop + 32, water.shape[1])
        piece = lab[r0:r1, c0:c1] == k
        if piece.sum() < 24:
            keep[k] = True  # specks: text, outlines
            continue
        w = water[r0:r1, c0:c1]
        contact = ndimage.binary_dilation(piece, structure=np.ones((3, 3))) & w
        wlab, wn = ndimage.label(w)
        # blue pixels inside an icon (fish, potions) are no water of their own
        wsize = ndimage.sum_labels(w, wlab, np.arange(wn + 1))
        sides = [i for i in np.unique(wlab[contact]) if i and wsize[i] >= SPECK_PX]
        if len(sides) < 2:
            keep[k] = True  # an icon on the edge of the water, or a gap in water joined around it anyway
            continue
        # the two biggest waters it touches
        sizes = [(int((contact & (wlab == i)).sum()), i) for i in sides]
        sizes.sort(reverse=True)
        pts = [np.argwhere(contact & (wlab == i)).astype(np.float64) for _, i in sizes[:2]]
        axis = pts[1].mean(0) - pts[0].mean(0)
        norm = np.hypot(*axis)
        if norm < 1e-6:
            keep[k] = True
            continue
        perp = np.array([-axis[1], axis[0]]) / norm
        # each water's width a little away from the gap (right at it the gap itself is its bank)
        edt = ndimage.distance_transform_edt(w)
        dist = ndimage.distance_transform_edt(~contact)
        ok = True
        for (_, i), p in zip(sizes[:2], pts):
            span = np.ptp(p @ perp) + 1
            width = 2 * edt[(dist <= 30) & (wlab == i)].max()
            ok &= span <= 1.3 * width + 4
        keep[k] = ok
    return keep[lab]


def local_thickness(edt, mask):
    """Radius of the largest disk inside the water that covers each pixel: the channel's half width there."""
    out = np.where(mask, edt, 0).astype(np.float32)
    top = int(min(np.ceil(edt[mask].max()) if mask.any() else 0, 48))
    for r in range(2, top + 1):
        centres = mask & (edt >= r)
        if not centres.any():
            break
        cover = (ndimage.distance_transform_edt(~centres) <= r) & mask
        out = np.where(cover, np.maximum(out, r), out)
    return out


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


def shift(a, dr, dc, fill):
    """b[r, c] = a[r + dr, c + dc], fill outside."""
    b = np.full_like(a, fill)
    h, w = a.shape
    b[max(-dr, 0):h - max(dr, 0), max(-dc, 0):w - max(dc, 0)] = a[max(dr, 0):h - max(-dr, 0), max(dc, 0):w - max(-dc, 0)]
    return b


def masked_blur(v, mask, sigma):
    w = ndimage.gaussian_filter(mask.astype(np.float32), sigma)
    s = ndimage.gaussian_filter(np.where(mask, v, 0).astype(np.float32), sigma)
    return np.where(mask, s / np.maximum(w, 1e-6), 0)


def farthest(piece, start):
    """The pixel of `piece` farthest from `start` through it."""
    rs, cs = np.nonzero(piece)
    r0, c0 = rs.min(), cs.min()
    sub = piece[r0:rs.max() + 1, c0:cs.max() + 1]
    mcp = MCP_Geometric(np.where(sub, 1.0, np.inf), fully_connected=True)
    d, _ = mcp.find_costs(np.argwhere(start[r0:rs.max() + 1, c0:cs.max() + 1]))
    d = np.where(sub & np.isfinite(d), d, -1)
    r, c = np.unravel_index(np.argmax(d), d.shape)
    return r + r0, c + c0


def solve(water, allowed, outlet, sources, ramp, speed):
    """
    Flow of one river in a pixel window.

    water: water pixels; allowed: where this river's water may run; outlet: pixels the river drains into (held at
    zero potential); sources: pixel (row, col) points it rises at; ramp: 0-1 speed factor per pixel (calm toward
    open water); speed: tiles per second of the river's typical channel.

    Returns (east, north) flow per pixel in tiles per second and the pixels the river reaches.
    """
    dom = water & allowed
    lab, n = ndimage.label(dom)
    q = np.zeros(dom.shape, np.float32)
    rr_, cc_ = np.ogrid[:dom.shape[0], :dom.shape[1]]
    for r, c in sources:
        near = dom & ((rr_ - r) ** 2 + (cc_ - c) ** 2 <= SOURCE_PX * SOURCE_PX)
        if not near.any():
            continue
        q[near] += 1.0 / near.sum()
    fixed = outlet & dom
    # A piece of the river cut off from the rest (caves: the map doesn't show where the water goes) runs from its
    # source to its far end, or from its far end into its outlet.
    lab_src = np.unique(lab[q > 0])
    lab_out = np.unique(lab[fixed])
    for k in set(lab_src.tolist()) ^ set(lab_out.tolist()):
        if k == 0:
            continue
        piece = lab == k
        start = q > 0 if k in lab_src else fixed
        far = farthest(piece, start & piece)
        if k in lab_src:
            fixed = fixed | (piece & ((rr_ - far[0]) ** 2 + (cc_ - far[1]) ** 2 <= SOURCE_PX * SOURCE_PX))
        else:
            near = piece & ((rr_ - far[0]) ** 2 + (cc_ - far[1]) ** 2 <= SOURCE_PX * SOURCE_PX)
            q[near] += 1.0 / near.sum()
    # only water joined to both a source and an outlet runs
    keep = np.zeros(n + 1, bool)
    keep[np.unique(lab[fixed])] = True
    has_src = np.zeros(n + 1, bool)
    has_src[np.unique(lab[q > 0])] = True
    keep &= has_src
    keep[0] = False
    reach = keep[lab]
    zero = (np.zeros(dom.shape, np.float32), np.zeros(dom.shape, np.float32), reach)
    if not reach.any():
        return zero

    unknown = reach & ~fixed
    idx = np.full(dom.shape, -1, np.int64)
    idx[unknown] = np.arange(int(unknown.sum()))
    rows, cols, vals = [], [], []
    deg = np.zeros(dom.shape, np.float64)
    for dr, dc in ((0, 1), (0, -1), (1, 0), (-1, 0)):
        nb_reach = shift(reach, dr, dc, False)
        nb_idx = shift(idx, dr, dc, -1)
        deg += unknown & nb_reach
        link = unknown & (nb_idx >= 0)
        rows.append(idx[link])
        cols.append(nb_idx[link])
        vals.append(np.full(int(link.sum()), -1.0))
    i = idx[unknown]
    rows.append(i)
    cols.append(i)
    vals.append(deg[unknown])
    m = int(unknown.sum())
    a = sparse.csc_matrix((np.concatenate(vals), (np.concatenate(rows), np.concatenate(cols))), shape=(m, m))
    phi = np.full(dom.shape, np.nan)
    phi[fixed & reach] = 0
    phi[unknown] = spsolve(a, q[unknown].astype(np.float64))

    gx, gy = gradient(phi)
    # map rows run south: north is -row; water runs down the potential
    vx, vy = -gx, gy
    vx = np.where(reach, vx, 0)
    vy = np.where(reach, vy, 0)
    g = np.hypot(vx, vy)
    # direction, lightly smoothed; speed from the potential's slope, smoothed a little more (inner corners of bends
    # are singular in potential flow)
    dx = masked_blur(vx / np.maximum(g, 1e-12), reach, 1.0)
    dy = masked_blur(vy / np.maximum(g, 1e-12), reach, 1.0)
    dn = np.hypot(dx, dy)
    dx, dy = dx / np.maximum(dn, 1e-6), dy / np.maximum(dn, 1e-6)
    g = masked_blur(g, reach, 2.0)

    edt = ndimage.distance_transform_edt(water)
    thick = local_thickness(edt, water)
    s = np.clip((edt - 0.5) / np.maximum(thick - 0.5, 0.5), 0, 1)
    profile = (PROFILE_BANK + (1 - PROFILE_BANK) * s * (2 - s)) / (PROFILE_BANK + (1 - PROFILE_BANK) * 2 / 3.0)

    # the river's typical channel: its middle, away from open water, on the path the water takes
    central = reach & (s >= 0.6) & (ramp > 0.95) & (g > 0)
    if central.sum() < 8:
        central = reach & (g > 0)
    if not central.any():
        return zero
    g_ref = np.percentile(g[central], 75)
    gain = np.minimum(g / max(g_ref, 1e-12), MAX_GAIN)
    # Ponds and bulges the river passes through or past barely move: potential flow alone still drifts across them
    # (and the side of a pond the river skirts is the pond, not the channel)
    main = reach & (g >= 0.5 * g_ref)
    width_ref = np.median(thick[main]) if main.any() else np.median(thick[reach])
    wide = np.maximum(thick / max(width_ref, 1.0) - WIDE_FROM, 0)
    calm = 1 / (1 + wide * wide)
    v = np.minimum(speed * gain * profile * ramp * calm, MAX_SPEED)
    v = np.where(dn > 1e-3, v, 0)
    return (np.where(reach, dx * v, 0).astype(np.float32), np.where(reach, dy * v, 0).astype(np.float32), reach)
