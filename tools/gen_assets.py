#!/usr/bin/env python3
"""Generate all templated assets/data for the wool + concrete stairs/slabs backport.

One DRY source for 2 materials x 16 colors x {stairs, slab}. Re-run after editing templates:
    python3 tools/gen_assets.py

`base` below is the id stem, e.g. "white_wool" or "white_concrete".

Emits into src/main/resources:
  - assets/woolbackport/models/block/<base>_{stairs,stairs_inner,stairs_outer,slab,slab_top}.json
  - assets/minecraft/items/<base>_{stairs,stairs_inner,stairs_outer,slab,slab_top}.json
    (item model definitions under the *real* item ids — see the note at the emit site)
  - assets/woolbackport/lang/en_us.json (resource-pack names for pack-having clients)
  - data/woolbackport/lang/en_us.json   (Server Translations API names for pack-less clients)
  - data/minecraft/recipe/<base>_{stairs,slab}.json
  - data/minecraft/loot_table/blocks/<base>_{stairs,slab}.json
  - data/minecraft/tags/block/<per-material tag>.json  (see MATERIALS)

No PNG textures: every model reuses the existing vanilla minecraft:block/<base> texture.
"""
import json, pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
RES = ROOT / "src/main/resources"

# id -> display name
COLORS = {
    "white": "White", "orange": "Orange", "magenta": "Magenta",
    "light_blue": "Light Blue", "yellow": "Yellow", "lime": "Lime",
    "pink": "Pink", "gray": "Gray", "light_gray": "Light Gray",
    "cyan": "Cyan", "purple": "Purple", "blue": "Blue",
    "brown": "Brown", "green": "Green", "red": "Red", "black": "Black",
}

# material id -> (display name, block tag it joins under data/minecraft/tags/block/)
#   wool     -> shears mine it fast, like the wool block itself. 26.1.2 has no dedicated
#               shears-speed tag (the one 26.2 uses does not exist here): ShearsItem's tool rule
#               is overrideSpeed(BlockTags.WOOL, 5.0f), so membership in #minecraft:wool *is* the
#               fast-shear hook. That tag also feeds #minecraft:dampens_vibrations and
#               #minecraft:occludes_vibration_signals, both correct for wool stairs/slabs, and is
#               referenced by nothing else vanilla (no recipes, no sheep behaviour).
#   concrete -> REQUIRED, not cosmetic: ofFullCopy(concrete) carries requiresCorrectToolForDrops,
#               and the correct-tool check resolves through #minecraft:mineable/pickaxe. Without
#               this tag the blocks mine at hand speed and drop nothing. Concrete is "wooden
#               pickaxe or better", so no needs_stone_tool/needs_iron_tool entry.
MATERIALS = {
    "wool": ("Wool", "wool"),
    "concrete": ("Concrete", "mineable/pickaxe"),
}

# block model parent per variant suffix
STAIR_VARIANTS = {
    "_stairs": "minecraft:block/stairs",
    "_stairs_inner": "minecraft:block/inner_stairs",
    "_stairs_outer": "minecraft:block/outer_stairs",
}
SLAB_VARIANTS = {
    "_slab": "minecraft:block/slab",
    "_slab_top": "minecraft:block/slab_top",
}
ALL_VARIANTS = {**STAIR_VARIANTS, **SLAB_VARIANTS}

# The display-entity variants are drawn on top of a shape-identical donor block (the waxed cut
# copper stair, the petrified oak slab), so their cuboids are grown by EXPANSION on every side.
# 16 model units = 1 block, so 0.08 = 5 mm of clearance; a 24-bit depth buffer resolves ~1 mm at 30
# blocks and ~6 mm at 70, so less than this z-fights at range. It has to be done to the GEOMETRY:
# scaling the display entity cannot work for these shapes, because a uniform scale about the model
# centre leaves the plane through that centre exactly where it was, and the stair tread and the top
# slab's underside both sit on y=8, i.e. on that plane. (The polymer patch bundle, whose placement
# this matches, expands its model elements the same way.)
# _slab is deliberately absent: it is not a display, it is the model the real sculk-sensor donor
# renders as an actual block, so its cuboids must stay exactly 1x1x1.
EXPANDED = {
    "_stairs": "stairs",
    "_stairs_inner": "inner_stairs",
    "_stairs_outer": "outer_stairs",
    "_slab_top": "slab_top",
}
EXPANSION = 0.08
VANILLA_MODELS = pathlib.Path(__file__).resolve().parent / "vanilla_models"


def expanded_elements(name):
    """Vanilla block model `name`, every cuboid grown EXPANSION units on all six sides."""
    src = json.loads((VANILLA_MODELS / f"{name}.json").read_text())
    return [
        {
            "from": [round(v - EXPANSION, 4) for v in element["from"]],
            "to": [round(v + EXPANSION, 4) for v in element["to"]],
            "faces": element["faces"],
        }
        for element in src["elements"]
    ]


def write(path: pathlib.Path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, indent=2) + "\n")


