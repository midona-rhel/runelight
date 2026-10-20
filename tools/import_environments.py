#!/usr/bin/env python3
"""
Build RuneLight's environments.json: per-area ambience (ambient and sun tint, fog, sky haze) and the
cave/overworld classification, from 117 HD's areas and environments plus 3D Weather's biome regions.

Usage:
  tools/import_environments.py <RLHD>/src/main/resources/rs117/hd/scene \
      <3D-Weather>/src/main/java/com/weather3d/BiomeChunkMap.java \
      src/main/resources/com/midonarhel/runelight/environments.json

Lookup order in game (EnvironmentManager.java): 117 HD environments in their file order (specific before
general), then CAVE_AREAS (looks for underground areas 117 HD leaves out), then the 3D Weather biome of the 64x64 region, then 117 HD's OVERWORLD area (open sky), then a
default by height (y >= 4160 is underground).

117 HD lights in its own units (strong ambient, strong sun, its own fog curve). RuneLight keeps its own
time-of-day palette (sky.glsl), so each environment is stored relative to a baseline: the tint and strength
of its ambient and sun relative to 117 HD's overworld (or to its typical cave for underground areas). 1.0
means "RuneLight's normal look". Strength ratios are square-rooted and clamped so no area goes extreme.

Not imported (yet):
  - environments gated on varbits/varps (quest states, cutscenes); the unconditional entry after them is used
  - player-owned house themes, legacy Theatre of Blood lighting, the seasonal OVERWORLD/AUTUMN/WINTER bases
  - sky presets and objects other than skyVisibility, fixed sun angles, shadow angles, wind, ground fog,
    water colors, underglow, lightning

117 HD and 3D Weather are BSD 2-Clause licensed; their notices are reproduced in LICENSE.
"""
import json
import math
import re
import sys

MAX_COORD = 16383

# 117 HD's reference lighting the ratios are taken against
OW_AMBIENT, OW_AMBIENT_STRENGTH = "#97baff", 1.0
OW_SUN_STRENGTH = 4.0
CAVE_AMBIENT, CAVE_AMBIENT_STRENGTH = "#aaafb6", 1.5
CAVE_SUN_STRENGTH = 1.0
DEFAULT_FOG_DEPTH = 25.0

# how much of a color's hue difference survives (0 none, 1 all); stylized but not garish
TINT_EXPONENT = 0.5
STRENGTH_EXPONENT = 0.5
# keeps saturated lights (pure orange lava) from removing a channel entirely
TINT_FLOOR = 0.2
AMBIENT_RANGE = (0.45, 1.7)
SUN_RANGE_OVERWORLD = (0.5, 1.4)
SUN_RANGE_CAVE = (0.2, 1.6)
# share of the sky replaced by fog where 117 HD hides the sky
HAZE_SCALE = 0.85

SKIPPED_KEYS = {"OVERWORLD", "AUTUMN", "WINTER"}

# Sky multipliers (sRGB) layered over 117 HD's environments, which carry none. A blue sky is right for most of
# the mainland; Morytania's is a little greener and darker. Its clouds and the sky seen in water follow because
# envSky() multiplies the finished sky. Tune here, then regenerate.
MORYTANIA_SKY_TINT = "#c4d6ae"
# 117 HD environment (key or area) -> tint
SKY_TINTS = {name: MORYTANIA_SKY_TINT for name in (
    "MORYTANIA", "BARROWS", "DARKMEYER", "MEIYERDITCH", "VER_SINHAZA", "VER_SINHAZA_TRANSITION")}
# 117 HD areas that make up open-air Morytania; a 3D Weather region mostly covered by them gets the
# MORYTANIA_SWAMP biome, so its weather is Morytania's whatever 3D Weather called it (Barrows is "barrens")
MORYTANIA_AREAS = ("MORYTANIA", "BARROWS", "DARKMEYER", "MEIYERDITCH", "VER_SINHAZA", "BURGH_DE_ROTT",
                   "PORT_PHASMATYS", "CANIFIS", "SLEPE", "MORTTON")
MORYTANIA_REGION_SHARE = 0.3
# y from here down is instances and underground; MORYTANIA nests cutscene and trekking instances up there
SURFACE_MAX_Y = 4160

