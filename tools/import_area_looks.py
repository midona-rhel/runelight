#!/usr/bin/env python3
"""
Build Lumina's area_looks.json, the look of each area for the sky module (sky.AreaLook), from 117 HD's
environments.json and areas.json plus the hand overrides below. The old RuneLight renderer's
tools/import_environments.py, cut down: no biomes, no cave regions, no boxes (Lumina picks the environment with
117 HD's own EnvironmentManager and looks the entry up by name), and none of 117 HD's fog data.

Usage, from the repo root (the defaults are the repo's own copies of 117 HD's files):
  python tools/import_area_looks.py [<scene dir> [<output>]]

One entry per 117 HD environment, keyed as Lumina's Environment.toString() does: the key, or the area name without
one. Duplicate names (the varbit-gated Blood Moon variants of Morytania, the ToB rooms) collapse into one entry; the
unconditional one wins, else the first. 117 HD lights in its own units (strong ambient, strong sun), so each
environment's light is stored relative to a baseline: the tint and strength of its ambient and sun relative to
117 HD's OVERWORLD environment, or to its typical cave for underground areas. 1.0 is "Lumina's normal look".
Strength ratios are square-rooted and clamped so no area goes extreme.

117 HD's fog data (fog colours and depths) is not carried over: in Lumina the sky owns the colour and the fog is
the sky's horizon. The only places that override the sky are the hand-listed SKY_OVERRIDES below.

Fields (colours are sRGB 0..1; the Java side converts; absent fields are the defaults):
  ambient      rgb multiplier on the sky's ambient light (underground: on the cave ambient)
  sun          rgb multiplier on the sun and moon light
  underground  no sky: the background and the fog are fogColor, the sun is 0, the ambient is the cave ambient
               times `ambient` (117 HD's isOverworld = the area meets the OVERWORLD area, as Lumina's Environment does)
  fogColor     underground: the area's own fog colour, 117 HD's (black where it has none). Overworld, only in a sky
               override: the colour the sky is hazed toward
  skyTint      sky override: rgb multiplier on the sky's colours
  haze         sky override: how much of the sky is hazed toward fogColor, horizon fully, zenith at 0.55 of it

117 HD is BSD 2-Clause licensed; its notice is reproduced in LICENSE.
"""
import json
import math
import os
import sys

# 117 HD's reference lighting the ratios are taken against
OW_AMBIENT, OW_AMBIENT_STRENGTH = "#97baff", 1.0
OW_SUN_STRENGTH = 4.0
CAVE_AMBIENT, CAVE_AMBIENT_STRENGTH = "#aaafb6", 1.5
CAVE_SUN_STRENGTH = 1.0

# how much of a color's hue difference survives (0 none, 1 all); stylized but not garish
TINT_EXPONENT = 0.5
STRENGTH_EXPONENT = 0.5
# keeps saturated lights (pure orange lava) from removing a channel entirely
TINT_FLOOR = 0.2
AMBIENT_RANGE = (0.45, 1.7)
SUN_RANGE_OVERWORLD = (0.5, 1.4)
SUN_RANGE_CAVE = (0.2, 1.6)

# ---------------------------------------------------------------------------------------------------------
# hand overrides, on top of 117 HD's data; every one is listed in the output's header so it can be pruned.
# An area overrides the sky only where the sky colour itself should differ from the open overworld's. For now that
# is Morytania alone: the old renderer's greenish, darker sky (MORYTANIA_SKY_TINT) hazed toward its swamp fog.
# MORYTANIA_SWAMP was the old renderer's biome name; no 117 HD environment is called that, so the entry is inert
# until something is keyed by it.
MORYTANIA_SKY_TINT = "#c4d6ae"
MORYTANIA_FOG = "#4a5240"
MORYTANIA_HAZE = 0.6
SKY_OVERRIDES = {name: {"skyTint": MORYTANIA_SKY_TINT, "fogColor": MORYTANIA_FOG, "haze": MORYTANIA_HAZE} for name in (
    "MORYTANIA", "BARROWS", "DARKMEYER", "MEIYERDITCH", "VER_SINHAZA", "VER_SINHAZA_TRANSITION", "MORYTANIA_SWAMP")}


# ---------------------------------------------------------------------------------------------------------
# colors

def parse_color(c):
    """117 HD colors are "#rrggbb" or [r, g, b] in 0-255 (sometimes above 255). Returns sRGB floats."""
    if isinstance(c, str):
        c = c.lstrip("#")
        return [int(c[i:i + 2], 16) / 255 for i in (0, 2, 4)]
    return [v / 255 for v in c[:3]]


def to_linear(c):
    return [x / 12.92 if x <= 0.04045 else ((x + 0.055) / 1.055) ** 2.4 for x in c]


def luminance(c):
    return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]


def chroma(c):
    l = luminance(c)
    return [x / l for x in c] if l > 1e-6 else [1.0, 1.0, 1.0]


