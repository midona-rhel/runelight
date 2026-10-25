#!/usr/bin/env python3
"""
Convert 117 HD's light definitions into RuneLight's lights.json.

117 HD names game objects, NPCs, animations and spot animations by their gameval names. This resolves those
names to numeric IDs with 117 HD's gamevals.json, and the areas a light is restricted to to their boxes with its
areas.json (so lights.json stands alone), and keeps the fields RuneLight uses. Every definition is imported; a
field this script does not know, a name it cannot resolve, or a value 117 HD would not accept stops it.

An area box is [minX, minY, minZ, maxX, maxY, maxZ] in world tiles and planes, as in area_hiding.json. Nested
areas, regions and region boxes are resolved. A definition with areas spawns only where the light stands inside
one of them and outside every one of excludeAreas.

outdoorLighting is kept as true: 117 HD's optional position only picks the environment whose sky it samples,
and RuneLight has one sky. fadeOverlap is in 117 HD's schema, but nothing in its code reads it. alignment CENTER
is not one of 117 HD's alignments; its loader reads it as CUSTOM, which is what is left out here.

Usage (from a 117HD/RLHD checkout):
  tools/import_117hd_lights.py <RLHD>/src/main/resources/rs117/hd/scene \
      src/main/resources/com/midonarhel/runelight/lights.json

117 HD is BSD 2-Clause licensed; its notice is reproduced in LICENSE.
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from import_117hd_areas import resolver, unique  # noqa: E402
from import_117hd_water import load  # noqa: E402

ID_FIELDS = {
    "npcIds": "npcs",
    "objectIds": "objects",
    "projectileIds": "spotanims",
    "graphicsObjectIds": "spotanims",
    "animationIds": "anims",
    "excludeAnimationIds": "anims",
}
# copied as they are
KEPT = [
    "description", "worldX", "worldY", "plane", "height", "radius", "strength", "duration", "range", "offset",
    "visibleFromOtherPlanes", "nightMultiplier", "fadeInDuration", "fadeOutDuration", "spawnDelay", "despawnDelay",
    "fixedDespawnTime", "despawnWithParent", "ignoreActorHiding", "waitForAnimation",
]
# read below
CONVERTED = ["color", "type", "alignment", "schedule", "outdoorLighting", "areas", "excludeAreas"] + list(ID_FIELDS)
# in 117 HD's schema, read by none of its code
UNUSED = ["fadeOverlap"]

TYPES = ["STATIC", "FLICKER", "PULSE"]
ALIGNMENTS = [
    "NORTH", "NORTHEAST", "NORTHEAST_CORNER", "EAST", "SOUTHEAST", "SOUTHEAST_CORNER", "SOUTH", "SOUTHWEST",
    "SOUTHWEST_CORNER", "WEST", "NORTHWEST", "NORTHWEST_CORNER", "BACK", "BACKLEFT", "BACKLEFT_CORNER", "LEFT",
    "FRONTLEFT", "FRONTLEFT_CORNER", "FRONT", "FRONTRIGHT", "FRONTRIGHT_CORNER", "RIGHT", "BACKRIGHT",
    "BACKRIGHT_CORNER",
]


def load_gamevals(path):
    text = open(path, encoding="utf-8").read()
    # the file starts with a comment line
    return json.loads(text[text.index("{"):])


def color(c):
    """Colors come as [r, g, b] in 0-255 or as "#rrggbb"; return [r, g, b] in 0-255."""
    if isinstance(c, str):
        h = c.lstrip("#")
        return [int(h[i:i + 2], 16) for i in (0, 2, 4)]
    return [round(float(v), 3) for v in c]


def schedule(s, where):
    """Only the night-time schedule exists in RuneLight (its randomOffset is not used)."""
    if s == "NIGHT":
        return "NIGHT"
    if isinstance(s, dict) and s.get("during") == "NIGHT" and not s.keys() - {"during", "randomOffset"}:
        return "NIGHT"
    raise SystemExit(f"unsupported schedule {s!r} in {where}")


def resolve(names, table, where):
    ids = []
    for n in names:
        if isinstance(n, int):
            ids.append(n)
        elif n in table:
            ids.append(table[n])
        else:
            raise SystemExit(f"unresolved name {n!r} in {where}")
    return sorted(set(ids))


def outdoor_lighting(v, where):
    if isinstance(v, bool):
        return v
    if isinstance(v, list) and len(v) == 3 and all(isinstance(i, int) for i in v):
        return True
    raise SystemExit(f"outdoorLighting must be a boolean or [ worldX, worldY, plane ] in {where}")


class Areas:
    """Area names and inline boxes to boxes, failing on names areas.json does not have."""

    def __init__(self, raw):
        self.names = {a["name"] for a in raw if a.get("name")} | {"ALL"}
        self.resolve, self.boxes = resolver(raw)

    def of(self, entries, where):
        for e in entries:
            if isinstance(e, str) and e not in self.names:
                raise SystemExit(f"unknown area {e!r} in {where}")
        return unique(self.boxes(entries))


def convert(d, gamevals, areas):
    where = d.get("description")
    unknown = d.keys() - set(KEPT) - set(CONVERTED) - set(UNUSED)
    if unknown:
        raise SystemExit(f"unknown fields {sorted(unknown)} in {where}")

    e = {k: d[k] for k in KEPT if k in d}
    e["color"] = color(d["color"])
    if "type" in d:
        if d["type"] not in TYPES:
            raise SystemExit(f"unknown type {d['type']!r} in {where}")
        e["type"] = d["type"]
    # CENTER is CUSTOM, as 117 HD reads it
    if d.get("alignment") not in (None, "CENTER", "CUSTOM"):
        if d["alignment"] not in ALIGNMENTS:
            raise SystemExit(f"unknown alignment {d['alignment']!r} in {where}")
        e["alignment"] = d["alignment"]
    if d.get("schedule") is not None:
        e["schedule"] = schedule(d["schedule"], where)
    if d.get("outdoorLighting") is not None and outdoor_lighting(d["outdoorLighting"], where):
        e["outdoorLighting"] = True
    for field in ("areas", "excludeAreas"):
        # 117 HD restricts nothing when the list is empty
        boxes = areas.of(d.get(field) or [], f"{field} of {where}")
        if boxes:
            e[field] = boxes
    for field, table in ID_FIELDS.items():
        if d.get(field):
            e[field] = resolve(d[field], gamevals[table], where)
    return e


def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    scene, out_path = sys.argv[1], sys.argv[2]
    lights = load(f"{scene}/lights.json")
    gamevals = load_gamevals(f"{scene}/gamevals.json")
    areas = Areas(load(f"{scene}/areas.json"))

    out = [convert(d, gamevals, areas) for d in lights]

    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(out, f, separators=(",", ":"))
        f.write("\n")
    counts = {k: sum(1 for e in out if e.get(k)) for k in
              ("areas", "excludeAreas", "animationIds", "waitForAnimation", "outdoorLighting", "alignment", "schedule")}
    print(f"wrote {len(out)} of {len(lights)} lights; {counts}")


if __name__ == "__main__":
    main()
