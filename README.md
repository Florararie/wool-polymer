# Wool & Concrete Stairs & Slabs Backport

Original [here](https://github.com/craftycorvid/wool-polymer)

Server-side backport of Minecraft 26.3's wool stairs and slabs _and_ concrete stairs and slabs (all 16 colors each, 64 blocks) to 26.1.2, built with [Polymer](https://polymer.pb4.eu) + Fabric. Players need **only a resource pack** (for the shapes) — no client mod. Names, drops, recipes, shears-mining (wool) and pickaxe-mining (concrete) all work server-side. Designed to vanish the moment 26.3 ships: the blocks use their native `minecraft:` ids, so world data is identical and native takes over with zero migration.

## Requirements

- A Fabric 26.1.2 server
- **Fabric API**
- **Polymer**

## Install

1. Drop the jar into the server's `mods/` folder (alongside Fabric API and Polymer).
2. Make the generated resource pack reach players (it's marked **required**). Easiest path —
   the bundled **autohost** serves it automatically; enable it once in
   `config/polymer/auto-host.json`:
   ```json
   { "enabled": true }
   ```
   (Behind a proxy, also set `"forced_address"`.) Alternatively run `/polymer generate-pack`
   and serve `polymer/resourcepack.zip` yourself via `resource-pack` in `server.properties`.
3. Players accept the pack on join and see proper stairs/slabs. Craft them with the usual patterns (6 blocks → 4 stairs, 3 blocks → 6 slabs). Neither material has a stonecutter recipe, matching 26.3. Concrete stairs/slabs need a **pickaxe** to drop, like concrete itself.
4. **Wool slabs are 3 wool in a vertical column, not a row.** 26.3's horizontal recipe is identical to PolyDecorations' sleeping bag (three wool across); when two shaped recipes match the same grid, Minecraft picks the winner by arbitrary recipe order, so wool slabs would silently be uncraftable next to it. The column can't collide, costs and yields the same (3 → 6), and appears in the recipe book as usual. The stairs pattern is unaffected, and concrete keeps the vanilla row.

## Known limitations

- **Bottom slabs are real blocks; top slabs and stairs are display entities.** A bottom slab is sent
  as a sculk-sensor state carrying our model, so the client renders it itself: it appears instantly
  and gets smooth lighting and ambient occlusion. Top slabs need the upper half of the cell and
  stairs need 40 shapes, which no vanilla pool is big enough for, so those are drawn by an
  item-display entity: one light sample per block, no AO (Mojira MC-1531 / MC-231099), so they read
  slightly flatter near torches and next to full blocks. Turning Smooth Lighting off in the client's
  video settings evens them out. Double slabs and full blocks are unaffected. Borrowing sculk states
  leaves sculk sensors alone: that pool only holds `power=1..15` states (vanilla's blockstate ignores
  power), Polymer normalises real sensors to `power=0` when it sends them, and the pack puts vanilla's
  own variants back into the donor's blockstate.
- **Placement: bottom slabs are instant, top slabs and stairs swap a texture.** A top slab or stair
  is a shape-identical donor (petrified oak slab, waxed cut copper stair) on the client, with the
  display entity arriving a moment later, so placing swaps a vanilla texture for the wool/concrete one
  instead of showing a hole. The display is inflated ~5 mm past the donor so the two never z-fight.
  The sound patcher makes those donors' step/hit/fall sounds server-authoritative, which sounds
  identical, just server-driven.
- **Creative items need the Polymer client mod, in a tab of their own.** Creative tabs are built by
  the client from its own item registry, so a vanilla client cannot show items it has no id for and
  no server can inject them. The mod registers a Polymer creative tab — **Wool & Concrete Stairs &
  Slabs** — which Polymer creates on connected clients and fills with the 64 real items; it also
  appends them to Building Blocks through the same sync. Both need Polymer on the client. On a
  strictly vanilla client the creative menu is unchanged: use `/give`, or craft the blocks in
  survival. Placing, breaking, sounds and recipes are unaffected either way.
- **Sound prediction** is handled by Polymer's sound patcher, which needs its resource pack (already
  required here). Without the pack, footsteps on wool stairs would still use the donor block's sound.

## Building

Needs **JDK 25** — MC 26.x compiles with `--release 25`, so an older JDK fails on the release flag.

```sh
./gradlew build     # -> build/libs/wool-backport-<version>.jar
```

Polymer, its sound patcher, autohost and the Server Translations API are bundled jar-in-jar, so that
single jar is the whole install. The `assets/` and `data/` trees are generated from one DRY definition
in `tools/gen_assets.py` (all 64 blocks); they are committed, so a fresh clone builds as-is — re-run
`python3 tools/gen_assets.py` only after editing the generator.

## When 26.3 arrives

The blocks are real `minecraft:<color>_wool_stairs` / `_slab` and `minecraft:<color>_concrete_stairs` / `_slab`, so existing builds load **natively** with no conversion. The mod also refuses to load outside 26.1.x (`"minecraft": ">=26.1.2 <26.2"`), and even if forced it skips any family whose ids already exist (wool landed in 26.3-snapshot-2, concrete in snapshot-7). Just delete the jar.

## In Memory of Apollo

This mod was created because we wanted to make a statue of our dog Apollo, who recently passed away.
Our server runs some light mods but is 100% vanilla-client compatible so we couldn't use some of the other mods that backport wool stairs and slabs.
