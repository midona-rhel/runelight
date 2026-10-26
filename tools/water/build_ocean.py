#!/usr/bin/env python3
"""
Work out which water of the world is open sea, from the world map image, and write it as the plugin's ocean.dat
resource (read by OceanMap.java). Only open sea gets shore waves; a scene sees too little of the world to tell a river
running off its edge from the sea, the map doesn't.

1. Water per tile, as for the flow map (worldmap.water_tiles).
2. Open water: tiles at least OPEN_RADIUS from any land (ground the map draws that isn't water), so river channels,
   inlets and straits narrower than twice that are cut away even where they run into the sea.
3. Sea: the pieces of open water that are huge (the world's sea) or that run out to the edge of the mapped ground
   (the separate sea maps, mapped squares of sea around an island), and aren't underground (below the surface map,
   y >= MAX_SURFACE_Y, caves have pools that touch unmapped squares too). A lake lies inside land, so it never is.
4. The sea's water tiles: its open water and the water within SHORE_REACH tiles of it, reached through water, so
   the shallows and the mouths of rivers are included but a river is not followed up its channel.

Output: "RLOM", u16 version 1, u16 region count, then per region u16 id (x >> 6 << 8 | y >> 6), u16 length and that
many bytes of zlib-deflated 512 bytes: a bit per tile, 64 x 64, row by row from the south, x fastest, least
significant bit first; set is sea. Only regions with sea are listed. Big-endian.

Usage: tools/water/build_ocean.py <cache dir> <out ocean.dat>
"""
import struct
import sys
import zlib

import numpy as np
from scipy import ndimage

import worldmap

OPEN_RADIUS = 3         # tiles from land for open water
HUGE = 100000           # open tiles: the world's sea
MIN_SEA_MAP = 1000      # open tiles of a sea map, so a speck of water inside one isn't
EDGE_REACH = 4          # tiles from unmapped squares that count as running out to the edge of the map
MIN_EDGE = 200          # open tiles that close to it
MAX_SURFACE_Y = 6400    # tiles: caves and dungeons are mapped from here up
SHORE_REACH = 2         # tiles of water around open water that are sea too
REGION = 64
VERSION = 1


def sea(cache):
    """The sea per tile (row = YTOP - y, col = x - X0)."""
    water = worldmap.water_tiles(cache)
    mapped = worldmap.mapped_tiles(cache)
    land = mapped & ~water
    core = water & (ndimage.distance_transform_edt(~land) >= OPEN_RADIUS)
    labels, n = ndimage.label(core, structure=np.ones((3, 3)))
    near_edge = ndimage.distance_transform_edt(mapped) <= EDGE_REACH
    index = np.arange(n + 1)
    area = ndimage.sum_labels(core, labels, index)
    edge = ndimage.sum_labels(core & near_edge, labels, index)
    # the northernmost tile of each piece is its first row
    first_row = np.array([0] + [s[0].start for s in ndimage.find_objects(labels)])
    underground = worldmap.YTOP - first_row >= MAX_SURFACE_Y
    keep = ((area >= HUGE) | (area >= MIN_SEA_MAP) & (edge >= MIN_EDGE)) & ~underground
    keep[0] = False
    print("sea pieces", int(keep.sum()), "of", n, "open water pieces")
    return ndimage.binary_dilation(keep[labels], structure=np.ones((3, 3)), iterations=SHORE_REACH, mask=water)


def write(ocean, path):
    h, w = ocean.shape
    regions = []
    for ry in range(h // REGION):
        for rx in range(w // REGION):
            r0, c0 = ry * REGION, rx * REGION
            block = ocean[r0:r0 + REGION, c0:c0 + REGION][::-1]
            if not block.any():
                continue
            x, y = rx + worldmap.X0 // REGION, (h // REGION - 1 - ry) + (worldmap.YTOP + 1 - h) // REGION
            bits = np.packbits(block.ravel(), bitorder="little").tobytes()
            regions.append((x << 8 | y, zlib.compress(bits, 9)))
    regions.sort()
    with open(path, "wb") as f:
        f.write(b"RLOM" + struct.pack(">HH", VERSION, len(regions)))
        for rid, data in regions:
            f.write(struct.pack(">HH", rid, len(data)) + data)
    print("regions", len(regions), "sea tiles", int(ocean.sum()), "bytes", 8 + sum(4 + len(d) for _, d in regions))


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    write(sea(sys.argv[1]), sys.argv[2])


if __name__ == "__main__":
    main()
