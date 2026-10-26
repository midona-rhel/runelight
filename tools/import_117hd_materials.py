#!/usr/bin/env python3
"""
Derive RuneLight's surface materials (materials.json) from 117 HD's scene data.

RuneLight has two material behaviours:
  transmissive  thin foliage that is also lit from behind (leaves, bushes, plants, grass)
  emissive      surfaces that ignore lighting and show their own color, with a level 1-3 (3 = fully self-lit)

117 HD has no explicit foliage flag (its "subsurface" is only set on glass and sail cloth), so foliage is
taken from what it does have:
  - vanilla textures whose 117 HD material is a leaf texture (LEAVES_*, LEAFY_*, *_LEAF, willow, maple)
  - objects with a wind displacement mode (117 HD sways exactly the trees, bushes, plants, flowers and grass)
    or whose override description names foliage, minus non-plants that happen to sway (banners, drains, ...),
    plus grown oak/willow/maple/yew/magic/teak/mahogany trees by gameval name
  Foliage objects are whole trees; RuneLight keeps their brown and grey faces (bark) opaque by color.
Emissive comes from:
  - vanilla textures whose material is "unlit" (lava, fire and infernal capes, magic stars)
  - objects and NPCs whose whole-model override uses an unlit material (portals, pyrefiends, ...)
  - objects and NPCs whose override makes only some faces unlit, by face color (a hot-colored furnace, the flames of
    a door, a spotlight's translucent beam) or only its textured faces: kept as emissive groups, each with the
    override's colorOverrides conditions as "h==5", "a<255" terms (clauses of terms are ANDed, clauses ORed), tried in
    order like 117 HD does
  - NPCs with lights whose light description says they are spirits, ghosts, souls, wisps and the like
    (their bodies are mostly transparent, and 117 HD makes them glow with that light)
  - projectiles and spot animations that carry a light (spells, fire, magic effects)
  - objects whose light says they burn (fires, flames, lava) or cook and smelt (ranges, ovens, stoves, furnaces):
    only their hot-colored faces glow (117 HD has no emissive data for these; its glow there is its lights alone)

Fake water is the third behaviour: models whose faces only pretend to be water (fountains, wells, troughs, barrels,
sinks, pools, waterfalls). RuneLight draws those faces as real water (water.glsl waterFake). They come from the
override groups whose material is one of 117 HD's flat water materials, in the same group form as the emissive ones
(level 1: fake water), minus the ones that are not water (blood, portals, slime floors, pipes, vents), and from the
vanilla water textures, which SurfaceMaterials treats as fake wherever a model carries them.

Usage (from a 117HD/RLHD checkout):
  tools/import_117hd_materials.py <RLHD>/src/main/resources/rs117/hd/scene \
      src/main/resources/com/midonarhel/runelight/materials.json

117 HD is BSD 2-Clause licensed; its notice is reproduced in LICENSE.
"""
import json
import os
import re
import sys

LEAF_TEXTURE = re.compile(r"LEAF|LEAVES|LEAFY")
FOLIAGE_WORDS = re.compile(r"\b(trees?|leaf|leaves|leafy|bush(es)?|plants?|hedges?|ferns?|vines?|ivy|grass|flowers?|"
    r"shrubs?|palms?|kelp|wheat|bullrushes|flax|cactus|sapling|lilypad|thistle|sunflowers|tulips|daisies|"
    r"bromelia|foliage)\b", re.I)
NOT_FOLIAGE = re.compile(r"stump|statue|drain|banner|rope|fence|dirt|burrow|\blogs?\b|\bwalls?\b|window|door|chest|"
    r"\btable|crate|\bsign\b|portal|planter|coral|\brocks?\b|rockery|\bstones?\b|floating island|balloon|totem|"
    r"bark - |\bbridge|\bbed\b|\bcart\b|shelves|railing|trellis|walkway|ladder|doorway|pillar|burnt|dead", re.I)

# grown trees that 117 HD leaves without an override of their own (Prifddinas, Deadman, ...)
TREE_NAME = re.compile(r"(^|_)(OAK|WILLOW|MAPLE|YEW|MAGIC|TEAK|MAHOGANY)_?TREE")
NOT_TREE_NAME = re.compile(r"STUMP|DEAD|SEED|SAPLING|PATCH|LOG|PLANK|BARK|ROOT|SHORTCUT|DOOR|PORTAL|TELE")

