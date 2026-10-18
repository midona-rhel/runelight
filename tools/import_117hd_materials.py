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
  - NPCs with lights whose light description says they are spirits, ghosts, souls, wisps and the like
    (their bodies are mostly transparent, and 117 HD makes them glow with that light)
  - projectiles and spot animations that carry a light (spells, fire, magic effects)
  - objects whose light says they burn (fires, flames, lava); only their hot-colored faces glow

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
FIRE_LIGHT = re.compile(r"\bfires?\b|flames?|\blava\b|magma|campfire|bonfire", re.I)
NOT_FIRE = re.compile(r"altar|lantern|cannon|statue|ship|orb", re.I)

# emissive levels
FULL = 3


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
        return bool(name) and prop(name, "unlit") is True

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
    for o in overrides:
        desc = o.get("description", "")
        objects = resolve(o.get("objectIds"), gamevals["objects"], desc, warnings)
        npcs = resolve(o.get("npcIds"), gamevals["npcs"], desc, warnings)
        wind = o.get("windDisplacementMode")
        swaying = wind is not None and wind != "DISABLED"
        if objects and (swaying or FOLIAGE_WORDS.search(desc)) and not NOT_FOLIAGE.search(desc):
            foliage_objects.update(objects)
        if unlit(o.get("baseMaterial")):
            for i in objects:
                emissive_objects[i] = FULL
            for i in npcs:
                emissive_npcs[i] = FULL

    for name, i in gamevals["objects"].items():
        if TREE_NAME.search(name) and not NOT_TREE_NAME.search(name):
            foliage_objects.add(i)

    # lights: glowing NPCs, burning objects and spot animations
    emissive_spotanims, fire_objects = {}, {}
    for d in lights:
        desc = d.get("description", "")
        if d.get("objectIds") and FIRE_LIGHT.search(desc) and not NOT_FIRE.search(desc):
            for i in resolve(d["objectIds"], gamevals["objects"], desc, warnings):
                if i not in emissive_objects:
                    fire_objects[i] = FULL
        if d.get("npcIds") and SPIRIT_LIGHT.search(desc):
            for i in resolve(d["npcIds"], gamevals["npcs"], desc, warnings):
                emissive_npcs[i] = FULL
        for field in ("projectileIds", "graphicsObjectIds"):
            for i in resolve(d.get(field), gamevals["spotanims"], desc, warnings):
                emissive_spotanims[i] = FULL

    def pairs(m):
        return [[k, v] for k, v in sorted(m.items())]

    out = {
        "transmissiveTextures": sorted(leaf_textures),
        "transmissiveObjects": sorted(foliage_objects),
        "emissiveTextures": pairs(emissive_textures),
        "emissiveObjects": pairs(emissive_objects),
        "fireObjects": pairs(fire_objects),
        "emissiveNpcs": pairs(emissive_npcs),
        "emissiveSpotanims": pairs(emissive_spotanims),
    }
    with open(sys.argv[2], "w", encoding="utf-8") as f:
        json.dump(out, f, separators=(",", ":"))
        f.write("\n")
    for w in sorted(set(warnings)):
        print("warning:", w, file=sys.stderr)
    print("wrote " + ", ".join(f"{len(v)} {k}" for k, v in out.items()))


if __name__ == "__main__":
    main()