def relative(color_lin, strength, base_lin, base_strength, value_range):
    """Tint and strength of a light relative to the baseline light, as one RGB multiplier."""
    t = [max((a / b) ** TINT_EXPONENT, TINT_FLOOR) for a, b in zip(chroma(color_lin), chroma(base_lin))]
    lt = luminance(t)
    t = [x / lt for x in t] if lt > 1e-6 else [1.0, 1.0, 1.0]
    ratio = luminance(color_lin) * strength / (luminance(base_lin) * base_strength)
    s = min(max(max(ratio, 0) ** STRENGTH_EXPONENT, value_range[0]), value_range[1])
    return [round(x * s, 4) for x in t]


def convert(env, cave):
    """117 HD environment fields -> Lumina multipliers, following 117 HD's defaults in Environment.java."""
    white = [1.0, 1.0, 1.0]
    sun = to_linear(parse_color(env["directionalColor"])) if "directionalColor" in env else white
    sun_strength = env.get("directionalStrength", 0.25)
    if "ambientColor" in env:
        amb = to_linear(parse_color(env["ambientColor"]))
    elif not cave:
        # 117 HD derives an overworld ambient from the sun color through a thin atmosphere
        depth = [0.0507 + 0.08, 0.1031 + 0.08, 0.2394 + 0.08]
        amb = [s * 0.5 * (math.exp(d) - 1) for s, d in zip(sun, depth)]
    else:
        amb = white
    amb_strength = env.get("ambientStrength", -1)
    if amb_strength < 0:
        amb_strength = sun_strength if not cave else 1.0

    if cave:
        ambient = relative(amb, amb_strength, to_linear(parse_color(CAVE_AMBIENT)), CAVE_AMBIENT_STRENGTH,
                           AMBIENT_RANGE)
        sun_mul = relative(sun, sun_strength, white, CAVE_SUN_STRENGTH, SUN_RANGE_CAVE)
    else:
        ambient = relative(amb, amb_strength, to_linear(parse_color(OW_AMBIENT)), OW_AMBIENT_STRENGTH,
                           AMBIENT_RANGE)
        sun_mul = relative(sun, sun_strength, white, OW_SUN_STRENGTH, SUN_RANGE_OVERWORLD)

    out = {"ambient": ambient, "sun": sun_mul}
    if cave:
        out["underground"] = True
        # underground areas without a fog color fade to black, like 117 HD
        out["fogColor"] = [round(x, 4) for x in parse_color(env.get("fogColor", "#000000"))]
    return out


# ---------------------------------------------------------------------------------------------------------
# areas, as Lumina's scene.areas.Area normalizes them (rawAabbs, regions, regionBoxes, areas; unhideAreas are not
# part of intersects)

MAX_COORD = 16383


def clamp_coord(v):
    return min(max(v, 0), MAX_COORD)


def aabb(b):
    """117 HD AABB array forms -> (minX, minY, minPlane, maxX, maxY, maxPlane); planes -1..4 mean all."""
    n = len(b)
    if n == 2:
        x1, y1, z1, x2, y2, z2 = b[0], b[1], 0, b[0], b[1], 3
    elif n == 3:
        x1, y1, z1, x2, y2, z2 = b[0], b[1], b[2], b[0], b[1], b[2]
    elif n == 4:
        x1, y1, z1, x2, y2, z2 = b[0], b[1], 0, b[2], b[3], 3
    elif n == 5:
        x1, y1, z1, x2, y2, z2 = b[0], b[1], b[4], b[2], b[3], b[4]
    elif n == 6:
        x1, y1, z1, x2, y2, z2 = b
    else:
        raise ValueError("bad aabb %r" % (b,))
    return (clamp_coord(min(x1, x2)), clamp_coord(min(y1, y2)), max(0, min(z1, z2)),
            clamp_coord(max(x1, x2)), clamp_coord(max(y1, y2)), min(3, max(z1, z2)))


def region_aabb(rid):
    x, y = (rid >> 8) << 6, (rid & 0xFF) << 6
    return (x, y, 0, x + 63, y + 63, 3)


def region_box(r):
    frm = r[0]
    to = r[1] if len(r) > 1 else r[0]
    p0 = r[2] if len(r) > 2 else 0
    p1 = r[3] if len(r) > 3 else (r[2] if len(r) > 2 else 3)
    x1, y1, x2, y2 = frm >> 8, frm & 0xFF, to >> 8, to & 0xFF
    return (min(x1, x2) << 6, min(y1, y2) << 6, max(0, p0),
            ((max(x1, x2) + 1) << 6) - 1, ((max(y1, y2) + 1) << 6) - 1, min(3, p1))