# Biome looks for regions no 117 HD environment covers, written in 117 HD's units so they go through the
# same conversion. Absent fields mean "baseline". Cave biomes light underground areas.
BIOMES = {
    "GRASSLAND": {},
    "FOREST": {"ambientColor": "#a3c9b0", "directionalColor": "#fff1d6", "directionalStrength": 3.6,
               "fogColor": "#9fb39a", "fogDepth": 10, "skyVisibility": 0.9},
    "DESERT": {"ambientColor": "#c9b597", "ambientStrength": 1.8, "directionalColor": "#ffe0b0",
               "directionalStrength": 4.2, "fogColor": "#d9c49a", "fogDepth": 22, "skyVisibility": 0.65,
               "skyTint": "#fff3df"},
    "SWAMP": {"ambientColor": "#9aa889", "ambientStrength": 0.8, "directionalColor": "#c8c9a0",
              "directionalStrength": 2.4, "fogColor": "#4a5240", "fogDepth": 45, "skyVisibility": 0.45,
              "skyTint": "#c8d0bc"},
    # Morytania's weather (weather_climate.json) never clears; its sky is the sky tint above
    "MORYTANIA_SWAMP": {"ambientColor": "#9aa889", "ambientStrength": 0.8, "directionalColor": "#c8c9a0",
                        "directionalStrength": 2.4, "fogColor": "#4a5240", "fogDepth": 45, "skyVisibility": 0.45,
                        "skyTint": MORYTANIA_SKY_TINT},
    "ARCTIC": {"ambientColor": "#a9cfff", "ambientStrength": 1.4, "directionalColor": "#eef4ff",
               "directionalStrength": 3.2, "fogColor": "#c3d2e8", "fogDepth": 30, "skyVisibility": 0.65,
               "skyTint": "#e6f0ff"},
    "MOUNTAIN": {"ambientColor": "#a7b8d6", "directionalColor": "#fff8ee", "directionalStrength": 3.8,
                 "fogColor": "#b4bfcf", "fogDepth": 18, "skyVisibility": 0.75},
    "WILDERNESS": {"ambientColor": "#b8a4a4", "ambientStrength": 0.75, "directionalColor": "#d9b3a6",
                   "directionalStrength": 2.6, "fogColor": "#3a2222", "fogDepth": 30, "skyVisibility": 0.5,
                   "skyTint": "#e0b8b0"},
    "TROPICAL": {"ambientColor": "#a9d4b8", "ambientStrength": 1.1, "directionalColor": "#ffe9c4",
                 "directionalStrength": 4.2, "fogColor": "#b9d9cf", "fogDepth": 16, "skyVisibility": 0.8},
    "BARRENS": {"ambientColor": "#b5aa9c", "directionalColor": "#f0dcc4", "directionalStrength": 3.4,
                "fogColor": "#9c8f80", "fogDepth": 20, "skyVisibility": 0.7, "skyTint": "#f0e6dc"},
    "CAVE": {"cave": True, "ambientColor": "#9fb0c8", "ambientStrength": 1.3, "directionalColor": "#b8c8e0",
             "directionalStrength": 0.9, "fogColor": "#070a10", "fogDepth": 35},
    "LAVA_CAVE": {"cave": True, "ambientColor": "#d9a080", "ambientStrength": 1.4,
                  "directionalColor": "#ffa060", "directionalStrength": 1.3, "fogColor": "#1c0904",
                  "fogDepth": 30},
    "COSMOS": {"cave": True, "ambientColor": "#b8a8e0", "ambientStrength": 1.5, "directionalColor": "#e0d8ff",
               "fogColor": "#0c0818", "fogDepth": 30},
}
# Looks for underground areas 117 HD gives no environment (they would all fall back to the plain CAVE biome),
# in 117 HD's units like BIOMES. Only the differences from CAVE are written: how warm or cold the stone is,
# how murky the air. All stay dark with black fog, so the void around them is black.
CAVE_LOOKS = {
    "SEWER": {"ambientColor": "#94a88a", "ambientStrength": 1.25, "directionalColor": "#b9c49a",
              "directionalStrength": 0.8, "fogColor": "#0a1006", "fogDepth": 45},
    "CRYPT": {"ambientColor": "#8e9f9c", "ambientStrength": 1.2, "directionalColor": "#aebdb8",
              "directionalStrength": 0.7, "fogColor": "#040708", "fogDepth": 50},
    "MINE": {"ambientColor": "#b4a690", "ambientStrength": 1.35, "directionalColor": "#ffd9a6",
             "directionalStrength": 0.95, "fogColor": "#0e0903", "fogDepth": 28},
    "BASEMENT": {"ambientColor": "#bdb4a6", "ambientStrength": 1.5, "directionalColor": "#ffe2b8",
                 "directionalStrength": 0.8, "fogColor": "#0b0804", "fogDepth": 22},
    "ICE": {"ambientColor": "#9cc2f0", "ambientStrength": 1.2, "directionalColor": "#d4e6ff",
            "directionalStrength": 0.85, "fogColor": "#16222e", "fogDepth": 32},
    "DAMP": {"ambientColor": "#93b0c2", "ambientStrength": 1.25, "directionalColor": "#b4d2de",
             "directionalStrength": 0.8, "fogColor": "#040a0e", "fogDepth": 42},
    "JUNGLE": {"ambientColor": "#9db393", "ambientStrength": 1.25, "directionalColor": "#c9d8a8",
               "directionalStrength": 0.8, "fogColor": "#050b05", "fogDepth": 40},
    "DESERT": {"ambientColor": "#c4ab85", "ambientStrength": 1.3, "directionalColor": "#ffd9a0",
               "directionalStrength": 0.9, "fogColor": "#150e04", "fogDepth": 32},
    "LAVA": BIOMES["LAVA_CAVE"],
}

