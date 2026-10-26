"""
The OSRS world map image the water flow map is computed from, and how its pixels map to world tiles.

Source: Explv's osrs_map_tiles (https://github.com/Explv/osrs_map_tiles), generated_images/current-map-image-0.png,
rendered from the game cache by RuneLite's MapImageDumper (4 pixels per tile, plane 0, regions 15..65 x 19..196).
Only data derived from it (flow per tile) ships with the plugin, never the image.

Pixel to tile, checked on Lumbridge castle (3205-3217, 3205-3229), the Lumbridge - Al Kharid bridge over the River
Lum (3236-3244, 3226) and the Lumbridge fishing spots: tile x = column / 4 + 960, tile y = 12607 - row / 4.
"""
import os
import urllib.request

import numpy as np
from PIL import Image
from scipy import ndimage

COMMIT = "1937ccfa4007345ef62f7694e773509deac8a1d7"
URL = "https://raw.githubusercontent.com/Explv/osrs_map_tiles/" + COMMIT + "/generated_images/current-map-image-0.png"
PX = 4
X0 = 960      # world x of the first column
YTOP = 12607  # world y of the first row
MIN_VOTES = 8          # of 16 pixels
CLOSE_RADIUS = 2       # tiles of land a river may be interrupted by (bridges, icons)
MIN_SPECK = 10         # smaller water specks are map icons


def download(cache):
    os.makedirs(cache, exist_ok=True)
    png = os.path.join(cache, "map-0.png")
    if not os.path.exists(png):
        print("downloading", URL)
        urllib.request.urlretrieve(URL, png + ".part")
        os.replace(png + ".part", png)
    return png


def full_image(cache):
    """The map as a (rows, cols, 3) uint8 array, memory mapped from a cached .npy."""
    npy = os.path.join(cache, "map-0.npy")
    if not os.path.exists(npy):
        Image.MAX_IMAGE_PIXELS = None
        a = np.asarray(Image.open(download(cache)).convert("RGB"))
        m = np.lib.format.open_memmap(npy + ".part", mode="w+", dtype=np.uint8, shape=a.shape)
        m[:] = a
        m.flush()
        del m
        os.replace(npy + ".part", npy)
    return np.load(npy, mmap_mode="r")


def is_water(a):
    """Water pixels of an (..., 3) RGB array: the water textures are drawn blue; land, roads, sand and swamp ground
    are not."""
    a = a.astype(np.int16)
    red, green, blue = a[..., 0], a[..., 1], a[..., 2]
    return (blue - red >= 30) & (blue - green >= 20) & (blue >= 90)


def water_pixels(cache, rows, cols):
    """Water pixels of the tiles in the tile slices rows, cols (4 x 4 per tile); off the map is not water."""
    full = full_image(cache)
    h, w = full.shape[0] // PX, full.shape[1] // PX
    out = np.zeros(((rows.stop - rows.start) * PX, (cols.stop - cols.start) * PX), bool)
    r0, r1 = max(rows.start, 0), min(rows.stop, h)
    c0, c1 = max(cols.start, 0), min(cols.stop, w)
    if r0 < r1 and c0 < c1:
        out[(r0 - rows.start) * PX:(r1 - rows.start) * PX, (c0 - cols.start) * PX:(c1 - cols.start) * PX] = \
            is_water(full[r0 * PX:r1 * PX, c0 * PX:c1 * PX])
    return out


def water_votes(cache):
    """Per tile (row = YTOP - y, col = x - X0): how many of its 16 pixels have the map's water colors."""
    npy = os.path.join(cache, "votes.npy")
    if os.path.exists(npy):
        return np.load(npy)
    full = full_image(cache)
    h, w, _ = full.shape
    votes = np.zeros((h // PX, w // PX), np.uint8)
    for r in range(0, h, 4096):
        water = is_water(full[r:r + 4096])
        rows = water.shape[0] // PX
        votes[r // PX:r // PX + rows] = water.reshape(rows, PX, w // PX, PX).sum(axis=(1, 3))
    np.save(npy, votes)
    return votes


def mapped_tiles(cache):
    """Per tile (row = YTOP - y, col = x - X0): whether the map draws anything there; unmapped squares are black."""
    npy = os.path.join(cache, "mapped.npy")
    if os.path.exists(npy):
        return np.load(npy)
    full = full_image(cache)
    h, w, _ = full.shape
    mapped = np.zeros((h // PX, w // PX), bool)
    for r in range(0, h, 4096):
        drawn = full[r:r + 4096].any(axis=2)
        rows = drawn.shape[0] // PX
        mapped[r // PX:r // PX + rows] = drawn.reshape(rows, PX, w // PX, PX).any(axis=(1, 3))
    np.save(npy, mapped)
    return mapped


def disk(r):
    y, x = np.ogrid[-r:r + 1, -r:r + 1]
    return x * x + y * y <= r * r + r


def water_tiles(cache):
    """Water per tile (row = YTOP - y, col = x - X0): voted per tile, closed over bridges, docks and map icons so a
    river stays one piece, and specks smaller than an icon dropped."""
    votes = water_votes(cache)
    raw = votes >= MIN_VOTES
    water = ndimage.binary_closing(raw, structure=disk(CLOSE_RADIUS)) | raw
    labels, n = ndimage.label(water, structure=np.ones((3, 3)))
    sizes = ndimage.sum_labels(water, labels, np.arange(n + 1))
    return water & (sizes[labels] >= MIN_SPECK)


def tile_slice(x0, y0, x1, y1):
    """Array slices for world tiles x0..x1, y0..y1 (inclusive) in tile arrays; row 0 is the northernmost."""
    return slice(YTOP - y1, YTOP - y0 + 1), slice(x0 - X0, x1 - X0 + 1)


def world(row, col):
    return col + X0, YTOP - row
