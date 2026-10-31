#!/usr/bin/env python3
"""
Import 117 HD's per-model normal and shading flags as Lumina's model_shading.json ("shadingGroups"), next to
materials.json (its own file, so that import_117hd_materials.py rewriting materials.json leaves it alone).

117 HD's model overrides (model_overrides.json) carry three flags that change a model's normals or its shading
(docs/117hd-model-shading.md §1.6):
  flatNormals              every face lit by its own normal, as if the game marked it flat      -> flag 1
  upwardsNormals           the lighting normal points straight up (grass, ground clutter)        -> flag 2
  undoVanillaShading=false the game's baked shading is kept, not undone                          -> flag 4
An override applies to whole models (objectIds, npcIds), and its colorOverrides to the untextured faces whose colour
meets their "colors" condition, the first that matches deciding, with the sub-override's own flags (they inherit
nothing from the parent). Textured faces take the parent's flags. Lumina's groups have the same form as its emissive
and fake water groups: {objects, npcs, untextured, textured, rules: [{level, when}]}, a level here being the flags.

Which override applies to an id, as 117 HD's ModelOverrideManager.addEntry: the last override without areas that
lists it. Overrides limited to areas, and seasonal ones, are skipped (Lumina has no area or season test for these);
the importer lists them. Projectile and graphics object overrides are skipped too (four carry flags). The other
flags of the same overrides (doubleSidedFaces, inheritTileColorType, ...) are not imported; see the doc.

Usage (from a 117HD/RLHD checkout):
  tools/import_117hd_shading.py <RLHD>/src/main/resources/rs117/hd/scene \
      src/main/resources/com/midonarhel/lumina/scene/model_shading.json

117 HD is BSD 2-Clause licensed; its notice is reproduced in LICENSE.
"""
import importlib.util
import json
import os
import sys

FLAT_NORMALS = 1
UPWARDS_NORMALS = 2
KEEP_SHADING = 4

# the condition parser and gameval lookups are import_117hd_materials.py's
_spec = importlib.util.spec_from_file_location("import_117hd_materials",
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "import_117hd_materials.py"))
materials = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(materials)


def flags(o):
    f = 0
    if o.get("flatNormals"):
        f |= FLAT_NORMALS
    if o.get("upwardsNormals"):
        f |= UPWARDS_NORMALS
    if o.get("undoVanillaShading") is False:
        f |= KEEP_SHADING
    return f


def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    scene, out = sys.argv[1], sys.argv[2]
    overrides = materials.load_json(os.path.join(scene, "model_overrides.json"))
    gamevals = materials.load_json(os.path.join(scene, "gamevals.json"))
    warnings, skipped = [], []

    # per id, the override that applies: (flags, rules) or None, the last unrestricted one winning
    applies = {"objects": {}, "npcs": {}}
    for o in overrides:
        desc = o.get("description", "")
        restricted = bool(o.get("areas")) or o.get("seasonalTheme") is not None
        base = flags(o)
        rules = []
        for c in o.get("colorOverrides") or []:
            try:
                rules.append([flags(c), materials.conditions(c.get("colors"))])
            except ValueError as ex:
                warnings.append(f"{ex} in {desc}")
        # the first matching rule decides a face, so those after the last one that differs from the default are moot
        while rules and rules[-1][0] == base:
            rules.pop()
        if restricted:
            if base or rules:
                skipped.append(desc)
            continue
        if base and (o.get("projectileIds") or o.get("graphicsObjectIds")):
            skipped.append(desc + " (projectiles/graphics)")
        for kind, key in (("objects", "objectIds"), ("npcs", "npcIds")):
            for i in materials.resolve(o.get(key), gamevals[kind], desc, warnings):
                applies[kind][i] = (base, json.dumps(rules)) if base or rules else None

    groups = {}  # (flags, rules) -> [objects, npcs]
    for kind, index in (("objects", 0), ("npcs", 1)):
        for i, a in applies[kind].items():
            if a is not None:
                groups.setdefault(a, [set(), set()])[index].add(i)

    data = {"shadingGroups": [

        {"objects": sorted(objects), "npcs": sorted(npcs), "untextured": base, "textured": base,
            "rules": [{"level": level, "when": when} for level, when in json.loads(rules)]}
        for (base, rules), (objects, npcs) in sorted(groups.items(), key=lambda g: (g[0][0], g[0][1]))]}
    with open(out, "w", encoding="utf-8") as f:
        json.dump(data, f, separators=(",", ":"))
        f.write("\n")

    count = lambda flag: sum(1 for g in data["shadingGroups"]
        if g["untextured"] & flag or any(r["level"] & flag for r in g["rules"]))
    print(f"{len(data['shadingGroups'])} shading groups: {count(FLAT_NORMALS)} with flat normals, "
        f"{count(UPWARDS_NORMALS)} upwards, {count(KEEP_SHADING)} keeping vanilla shading; "
        f"{sum(len(g['objects']) for g in data['shadingGroups'])} objects, "
        f"{sum(len(g['npcs']) for g in data['shadingGroups'])} NPCs")
    for s in skipped:
        print("skipped (area, season or projectile):", s)
    for w in warnings:
        print("warning:", w)


if __name__ == "__main__":
    main()