def main():
    # Title of the Polymer creative tab (that tab only exists on clients running Polymer).
    lang = {"itemGroup.woolbackport.backport": "Wool & Concrete Stairs & Slabs"}
    tags = {}  # tag path (under data/minecraft/tags/block/) -> block ids
    n = 0
    for material, (mat_name, tag) in MATERIALS.items():
        for color, name in COLORS.items():
            base = f"{color}_{material}"  # id stem, e.g. white_concrete
            tex = f"minecraft:block/{base}"
            for suffix, parent in ALL_VARIANTS.items():
                model_name = f"{base}{suffix}"
                # block model — all faces use the existing vanilla block texture.
                # Explicit fixed-display scale 0.5 (like minecraft:block/block): item displays render
                # the FIXED context at half size, so the holder asks for x2 and 0.5 * 2 = flush 1x1x1.
                model = {
                    "parent": parent,
                    "display": {"fixed": {"scale": [0.5, 0.5, 0.5]}},
                    "textures": {"bottom": tex, "top": tex, "side": tex},
                }
                if suffix in EXPANDED:
                    # Overrides the parent's cuboids but keeps its display transforms (GUI icon).
                    model["elements"] = expanded_elements(EXPANDED[suffix])
                write(RES / f"assets/woolbackport/models/block/{model_name}.json", model)
                # Item model definition. This MUST live under the minecraft namespace at the real id
                # (<base>_stairs / _slab; the _inner/_outer/_top ids are display-only), not under our
                # namespace: an item model is resolved by whichever id the holder names. The block's
                # ITEM_MODEL component names the native id, and viewers such as RRV resolve the item
                # they were given by its real id — pointed at woolbackport: they draw a missing model.
                # Polymer copies every assets/ tree from the mod jar into the pack, so files here ship
                # even though the blocks are registered under minecraft: ids.
                write(RES / f"assets/minecraft/items/{model_name}.json", {
                    "model": {"type": "minecraft:model", "model": f"woolbackport:block/{model_name}"},
                })
                n += 2

            stairs_id = f"minecraft:{base}_stairs"
            slab_id = f"minecraft:{base}_slab"

            # lang: blocks are registered under minecraft: ids, so keys are block.minecraft.*
            lang[f"block.minecraft.{base}_stairs"] = f"{name} {mat_name} Stairs"
            lang[f"block.minecraft.{base}_slab"] = f"{name} {mat_name} Slab"

            tags.setdefault(tag, []).extend([stairs_id, slab_id])

            # loot tables (drop self). No tool condition needed either way:
            # requiresCorrectToolForDrops gates whether the table is rolled at all, so the table
            # stays tool-agnostic — same as vanilla white_wool.json / white_concrete.json.
            # Slab drops 2 when double, matching vanilla oak_slab.
            write(RES / f"data/minecraft/loot_table/blocks/{base}_stairs.json", {
                "type": "minecraft:block",
                "pools": [{
                    "rolls": 1.0,
                    "conditions": [{"condition": "minecraft:survives_explosion"}],
                    "entries": [{"type": "minecraft:item", "name": stairs_id}],
                }],
                "random_sequence": f"minecraft:blocks/{base}_stairs",
            })
            write(RES / f"data/minecraft/loot_table/blocks/{base}_slab.json", {
                "type": "minecraft:block",
                "pools": [{
                    "rolls": 1.0,
                    "entries": [{
                        "type": "minecraft:item",
                        "name": slab_id,
                        "functions": [
                            {"function": "minecraft:set_count", "count": 2.0, "conditions": [{
                                "condition": "minecraft:block_state_property",
                                "block": slab_id,
                                "properties": {"type": "double"},
                            }]},
                            {"function": "minecraft:explosion_decay"},
                        ],
                    }],
                }],
                "random_sequence": f"minecraft:blocks/{base}_slab",
            })

            # recipes (vanilla patterns: 6 -> 4 stairs, 3 -> 6 slabs; no stonecutter recipe,
            # matching 26.3 for both materials)
            source = f"minecraft:{base}"
            write(RES / f"data/minecraft/recipe/{base}_stairs.json", {
                "type": "minecraft:crafting_shaped", "category": "building",
                "key": {"#": source}, "pattern": ["#  ", "## ", "###"],
                "result": {"id": stairs_id, "count": 4},
            })
            # Wool slabs can NOT use 26.3's horizontal "###" pattern: PolyDecorations' sleeping bag is
            # the same three wool in a row, and when two shaped recipes match one grid vanilla picks
            # the winner by arbitrary recipe-map order (on a real server the sleeping bag won, leaving
            # wool slabs uncraftable). A vertical column cannot match that pattern, so it stays
            # deterministic; same 3 wool -> 6 slabs cost/yield, and it still shows in the recipe book.
            # Concrete keeps the vanilla horizontal pattern — nothing else crafts from 3 concrete.
            slab_pattern = ["#", "#", "#"] if material == "wool" else ["###"]
            write(RES / f"data/minecraft/recipe/{base}_slab.json", {
                "type": "minecraft:crafting_shaped", "category": "building",
                "key": {"#": source}, "pattern": slab_pattern,
                "result": {"id": slab_id, "count": 6},
            })
            n += 2

    lang_sorted = dict(sorted(lang.items()))
    # Resource-pack lang. The client merges every namespace's lang/*.json into one global map, so the
    # block.minecraft.* keys resolve even from our namespace — and addModAssets(MOD_ID) bundles
    # assets/woolbackport/ into the pack (it does NOT bundle assets/minecraft/, which is why the
    # minecraft-namespace path silently failed before). This is what actually shows names in-game.
    write(RES / "assets/woolbackport/lang/en_us.json", lang_sorted)
    # Datapack lang: Server Translations API reads data/<ns>/lang/*.json and sends per-client name
    # fallbacks, so clients who decline the resource pack still get real names.
    write(RES / "data/woolbackport/lang/en_us.json", lang_sorted)
    # Additive tags (no "replace") -> our blocks join the vanilla entries rather than replacing them.
    for path, ids in tags.items():
        write(RES / f"data/minecraft/tags/block/{path}.json", {"values": ids})
        n += 1
    n += 2
    print(f"generated {n} files for {len(MATERIALS)} materials x {len(COLORS)} colors")


if __name__ == "__main__":
    main()