# light descriptions of NPCs that are spirits or made of light: fully self-lit
SPIRIT_LIGHT = re.compile(r"ghost|spirit|soul|spectre|wisp|phantom|wight|lassar|totem|light_creature|\bmoth\b|"
    r"seren|fairy|phoenix|rift_guardian|antelope|elemental|wraith|\bshades?\b", re.I)
# objects whose light says they burn: only their hot-colored faces glow (flames, not the logs under them)
FIRE_LIGHT = re.compile(r"\bfires?\b|flames?|\blava\b|magma|campfire|bonfire|"
    r"(?<![a-z])(range|ovens?|stoves?|furnace)(?![a-z])", re.I)
NOT_FIRE = re.compile(r"altar|lantern|cannon|statue|ship|orb", re.I)

# 117 HD's materials for a flat sheet of water, which RuneLight draws as real water
FAKE_WATER_MATERIALS = {"WATER_FLAT", "WATER_FLAT_2", "WATER_FLAT_FORCE", "WATER_FLAT_FLOW", "WATER_FLAT_FLOW_2",
    "WATER_FOUNTAIN_FLAT", "WATER_FOUNTAIN_FLAT_LIGHT", "SWAMP_WATER_FLAT", "SWAMP_WATER_FLAT_FLOW"}
# ...of which these overrides are not water (117 HD borrows the materials for blood, slime, portals and the like), or
# shows them where a pool of real water cannot (a floor that is the whole room, vents and pipes of machinery)
NOT_WATER = re.compile(r"blood|maggot|portal|teleporter|pipe|vent\b|vents\b|hydra|bloodwood|drain|bucket|"
    r"waterwheel|whetstone|doghouse|cooking pot|wind catcher|demonic door|drakan|vampyr|sangvesti|darkmeyer|"
    r"planter|altar|chalice|barrel with liquid|decorative barrel|water tap|farming guild - greenhouse", re.I)

# Water 117 HD has no override for, found by scanning the cache's object models for water-hued faces (hue 32-44) on
# fountains, wells, sinks and falls: (objects, untextured default, textured, rules as [level, clauses])
EXTRA_FAKE_WATER = [
    ([878, 884, 6097, 6249, 6549, 8747, 12897], 0, 0, [[1, [["h==42"]]]]),  # wells
    ([2654, 3641], 0, 0, [[1, [["h==32", "a<255"]]]]),  # the Sinclair mansion fountains' water
    ([6750, 12089], 0, 0, [[1, [["h==37"]]]]),  # large and fairy fountains
    ([16302], 0, 1, [[1, [["h==37"]]]]),  # waterfall: its sheet is the droplets texture, its sides translucent water
    ([16704], 0, 0, [[1, [["h==32"]]]]),  # sink
]

# emissive levels
FULL = 3

# the face color variables of 117 HD's "colors" conditions; alpha is 255 minus the face's transparency
COLOR_VARIABLES = ("h", "s", "l", "a", "hsl")
TOKEN = re.compile(r"\s*(\d+|[a-z]+|==|!=|<=|>=|&&|\|\||[<>()!])")
COMPARISONS = ("==", "!=", "<=", ">=", "<", ">")
NEGATED = {"==": "!=", "!=": "==", "<": ">=", ">=": "<", ">": "<=", "<=": ">"}


def tokenize(text):
    tokens, pos = [], 0
    while pos < len(text.rstrip()):
        m = TOKEN.match(text, pos)
        if not m:
            raise ValueError(f"unsupported condition {text!r}")
        tokens.append(m.group(1))
        pos = m.end()
    return tokens


def parse_or(tokens, negate=False):
    """DNF of an expression: a list of clauses, each a list of "h==5" style terms that must all hold."""
    clauses = parse_and(tokens, negate)
    while tokens and tokens[0] == "||":
        tokens.pop(0)
        rest = parse_and(tokens, negate)
        clauses = clauses + rest if not negate else [a + b for a in clauses for b in rest]
    return clauses


def parse_and(tokens, negate):
    clauses = parse_not(tokens, negate)
    while tokens and tokens[0] == "&&":
        tokens.pop(0)
        rest = parse_not(tokens, negate)
        clauses = [a + b for a in clauses for b in rest] if not negate else clauses + rest
    return clauses


