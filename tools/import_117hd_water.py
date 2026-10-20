#!/usr/bin/env python3
"""
Convert 117 HD's water definitions into RuneLight's water_overrides.json.

Two things are taken over as 117 HD has them:
  - tiles: its tile overrides, in order, each with the overlay and underlay ids it matches, its area as boxes and
    the water type it gives (null where it gives none, so a tile it matches first keeps its vanilla water texture's
    type). WaterOverrides.java matches them the way 117 HD's TileOverrideManager does.
  - effects: the game objects whose ripples 117 HD hides (its model overrides' hideAsWaterEffect): either the whole
    model, or the faces of the first color rule matching each untextured face (a, h, s, l of its color), in order.

117 HD's water types are mapped to RuneLight's (FRESH, OCEAN, OCEAN_DARK, OCEAN_PALE, SWAMP, CAVE).

Usage (from a 117HD/RLHD checkout):
  tools/import_117hd_water.py <RLHD>/src/main/resources/rs117/hd/scene \
      src/main/resources/com/midonarhel/runelight/water_overrides.json

117 HD is BSD 2-Clause licensed; its notice is reproduced in LICENSE.
"""
import json
import re
import sys

MIN_Z, MAX_Z = -1, 9
WATER_TYPES = {
    "WATER": "FRESH", "WATER_FLAT": "FRESH", "PLAIN_WATER": "FRESH", "LIGHT_BLUE_WATER": "FRESH",
    "ICE": "FRESH", "ICE_FLAT": "FRESH",
    "DARK_BLUE_WATER": "OCEAN_DARK",
    "CAVE_WATER": "CAVE", "CAVE_WATER_MURKY": "CAVE", "MLM_WATER": "CAVE",
    "SWAMP_WATER": "SWAMP", "SWAMP_WATER_FLAT": "SWAMP", "CAVE_WATER_SWAMP": "SWAMP", "MUDDY_WATER": "SWAMP",
    "POISON_WASTE": "SWAMP", "SCAR_SLUDGE": "SWAMP", "BLACK_TAR_FLAT": "SWAMP", "ABYSS_BILE": "SWAMP",
    "ARAXXOR_WASTE": "SWAMP", "WHISPERER_WATER": "SWAMP", "BLOOD": "SWAMP", "BLOOD_DARK": "SWAMP",
    "BLOOD_FLAT": "SWAMP",
}


def load(path):
    text = open(path, encoding="utf-8").read()
    text = re.sub(r"^\s*//.*$", "", text, flags=re.M)
    text = re.sub(r",(\s*[\]}])", r"\1", text)
    return json.loads(text[min(i for i in (text.find("["), text.find("{")) if i >= 0):])


def aabb(ints):
    n = len(ints)
    if n == 2:
        x, y = ints
        return [x, y, MIN_Z, x, y, MAX_Z]
    if n == 3:
        x, y, z = ints
        return [x, y, z, x, y, z]
    if n == 4:
        x1, y1, x2, y2 = ints
        return [min(x1, x2), min(y1, y2), MIN_Z, max(x1, x2), max(y1, y2), MAX_Z]
    if n == 5:
        x1, y1, x2, y2, z = ints
        return [min(x1, x2), min(y1, y2), z, max(x1, x2), max(y1, y2), z]
    if n == 6:
        x1, y1, z1, x2, y2, z2 = ints
        return [min(x1, x2), min(y1, y2), min(z1, z2), max(x1, x2), max(y1, y2), max(z1, z2)]
    return None


def region_box(ints):
    if len(ints) == 1:
        ints = [ints[0], ints[0]]
    frm, to = ints[0], ints[1]
    p1, p2 = (ints[2], ints[2]) if len(ints) == 3 else (ints[2], ints[3]) if len(ints) == 4 else (0, 3)
    x1, x2 = sorted((frm >> 8, to >> 8))
    y1, y2 = sorted((frm & 0xFF, to & 0xFF))
    return [x1 << 6, y1 << 6, p1, ((x2 + 1) << 6) - 1, ((y2 + 1) << 6) - 1, p2]


def resolve_areas(raw):
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
        # containsPoint takes the areas it unhides as well
        out += boxes(a.get("unhideAreas"))
        done[name] = out
        return out

    return resolve