# 117 HD area names (areas.json) per look, in priority order. Only areas no 117 HD environment covers: those
# environments come first and win. Boxes that touch the overworld are skipped.
CAVE_AREAS = [
    ("LAVA", ["CERBERUS", "Lava Maze Dungeon"]),
    ("SEWER", ["DRAYNOR_SEWERS", "VARROCK_SEWERS", "PLAGUE_CITY_SEWERS"]),
    ("CRYPT", ["MORYTANIA_SLAYER_TOWER_BASEMENT", "PATERDOMUS_MAUSOLEUM", "PATERDOMUS_MAUSOLEUM_BASEMENT",
               "SHADE_CATACOMBS", "Shayzien Crypt", "Tomb of Ivandis (In Aid of Myreque)",
               "VARROCK_JULIETS_FAMILY_CRYPT", "White Knights' Castle crypt", "FORTHOS_DUNGEON",
               "Mort Myre Myreque Hideout", "MORYTANIA_SPIDER_CAVES", "ABANDONED_MINE"]),
    ("BASEMENT", ["HEROES_GUILD_BASEMENT", "WARRIORS_GUILD_BASEMENT", "DRAYNOR_MANOR_BASEMENT",
                  "WIZARD_TOWER_BASEMENT", "WITCHES_HOUSE_BASEMENT", "Blue Moon Inn basement", "Varrock West bank",
                  "Ferox Basement", "Champion's Challenge", "Phoenix Gang Hideout", "HAM_HIDEOUT",
                  "HAM Store Room", "ALDARIN_MANOR_WINECELLER", "Elemental Workshop floor 1"]),
    ("MINE", ["DWARVEN_MINES", "AMETHYST_MINES", "KELDAGRIM_TUNNEL", "KELDAGRIM", "KELDAGRIM_INTERIORS",
              "WEISS_SALT_MINE", "SHILO_VILLAGE_MINE", "DEEPFIN_POINT_MINE_CAVE"]),
    ("ICE", ["GHORROCK_DUNGEON", "Ghorrock Dungeon Cell", "Ghorrock Dungeon Duke Arena",
             "FREMENNIK_ISLES_ICE_TROLL_DUNGEON", "TROLLWEISS_CAVES", "Polar eagle cave",
             "Wilderness Slayer Cave - Snow Area", "Ruins of Tapoyauik Ice", "WEISS_UNDERGROUND"]),
    ("DAMP", ["WATERFALL_DUNGEON", "WATERFALL_DUNGEON_CHALICE_ROOM", "WATERFALL_DUNGEON_CHALICE_ROOM_FLOODED",
              "KRAKEN_COVE", "BRINE_RAT_CAVERN", "WATERBIRTH_DUNGEON", "WATERBIRTH_DUNGEON_LEVEL_1",
              "Glarial's Tomb Dungeon"]),
    ("JUNGLE", ["Ape Atoll Dungeon (Monkey Madness 1)", "Kruk's Dungeon main area (Monkey Madness 2)",
                "Kruk's Dungeon hunter area (Monkey Madness 2)",
                "Kruk's Dungeon maniacal monkey tunnels (Monkey Madness 2)",
                "Temple of Marimbo Dungeon (Monkey Madness 1)", "Ah Za Rhoon 1 (Shilo Village)",
                "Ah Za Rhoon 2 (Shilo Village)", "Tomb of Bervirius (Shilo Village)",
                "Viyeldi caves top floor (Legends' Quest)", "Karamjan Temple (Dragon Slayer 2)"]),
    ("DESERT", ["KALPHITE_SLAYER_CAVE", "Enakhra's Temple", "Beneath Cursed Sands cave", "SCABARAS_DUNGEON_ULLEK",
                "Klenter's Pyramid (A Tail of Two Cats)", "Genie cave", "DESERT_TREASURE_PYRAMID_LEVEL_4",
                "Desert Mining Camp (The Tourist Trap)", "ANCIENT_VAULT", "ANCIENT_VAULT_LOWER",
                "ANCIENT_VAULT_UPPER"]),
]