def parse_not(tokens, negate):
    t = tokens.pop(0)
    if t == "!":
        return parse_not(tokens, not negate)
    if t == "(":
        clauses = parse_or(tokens, negate)
        if not tokens or tokens.pop(0) != ")":
            raise ValueError("missing )")
        return clauses
    if t not in COLOR_VARIABLES or not tokens or tokens[0] not in COMPARISONS:
        raise ValueError(f"unsupported term at {t!r}")
    op = tokens.pop(0)
    value = tokens.pop(0) if tokens else ""
    if not value.isdigit():
        raise ValueError(f"unsupported term at {t!r}")
    return [[f"{t}{NEGATED[op] if negate else op}{value}"]]


def conditions(colors):
    """A colorOverride's "colors" as DNF: one expression string, an exact hsl number, a boolean, or a list of
    these (any of them)."""
    clauses = []
    for e in colors if isinstance(colors, list) else [colors]:
        if e is True:
            clauses.append([])
        elif isinstance(e, int) and e is not False:
            clauses.append([f"hsl=={e}"])
        elif isinstance(e, str):
            tokens = tokenize(e)
            clauses += parse_or(tokens)
            if tokens:
                raise ValueError(f"unsupported condition {e!r}")
    return clauses


def load_json(path):
    text = open(path, encoding="utf-8").read()
    # gamevals.json starts with a comment line
    return json.loads(text[text.index("[") if text.lstrip().startswith("[") else text.index("{"):])


def resolve(names, table, where, warnings):
    ids = []
    for n in names or []:
        if isinstance(n, int):
            ids.append(n)
        elif n in table:
            ids.append(table[n])
        else:
            warnings.append(f"unresolved {n!r} in {where}")
    return ids


