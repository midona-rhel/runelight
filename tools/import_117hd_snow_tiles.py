#!/usr/bin/env python3
"""
Derive Lumina's never-snow tiles (snow_tiles.json) from 117 HD's tile overrides, as its winter theme decides them.

117 HD has no list of tiles that stay clear of snow. Its winter theme works through the tile overrides' replacements:
a tile matches one override (by its overlay or underlay id and an area, else one of the overrides for any id, in the
file's order), and that override's replacements resolve under their conditions. The ones that turn a tile wintry are
"season == SeasonalTheme.WINTER" (to WINTER_GRASS, WINTER_DIRT and the like). A tile whose resolution never takes such
a replacement keeps its look in winter: castle floors, bridges, paths, docks, interiors. Lumina keeps the snow off
those tiles (SnowTiles.java), so it needs the same matching: every override in order with its ids, its area flattened
to boxes, and its replacements with their conditions.

The conditions are kept as 117 HD writes them, with "season == SeasonalTheme.WINTER" written "winter". Their
variables: h, s, l (the tile's south western colour), textures, blendingColors, blendingTextures,
blendingTexturesOnly (117 HD's options; Lumina takes their defaults), winter.

Output: {"overrides": [{"name", "ids", "boxes", "replacements", "snowy"}, ...]} in the file's order, where ids are overlay ids
with OVERLAY (1 << 31 in 117 HD; 1 << 30 here, an int's sign kept clear) or underlay ids, null for any; boxes are
[x1, y1, z1, x2, y2, z2] world tiles, inclusive, null when the override has no area (only a replacement); and
replacements are [target index or -1 for none, [condition, ...] any of which applies]; snowy marks an override whose
ground is snow in any season (a snowy mountain's), which is never clear of it.

Usage (from a 117HD/RLHD checkout):
  tools/import_117hd_snow_tiles.py <RLHD>/src/main/resources/rs117/hd/scene \
      src/main/resources/com/midonarhel/lumina/weather/snow_tiles.json

117 HD is BSD 2-Clause licensed; its notice is reproduced in LICENSE.
"""
import json
import sys

OVERLAY = 1 << 30
BIG = 1 << 30
WINTER = 'season == SeasonalTheme.WINTER'


def box(b):
    """An AABB as areas.json writes it (2 to 6 ints) as [x1, y1, z1, x2, y2, z2]."""
    if len(b) == 2:
        return [b[0], b[1], 0, b[0], b[1], 3]
    if len(b) == 3:
        return [b[0], b[1], b[2], b[0], b[1], b[2]]
    if len(b) == 4:
        return [min(b[0], b[2]), min(b[1], b[3]), 0, max(b[0], b[2]), max(b[1], b[3]), 3]
    if len(b) == 5:
        return [min(b[0], b[2]), min(b[1], b[3]), b[4], max(b[0], b[2]), max(b[1], b[3]), b[4]]
    return [min(b[0], b[3]), min(b[1], b[4]), min(b[2], b[5]), max(b[0], b[3]), max(b[1], b[4]), max(b[2], b[5])]


def region_box(frm, to, z1=0, z2=3):
    x1, y1, x2, y2 = frm >> 8, frm & 0xff, to >> 8, to & 0xff
    x1, x2 = min(x1, x2), max(x1, x2)
    y1, y2 = min(y1, y2), max(y1, y2)
    return [x1 << 6, y1 << 6, z1, ((x2 + 1) << 6) - 1, ((y2 + 1) << 6) - 1, z2]


def area_boxes(name, areas, seen=()):
    if name == 'ALL':
        return [[-BIG, -BIG, 0, BIG, BIG, 3]]
    a = areas.get(name)
    if a is None or name in seen:
        return []
    out = [box(b) for b in a.get('aabbs', [])]
    out += [region_box(r, r) for r in a.get('regions', [])]
    for rb in a.get('regionBoxes', []):
        rb = rb if isinstance(rb, list) else [rb]
        if len(rb) == 1:
            out.append(region_box(rb[0], rb[0]))
        elif len(rb) == 2:
            out.append(region_box(rb[0], rb[1]))
        elif len(rb) == 3:
            out.append(region_box(rb[0], rb[1], rb[2], rb[2]))
        else:
            out.append(region_box(rb[0], rb[1], rb[2], rb[3]))
    for other in a.get('areas', []):
        out += area_boxes(other, areas, seen + (name,))
    return out


def condition(c):
    if c is True:
        return 'true'
    if c is False or c is None:
        return 'false'
    return c.replace(WINTER, 'winter')


def main(scene, out):
    overrides = json.load(open(scene + '/tile_overrides.json'))
    areas = {a['name']: a for a in json.load(open(scene + '/areas.json'))}
    overrides = [o for o in overrides if isinstance(o, dict)]
    index = {}
    for i, o in enumerate(overrides):
        if o.get('name') and o['name'] not in index:
            index[o['name']] = i
    rows = []
    for o in overrides:
        ids = [OVERLAY | i for i in o.get('overlayIds', [])] + list(o.get('underlayIds', []))
        area = o.get('area')
        boxes = area_boxes(area, areas) if area else None
        replacements = []
        for target, cond in (o.get('replacements') or {}).items():
            conds = cond if isinstance(cond, list) else [cond]
            replacements.append([index.get(target, -1), [condition(c) for c in conds]])
        material = o.get('groundMaterial') or ''
        rows.append({'name': o.get('name') or o.get('description') or area, 'ids': ids or None,
                     'boxes': boxes if boxes else None, 'replacements': replacements or None,
                     'snowy': 'SNOW' in material or None})
    with open(out, 'w') as f:
        f.write('{"source": "117 HD tile_overrides.json and areas.json, tools/import_117hd_snow_tiles.py",\n')
        f.write(' "overrides": [\n')
        f.write(',\n'.join('  ' + json.dumps(r, separators=(',', ':')) for r in rows))
        f.write('\n]}\n')
    winter = sum(1 for r in rows if r['replacements'] and any('winter' in c for _, cs in r['replacements'] for c in cs))
    print('%d overrides, %d with a winter replacement, %d matchable' % (len(rows), winter,
        sum(1 for r in rows if r['boxes'])))


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