# 3D Weather lists these regions twice or with a typo; its lookup takes the first biome
REGION_FIXES = {43448: 13448}
REGION_OVERRIDES = {5789: "LAVA_CAVE"}


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
    """117 HD environment fields -> RuneLight multipliers, following 117 HD's defaults in Environment.java."""
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
    # underground areas without a fog color fade to black, like 117 HD; overworld ones keep the sky color
    if "fogColor" in env or cave:
        out["fogColor"] = [round(x, 4) for x in parse_color(env.get("fogColor", "#000000"))]
    out["fogDepth"] = max(0.0, float(env.get("fogDepth", DEFAULT_FOG_DEPTH)))
    sky = env.get("sky")
    vis = env.get("skyVisibility", sky.get("skyVisibility") if isinstance(sky, dict) else None)
    if vis is not None and not cave:
        out["haze"] = round((1 - vis) * HAZE_SCALE, 3)
    if "skyTint" in env:
        out["skyTint"] = [round(x, 4) for x in parse_color(env["skyTint"])]
    if env.get("instantTransition"):
        out["instant"] = True
    return out


# ---------------------------------------------------------------------------------------------------------
# areas

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
        # 117 HD's containsPoint includes these by default
        for b in a.get("unhideAreas") or []:
            out.append(aabb(b))
        out = list(dict.fromkeys(out))
        self.cache[name] = out
        return out


def intersects(a, b):
    return a[0] <= b[3] and a[3] >= b[0] and a[1] <= b[4] and a[4] >= b[1] and a[2] <= b[5] and a[5] >= b[2]


# ---------------------------------------------------------------------------------------------------------
# 3D Weather biomes

def load_biomes(path):
    src = open(path, encoding="utf-8").read()
    regions = {}
    for m in re.finditer(r"(\w+)\(Biome\.\w+,([\d,\s]+)\)", src):
        biome = m.group(1)
        for rid in (int(x) for x in m.group(2).replace(" ", "").replace("\n", "").split(",") if x):
            rid = REGION_FIXES.get(rid, rid)
            regions.setdefault(rid, biome)
    for rid, biome in REGION_OVERRIDES.items():
        regions[rid] = biome
    return regions


def morytania_regions(areas):
    """Surface regions at least MORYTANIA_REGION_SHARE covered by the Morytania areas."""
    covered = set()
    for name in MORYTANIA_AREAS:
        for b in areas.boxes(name):
            if b[2] == 0 and b[4] < SURFACE_MAX_Y:
                covered.update((x, y) for x in range(b[0], b[3] + 1) for y in range(b[1], b[4] + 1))
    count = {}
    for x, y in covered:
        key = (x >> 6) << 8 | (y >> 6)
        count[key] = count.get(key, 0) + 1
    return sorted(r for r, n in count.items() if n >= MORYTANIA_REGION_SHARE * 64 * 64)


# ---------------------------------------------------------------------------------------------------------