def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    scene = sys.argv[1]
    materials = load_json(os.path.join(scene, "materials.json"))
    overrides = load_json(os.path.join(scene, "model_overrides.json"))
    lights = load_json(os.path.join(scene, "lights.json"))
    gamevals = load_json(os.path.join(scene, "gamevals.json"))
    warnings = []

    by_name = {m["name"]: m for m in materials}

    def prop(name, key, depth=0):
        m = by_name.get(name)
        if m is None or depth > 16:
            return None
        if key in m:
            return m[key]
        parent = m.get("parent")
        return prop(parent, key, depth + 1) if parent else None

    def unlit(name):
        # UNLIT is built into 117 HD, not in materials.json
        return name == "UNLIT" or bool(name) and prop(name, "unlit") is True

    # textures
    leaf_textures, emissive_textures = set(), {}
    for m in materials:
        idx = m.get("vanillaTextureIndex")
        if idx is None:
            continue
        if LEAF_TEXTURE.search(m["name"]):
            leaf_textures.add(idx)
        if unlit(m["name"]):
            emissive_textures[idx] = FULL

    # objects and NPCs from model overrides
    foliage_objects, emissive_objects, emissive_npcs = set(), {}, {}
    groups = {}  # (untextured level, textured level, rules) -> [objects, npcs]
    for o in overrides:
        desc = o.get("description", "")
        objects = resolve(o.get("objectIds"), gamevals["objects"], desc, warnings)
        npcs = resolve(o.get("npcIds"), gamevals["npcs"], desc, warnings)
        wind = o.get("windDisplacementMode")
        swaying = wind is not None and wind != "DISABLED"
        if objects and (swaying or FOLIAGE_WORDS.search(desc)) and not NOT_FOLIAGE.search(desc):
            foliage_objects.update(objects)
        base = unlit(o.get("baseMaterial"))
        # a face with a texture takes the override's texture material, else its vanilla texture's; the unlit whole
        # models RuneLight had before also kept their textured faces lit by their base
        textured = unlit(o.get("textureMaterial")) or base and not o.get("textureMaterial")
        rules = []
        for c in o.get("colorOverrides") or []:
            try:
                when = conditions(c.get("colors"))
            except ValueError as ex:
                warnings.append(f"{ex} in {desc}")
                continue
            rules.append([FULL if unlit(c.get("baseMaterial")) else 0, when])
        # the first matching rule decides a face, so those after the last one that differs from the default are moot
        while rules and rules[-1][0] == (FULL if base else 0):
            rules.pop()
        if rules or base != textured:
            group = groups.setdefault((FULL if base else 0, FULL if textured else 0, json.dumps(rules)), [set(), set()])
            group[0].update(objects)
            group[1].update(npcs)
        elif base:
            for i in objects:
                emissive_objects[i] = FULL
            for i in npcs:
                emissive_npcs[i] = FULL

    # fake water: the override groups with a flat water material, first matching rule decides as for emissive ones
    fake = {}
    for o in overrides:
        desc = o.get("description", "")
        objects = resolve(o.get("objectIds"), gamevals["objects"], desc, warnings)
        if not objects or NOT_WATER.search(desc):
            continue
        water = lambda name: name in FAKE_WATER_MATERIALS
        base = water(o.get("baseMaterial"))
        textured = water(o.get("textureMaterial"))
        rules = []
        for c in o.get("colorOverrides") or []:
            try:
                when = conditions(c.get("colors"))
            except ValueError as ex:
                warnings.append(f"{ex} in {desc}")
                continue
            rules.append([1 if water(c.get("baseMaterial")) else 0, when])
        while rules and rules[-1][0] == (1 if base else 0):
            rules.pop()
        if (base or textured or rules) and (base or textured or any(r[0] for r in rules)):
            fake.setdefault((1 if base else 0, 1 if textured else 0, json.dumps(rules)), set()).update(objects)

    for objects, base, textured, rules in EXTRA_FAKE_WATER:
        fake.setdefault((base, textured, json.dumps(rules)), set()).update(objects)

    for name, i in gamevals["objects"].items():
        if TREE_NAME.search(name) and not NOT_TREE_NAME.search(name):
            foliage_objects.add(i)

    # lights: glowing NPCs, burning objects and spot animations
    emissive_spotanims, fire_objects = {}, {}
    grouped = {i for g in groups.values() for i in g[0]}
    grouped_npcs = {i for g in groups.values() for i in g[1]}
    for d in lights:
        desc = d.get("description", "")
        if d.get("objectIds") and FIRE_LIGHT.search(desc) and not NOT_FIRE.search(desc):
            for i in resolve(d["objectIds"], gamevals["objects"], desc, warnings):
                if i not in emissive_objects and i not in grouped:
                    fire_objects[i] = FULL
        if d.get("npcIds") and SPIRIT_LIGHT.search(desc):
            for i in resolve(d["npcIds"], gamevals["npcs"], desc, warnings):
                if i not in grouped_npcs:
                    emissive_npcs[i] = FULL
        for field in ("projectileIds", "graphicsObjectIds"):
            for i in resolve(d.get(field), gamevals["spotanims"], desc, warnings):
                emissive_spotanims[i] = FULL

    for i in grouped:
        emissive_objects.pop(i, None)
    for i in grouped_npcs:
        emissive_npcs.pop(i, None)

    def pairs(m):
        return [[k, v] for k, v in sorted(m.items())]

    out = {
        "transmissiveTextures": sorted(leaf_textures),
        "transmissiveObjects": sorted(foliage_objects),
        "emissiveTextures": pairs(emissive_textures),
        "emissiveObjects": pairs(emissive_objects),
        "fireObjects": pairs(fire_objects),
        "emissiveNpcs": pairs(emissive_npcs),
        "emissiveGroups": [
            {"objects": sorted(g[0]), "npcs": sorted(g[1]), "untextured": k[0], "textured": k[1],
             "rules": [{"level": r[0], "when": r[1]} for r in json.loads(k[2])]}
            for k, g in sorted(groups.items(), key=lambda kv: min(kv[1][0] | kv[1][1]))],
        "emissiveSpotanims": pairs(emissive_spotanims),
        "fakeWater": [
            {"objects": sorted(ids), "untextured": k[0], "textured": k[1],
             "rules": [{"level": r[0], "when": r[1]} for r in json.loads(k[2])]}
            for k, ids in sorted(fake.items(), key=lambda kv: min(kv[1]))],
    }
    with open(sys.argv[2], "w", encoding="utf-8") as f:
        json.dump(out, f, separators=(",", ":"))
        f.write("\n")
    for w in sorted(set(warnings)):
        print("warning:", w, file=sys.stderr)
    print("wrote " + ", ".join(f"{len(v)} {k}" for k, v in out.items()))


if __name__ == "__main__":
    main()
