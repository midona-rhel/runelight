#!/usr/bin/env python3
"""
Derive RuneLight's wind sway table (wind.json) from 117 HD's model overrides.

117 HD marks the objects that sway in the wind with a windDisplacementMode, a windDisplacementModifier (-3 to 3,
a strength multiplier of 0.25 to 2) and invertDisplacementStrength (hanging things: the bottom moves, not the
top). The modes keep 117 HD's ordinals:
  1 OBJECT                         the whole object bends with the wind (grass, flowers, bushes)
  2 OBJECT_NO_GROUND_DISPLACEMENT  the same (117 HD only differs in letting players push grass aside)
  3 VERTEX                         bends, and each part flutters on its own (shrubs, cacti, palms)
  4 VERTEX_WITH_HEMISPHERE_BLEND   trees: the canopy bends, the trunk stays put
  5 VERTEX_JIGGLE                  ivy: leaves shake in place along the wall

Overrides limited to areas are only used for objects without a general override. Objects whose description
says they are not plants (statues, drains, fences, windows) or that are stumps are left out, and grown
oak/willow/maple/yew/magic/teak/mahogany trees without an override of their own are added as trees.

Output: {"objects": [[id, code], ...]} with code = mode | (modifier + 3) << 3 | invert << 6.

Usage (from a 117HD/RLHD checkout):
  tools/import_117hd_wind.py <RLHD>/src/main/resources/rs117/hd/scene \
      src/main/resources/com/midonarhel/runelight/wind.json

117 HD is BSD 2-Clause licensed; its notice is reproduced in LICENSE.
"""
import json
import os
import re
import sys

MODES = {
    "OBJECT": 1,
    "OBJECT_NO_GROUND_DISPLACEMENT": 2,
    "VERTEX": 3,
    "VERTEX_WITH_HEMISPHERE_BLEND": 4,
    "VERTEX_JIGGLE": 5,
}
TREE = MODES["VERTEX_WITH_HEMISPHERE_BLEND"]

NOT_PLANT = re.compile(r"statue|drain|burrow|floating island|fence|support|window|stump", re.I)
STUMP_NAME = re.compile(r"STUMP")

# same as tools/import_117hd_materials.py
TREE_NAME = re.compile(r"(^|_)(OAK|WILLOW|MAPLE|YEW|MAGIC|TEAK|MAHOGANY)_?TREE")
NOT_TREE_NAME = re.compile(r"STUMP|DEAD|SEED|SAPLING|PATCH|LOG|PLANK|BARK|ROOT|SHORTCUT|DOOR|PORTAL|TELE")


def load_json(path):
    text = open(path, encoding="utf-8").read()
    # gamevals.json starts with a comment line
    return json.loads(text[text.index("[") if text.lstrip().startswith("[") else text.index("{"):])


def code(mode, modifier, invert):
    return mode | (max(-3, min(3, modifier)) + 3) << 3 | (1 << 6 if invert else 0)


def build(overrides, object_ids, warnings):
    """id -> code from 117 HD's model overrides and its object name table."""
    names = {i: n for n, i in object_ids.items()}
    general, local = {}, {}
    for o in overrides:
        mode = o.get("windDisplacementMode")
        if mode is None:
            continue
        desc = o.get("description", "")
        target = local if o.get("areas") else general
        value = code(MODES.get(mode, 0), o.get("windDisplacementModifier", 0), o.get("invertDisplacementStrength", False))
        if NOT_PLANT.search(desc):
            value = 0
        for n in o.get("objectIds") or []:
            i = n if isinstance(n, int) else object_ids.get(n)
            if i is None:
                warnings.append(f"unresolved {n!r} in {desc}")
                continue
            target.setdefault(i, value)

    table = dict(local)
    table.update(general)
    for name, i in object_ids.items():
        if i not in table and TREE_NAME.search(name) and not NOT_TREE_NAME.search(name):
            table[i] = code(TREE, 0, False)
    return {i: c for i, c in table.items() if c & 7 and not STUMP_NAME.search(names.get(i, ""))}


def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    scene = sys.argv[1]
    overrides = load_json(os.path.join(scene, "model_overrides.json"))
    gamevals = load_json(os.path.join(scene, "gamevals.json"))
    warnings = []
    table = build(overrides, gamevals["objects"], warnings)
    with open(sys.argv[2], "w", encoding="utf-8") as f:
        json.dump({"objects": [[i, c] for i, c in sorted(table.items())]}, f, separators=(",", ":"))
        f.write("\n")
    for w in sorted(set(warnings)):
        print("warning:", w, file=sys.stderr)
    counts = {}
    for c in table.values():
        counts[c & 7] = counts.get(c & 7, 0) + 1
    print(f"wrote {len(table)} objects; by mode " + ", ".join(f"{m}: {n}" for m, n in sorted(counts.items())))


if __name__ == "__main__":
    main()