def main():
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    scene_dir, biome_path, out_path = sys.argv[1:]
    areas = Areas(json.load(open(scene_dir + "/areas.json", encoding="utf-8")))
    environments = json.load(open(scene_dir + "/environments.json", encoding="utf-8"))
    overworld = areas.boxes("OVERWORLD")

    envs, boxes, dropped = [], [], {}

    def drop(reason, e):
        dropped.setdefault(reason, []).append(e.get("key") or e.get("area") or "?")

    for e in environments:
        if e.get("key") in SKIPPED_KEYS or "area" not in e:
            drop("seasonal base", e)
            continue
        if e.get("varbitCondition") or e.get("varpCondition"):
            drop("varbit/varp condition", e)
            continue
        if e.get("isPohTheme"):
            drop("POH theme", e)
            continue
        if e.get("isLegacyTob"):
            drop("legacy ToB", e)
            continue
        bx = areas.boxes(e["area"])
        if not bx:
            drop("empty area", e)
            continue
        cave = not any(intersects(b, o) for b in bx for o in overworld)
        d = {"name": e.get("key") or e["area"]}
        if cave:
            d["cave"] = True
        d.update(convert(e, cave))
        if d["name"] in SKY_TINTS and not cave:
            d["skyTint"] = [round(x, 4) for x in parse_color(SKY_TINTS[d["name"]])]
        index = len(envs)
        envs.append(d)
        for b in bx:
            boxes.append([index, *b])

    # looks for the underground areas 117 HD leaves to the plain CAVE biome; after its environments, so those win
    hd_count = len(envs)
    for look, names in CAVE_AREAS:
        bx = []
        for n in names:
            bx += areas.boxes(n)
        bx = [b for b in dict.fromkeys(bx) if not any(intersects(b, o) for o in overworld)]
        d = {"name": "CAVE_" + look, "cave": True}
        d.update(convert({"ambientStrength": 1.0, **CAVE_LOOKS[look]}, True))
        index = len(envs)
        envs.append(d)
        for b in bx:
            boxes.append([index, *b])

    # 117 HD's OVERWORLD (mainland and its extensions such as player-owned houses): neutral and sunlit. The
    # game looks at the region's surface biome before this one, so biomes can flavour it.
    overworld_index = len(envs)
    envs.append({"name": "OVERWORLD", "fallback": True, "ambient": [1, 1, 1], "sun": [1, 1, 1], "fogDepth": 0.0})
    for b in overworld:
        boxes.append([overworld_index, *b])

    first_biome = len(envs)
    biome_index = {}
    for name, preset in BIOMES.items():
        cave = bool(preset.get("cave"))
        d = {"name": name, "biome": True}
        if cave:
            d["cave"] = True
        if preset:
            # 117 HD would derive a missing ambient strength from the sun; biomes mean "normal"
            d.update(convert({"ambientStrength": 1.0, **preset}, cave))
        else:
            d.update({"ambient": [1, 1, 1], "sun": [1, 1, 1], "fogDepth": 0.0})
        biome_index[name] = len(envs)
        envs.append(d)

    region_biomes = load_biomes(biome_path)
    for rid in morytania_regions(areas):
        region_biomes[rid] = "MORYTANIA_SWAMP"
    biome_regions = {}
    for rid, biome in sorted(region_biomes.items()):
        biome_regions.setdefault(biome, []).append(rid)

    out = {
        "environments": envs,
        # env index, minX, minY, minPlane, maxX, maxY, maxPlane; earlier entries win
        "boxes": [v for b in boxes for v in b],
        "biomeRegions": {str(biome_index[b]): r for b, r in biome_regions.items()},
    }
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(out, f, separators=(",", ":"))
        f.write("\n")

    spans = 0
    for b in boxes:
        spans += ((b[4] >> 6) - (b[1] >> 6) + 1) * ((b[5] >> 6) - (b[2] >> 6) + 1)
    print("environments: %d from 117 HD (%d underground), %d biomes, overworld fallback with %d boxes" % (
        hd_count, sum(1 for e in envs[:hd_count] if e.get("cave")), len(envs) - first_biome,
        len(overworld)))
    print("boxes: %d, region index entries: %d" % (len(boxes), spans))
    print("biome regions: %d" % len(region_biomes))
    for reason, names in dropped.items():
        print("dropped (%s): %d: %s" % (reason, len(names), ", ".join(names)))


if __name__ == "__main__":
    main()
