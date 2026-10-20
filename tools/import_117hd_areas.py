#!/usr/bin/env python3
"""
Convert 117 HD's areas that hide the rest of the map (hideOtherAreas) into RuneLight's area_hiding.json.

Each entry keeps the order of 117 HD's areas.json, as which area the player is in is the first that contains them:
  - area: the boxes the player is inside, [minX, minY, minZ, maxX, maxY, maxZ] in world tiles and planes, with
    regions, region boxes and the other areas it lists resolved
  - unhide: boxes shown as well while the player is in the area, never what puts them in it

Usage (from a 117HD/RLHD checkout):
  tools/import_117hd_areas.py <RLHD>/src/main/resources/rs117/hd/scene \
      src/main/resources/com/midonarhel/runelight/area_hiding.json

117 HD is BSD 2-Clause licensed; its notice is reproduced in LICENSE.
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from import_117hd_water import MAX_Z, MIN_Z, aabb, load, region_box  # noqa: E402


def resolver(raw):
    by_name = {a["name"]: a for a in raw if a.get("name")}
    done = {"ALL": [[-(1 << 30), -(1 << 30), MIN_Z, 1 << 30, 1 << 30, MAX_Z]], "NONE": []}

    def boxes(entries):
        out = []
        for e in entries or []:
            if isinstance(e, str):
                out += resolve(e)
            elif isinstance(e, list):
                b = aabb([i for i in e if i is not None])
                if b:
                    out.append(b)
        return out

    def resolve(name):
        if name in done:
            return done[name]
        a = by_name.get(name)
        if a is None:
            return []
        done[name] = []
        out = boxes(a.get("aabbs"))
        for r in a.get("regions") or []:
            x, y = (r >> 8) << 6, (r & 0xFF) << 6
            out.append([x, y, MIN_Z, x + 63, y + 63, MAX_Z])
        for rb in a.get("regionBoxes") or []:
            out.append(region_box(rb))
        for other in a.get("areas") or []:
            out += resolve(other)
        done[name] = out
        return out

    return resolve, boxes


def unique(boxes):
    seen, out = set(), []
    for b in boxes:
        if tuple(b) not in seen:
            seen.add(tuple(b))
            out.append(b)
    return out


def main():
    scene, out_path = sys.argv[1], sys.argv[2]
    raw = load(f"{scene}/areas.json")
    resolve, boxes = resolver(raw)

    areas = []
    for a in raw:
        if not a.get("hideOtherAreas") or not a.get("name"):
            continue
        area = unique(resolve(a["name"]))
        if area:
            areas.append({"name": a["name"], "area": area, "unhide": unique(boxes(a.get("unhideAreas")))})

    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(areas, f, separators=(",", ":"))
    print(f"{len(areas)} areas hide the rest, {sum(len(a['area']) for a in areas)} boxes, "
          f"{sum(len(a['unhide']) for a in areas)} unhidden boxes")


if __name__ == "__main__":
    main()