def water_type(name):
    if not name or name == "NONE":
        return None
    if name in WATER_TYPES:
        return WATER_TYPES[name]
    # sailing's seas by name
    if name.startswith("WATER_"):
        return "OCEAN_PALE" if any(k in name for k in ("ICY", "CRYSTAL", "ENDLESS")) else "OCEAN"
    raise SystemExit(f"unmapped water type {name}")


def condition(expr):
    """117 HD's color condition (a, h, s, l compared with integers, joined by && and ||) as OR of ANDs."""
    if isinstance(expr, bool):
        return [[]] if expr else []
    if isinstance(expr, int):
        return [[["hsl", "==", expr]]]
    out = []
    for term in expr.split("||"):
        clauses = []
        for c in term.split("&&"):
            m = re.fullmatch(r"\s*(ahsl|hsl|[ahsl])\s*(==|!=|<=|>=|<|>)\s*(\d+)\s*", c)
            if not m:
                raise SystemExit(f"unsupported color condition {expr!r}")
            clauses.append([m.group(1), m.group(2), int(m.group(3))])
        out.append(clauses)
    return out


def prune(tiles):
    """
    Drops the overrides that give no water and can't keep a tile from one that does: one only matters where it could
    be matched first on a tile some water override covers (sharing an id with it, an overlay ahead of a water
    underlay, or matching any id ahead of it).
    """
    def ids(t):
        return {("o", i) for i in t["overlays"] or []} | {("u", i) for i in t["underlays"] or []}

    def meets(t, others):
        return any(a[0] <= b[3] and b[0] <= a[3] and a[1] <= b[4] and b[1] <= a[4] and a[2] <= b[5] and b[2] <= a[5]
            for w in others for a in t["area"] for b in w["area"])

    water = [t for t in tiles if t["type"]]
    water_underlays = [t for t in water if t["underlays"]]
    water_any = [t for t in water if not ids(t)]
    kept = []
    for t in tiles:
        if t["type"]:
            kept.append(t)
        elif not ids(t):
            if meets(t, water):
                kept.append(t)
        elif (meets(t, [w for w in water if ids(w) & ids(t)]) or t["overlays"] and meets(t, water_underlays)
                or meets(t, water_any)):
            kept.append(t)
    return kept


def main():
    scene, out_path = sys.argv[1], sys.argv[2]
    resolve = resolve_areas(load(f"{scene}/areas.json"))
    gamevals = load(f"{scene}/gamevals.json")
    objects = gamevals["objects"]

    tiles = []
    for o in load(f"{scene}/tile_overrides.json"):
        if not o.get("area") or o["area"] == "NONE":
            continue
        tiles.append({
            "overlays": o.get("overlayIds"),
            "underlays": o.get("underlayIds"),
            "type": water_type(o.get("waterType")),
            "area": resolve(o["area"]),
        })

    tiles = prune(tiles)

    effects = []
    for o in load(f"{scene}/model_overrides.json"):
        rules = o.get("colorOverrides") or []
        if not o.get("hideAsWaterEffect") and not any(r.get("hideAsWaterEffect") for r in rules):
            continue
        ids = sorted({objects[i] if isinstance(i, str) else i for i in o.get("objectIds") or []})
        if not ids:
            continue  # NPCs aren't static scenery
        e = {"objects": ids}
        if o.get("areas"):
            e["area"] = [b for a in o["areas"] for b in resolve(a)]
        if o.get("hideAsWaterEffect"):
            e["hide"] = True
        else:
            e["rules"] = []
            for r in rules:
                colors = r.get("colors")
                when = []
                for c in colors if isinstance(colors, list) else [colors]:
                    when += condition(c)
                e["rules"].append({"when": when, "hide": bool(r.get("hideAsWaterEffect"))})
        effects.append(e)

    with open(out_path, "w", encoding="utf-8") as f:
        json.dump({"tiles": tiles, "effects": effects}, f, separators=(",", ":"))
    print(f"{len(tiles)} tile overrides ({sum(1 for t in tiles if t['type'])} water), {len(effects)} ripple effects")


if __name__ == "__main__":
    main()