class Areas:
    def __init__(self, areas):
        self.by_name = {a["name"]: a for a in areas}
        self.cache = {}

    def boxes(self, name, stack=()):
        if name in self.cache:
            return self.cache[name]
        if name in stack:
            raise ValueError("area cycle at " + name)
        a = self.by_name.get(name)
        if a is None:
            print("warning: unknown area", name, file=sys.stderr)
            return []
        out = []
        for b in a.get("aabbs") or []:
            if isinstance(b, str):
                out += self.boxes(b, stack + (name,))
            else:
                out.append(aabb(b))
        for r in a.get("regions") or []:
            out.append(region_aabb(r))
        for r in a.get("regionBoxes") or []:
            out.append(region_box(r))
        for s in a.get("areas") or []:
            out += self.boxes(s, stack + (name,))
        out = list(dict.fromkeys(out))
        self.cache[name] = out
        return out


def intersects(a, b):
    return a[0] <= b[3] and a[3] >= b[0] and a[1] <= b[4] and a[4] >= b[1] and a[2] <= b[5] and a[5] >= b[2]


# ---------------------------------------------------------------------------------------------------------

def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    scene_dir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        root, "src", "main", "resources", "com", "midonarhel", "lumina", "scene")
    out_path = sys.argv[2] if len(sys.argv) > 2 else os.path.join(
        root, "src", "main", "resources", "com", "midonarhel", "lumina", "sky", "area_looks.json")

    areas = Areas(json.load(open(os.path.join(scene_dir, "areas.json"), encoding="utf-8")))
    environments = json.load(open(os.path.join(scene_dir, "environments.json"), encoding="utf-8"))
    overworld = areas.boxes("OVERWORLD")

    looks = {}
    order = []
    collapsed = {}
    conditional = {}
    for e in environments:
        name = e.get("key") or e.get("area")
        if not name:
            continue
        gated = bool(e.get("varbitCondition") or e.get("varpCondition"))
        if name in looks:
            # an unconditional entry replaces a gated one; otherwise the first stays
            collapsed[name] = collapsed.get(name, 0) + 1
            if gated or not conditional[name]:
                continue
        else:
            order.append(name)
        conditional[name] = gated
        bx = areas.boxes(e["area"]) if "area" in e else []
        # 117 HD's isOverworld: the area meets OVERWORLD. Entries without an area (the seasonal bases) stand in for the
        # overworld and are never underground.
        cave = bool(bx) and not any(intersects(b, o) for b in bx for o in overworld)
        d = {"name": name}
        d.update(convert(e, cave))
        looks[name] = d

    applied = []
    for name, o in SKY_OVERRIDES.items():
        if name not in looks:
            looks[name] = {"name": name, "ambient": [1.0, 1.0, 1.0], "sun": [1.0, 1.0, 1.0]}
            order.append(name)
            note = " (no 117 HD environment of that name: inert)"
        elif looks[name].get("underground"):
            print("warning: sky override for underground area", name, file=sys.stderr)
            continue
        else:
            note = ""
        looks[name]["skyTint"] = [round(x, 4) for x in parse_color(o["skyTint"])]
        looks[name]["fogColor"] = [round(x, 4) for x in parse_color(o["fogColor"])]
        looks[name]["haze"] = o["haze"]
        applied.append("%s: skyTint %s, hazed %.2f toward fog %s%s" % (name, o["skyTint"], o["haze"], o["fogColor"], note))

    header = [
        "// Generated by tools/import_area_looks.py from 117 HD's environments.json and areas.json",
        "// (src/main/resources/com/midonarhel/lumina/scene); the sky overrides are the tool's SKY_OVERRIDES. Hand edits",
        "// are fine for trying things, but move them into the tool afterwards or a regeneration loses them.",
        "// Read by com.midonarhel.lumina.sky.AreaLook. One entry per 117 HD environment, keyed by its key or, without",
        "// one, its area name. Colours are sRGB 0..1; absent fields are the defaults.",
        "//   ambient, sun: rgb multipliers on the sky's light, 1 = the open overworld (the light, not the fog)",
        "//   underground: no sky; background and fog = fogColor (117 HD's, black where it has none), sun 0, cave ambient",
        "//   skyTint, fogColor, haze: a sky override, only where the sky colour itself should differ from the overworld's:",
        "//     the sky times skyTint, then hazed toward fogColor (horizon by haze, zenith by 0.55 haze); the fog is that",
        "//     horizon. 117 HD's fog colours and depths are not used: the fog is always the sky's horizon.",
        "//",
        "// Sky overrides (%d):" % len(applied),
    ]
    header += ["//   " + a for a in applied]
    if collapsed:
        header.append("//")
        header.append("// Duplicate 117 HD entries collapsed into one look (the unconditional entry wins, else the first):")
        header.append("//   " + ", ".join("%s (%d more)" % (n, c) for n, c in collapsed.items()))

    with open(out_path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(header) + "\n[\n")
        for i, name in enumerate(order):
            f.write(json.dumps(looks[name], separators=(",", ":")))
            f.write(",\n" if i < len(order) - 1 else "\n")
        f.write("]\n")

    print("looks: %d (%d underground); sky overrides: %d; collapsed duplicates: %d" % (
        len(order), sum(1 for n in order if looks[n].get("underground")), len(applied), len(collapsed)))


if __name__ == "__main__":
    main()
