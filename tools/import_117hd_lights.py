#!/usr/bin/env python3
"""
Convert 117 HD's light definitions into RuneLight's lights.json.

117 HD names game objects, NPCs and spot animations by their gameval names. This resolves those names to
numeric IDs with 117 HD's gamevals.json, keeps only the fields RuneLight uses, and drops definitions it
does not support yet (lights restricted to named areas, and lights tied to an animation playing).

Usage (from a 117HD/RLHD checkout):
  tools/import_117hd_lights.py <RLHD>/src/main/resources/rs117/hd/scene/lights.json \
      <RLHD>/src/main/resources/rs117/hd/scene/gamevals.json \
      src/main/resources/com/midonarhel/runelight/lights.json

117 HD is BSD 2-Clause licensed; its notice is reproduced in LICENSE.
"""
import json
import sys

ID_FIELDS = {
    "npcIds": "npcs",
    "objectIds": "objects",
    "projectileIds": "spotanims",
    "graphicsObjectIds": "spotanims",
}
KEPT = [
    "description", "worldX", "worldY", "plane", "height", "radius", "strength", "color", "type",
    "duration", "range", "offset", "alignment", "visibleFromOtherPlanes", "nightMultiplier", "schedule",
    "fadeInDuration", "fadeOutDuration",
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


def schedule(s):
    """Only night-time schedules are supported; returns "NIGHT", None, or False for unsupported."""
    if s is None:
        return None
    if s == "NIGHT" or (isinstance(s, dict) and s.get("during") == "NIGHT"):
        return "NIGHT"
    return False


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


def main():
    if len(sys.argv) != 4:
        raise SystemExit(__doc__)
    lights = json.load(open(sys.argv[1], encoding="utf-8"))
    gamevals = load_gamevals(sys.argv[2])

    out, skipped = [], {"areas": 0, "animation": 0, "unsupported schedule": 0}
    for d in lights:
        if d.get("areas") or d.get("excludeAreas"):
            skipped["areas"] += 1
            continue
        if d.get("animationIds") or d.get("waitForAnimation"):
            skipped["animation"] += 1
            continue
        sched = schedule(d.get("schedule"))
        if sched is False:
            skipped["unsupported schedule"] += 1
            continue
        e = {k: d[k] for k in KEPT if k in d}
        e["color"] = color(d["color"])
        if sched:
            e["schedule"] = sched
        for field, table in ID_FIELDS.items():
            if d.get(field):
                e[field] = resolve(d[field], gamevals[table], d.get("description"))
        out.append(e)

    with open(sys.argv[3], "w", encoding="utf-8") as f:
        json.dump(out, f, separators=(",", ":"))
        f.write("\n")
    print(f"wrote {len(out)} lights, skipped {skipped}")


if __name__ == "__main__":
    main()
