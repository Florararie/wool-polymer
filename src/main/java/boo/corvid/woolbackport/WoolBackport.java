package boo.corvid.woolbackport;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.PolymerBlockModel;
import eu.pb4.polymer.blocks.api.PolymerBlockResourceUtils;
import eu.pb4.polymer.blocks.api.PolymerTexturedBlock;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import eu.pb4.polymer.resourcepack.impl.generation.DefaultRPBuilder;
import eu.pb4.polymer.soundpatcher.api.SoundPatcher;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.attachment.BlockAwareAttachment;
import eu.pb4.polymer.virtualentity.api.attachment.HolderAttachment;
import eu.pb4.polymer.core.api.item.PolymerCreativeModeTabUtils;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Backports 26.3's wool and concrete stairs and slabs (all 16 colors each) to 26.1.2, server-side,
 * via Polymer.
 *
 * Blocks are registered under their native {@code minecraft:} ids, so world data is identical to
 * 26.3 and native takes over with zero migration once the server updates. The mod refuses to load
 * outside the 26.1.x line (fabric.mod.json depends "minecraft": ">=26.1.2 <26.2"); the registry
 * guard below is a second safety net against re-registering an id that already exists natively.
 *
 * Vanilla clients can't render 16 colors of stairs/slabs: a client only has shapes Minecraft ships,
 * and Polymer's donor pools hold 5 slab states and 4 states per stair orientation, not 32. Bottom
 * slabs are the exception — the sculk-sensor pool has 75 usable states per waterlogging at exactly a
 * bottom slab's shape, so those become real client blocks (instant, smooth-lit). Top slabs and stairs
 * stay a per-block item-display entity over a shape-correct donor block that the client collides
 * with. That donor is visible rather than empty: an empty one is a hole until the entity is sent,
 * which looks like another block flashing when you place something. The real server-side
 * StairBlock/SlabBlock keeps collision either way.
 */
public class WoolBackport implements ModInitializer {
    public static final String MOD_ID = "woolbackport";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** Vanilla dye colors, matching minecraft:&lt;color&gt;_wool / _concrete ids. */
    static final String[] COLORS = {
            "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black",
    };

    /**
     * Block families backported here. Ids are {@code <color>_<material>_stairs} / {@code _slab},
     * and the source block each one copies is {@code <color>_<material>}.
     */
    static final String[] MATERIALS = {"wool", "concrete"};

    /** Identity rotation, shared (setLeftRotation copies it). */
    static final Quaternionf NO_ROTATION = new Quaternionf();

    /**
     * Items carried by the display entities. They render the colored wool model for pack-having
     * viewers and a generic vanilla stair/slab for pack-less ones (see {@link WoolDisplayItem}).
     * Shared across all 16 colors — the per-block model rides in the stack's ITEM_MODEL component.
     */
    static WoolDisplayItem STAIRS_DISPLAY;
    static WoolDisplayItem SLAB_DISPLAY;

    @Override
    public void onInitialize() {
        // Forward-compat, per family: skip any material that already exists natively. Wool landed in
        // 26.3-snapshot-2 and concrete in snapshot-7, so a dev build can have one and not the other.
        List<String> pending = new ArrayList<>();
        for (String material : MATERIALS) {
            if (!BuiltInRegistries.BLOCK.containsKey(
                    Identifier.withDefaultNamespace("white_" + material + "_stairs"))) {
                pending.add(material);
            }
        }
        if (pending.isEmpty()) {
            return; // everything exists natively (26.3+); nothing to polyfill
        }

        PolymerResourcePackUtils.markAsRequired();
        PolymerResourcePackUtils.addModAssets(MOD_ID);

        // Stairs keep their display without the pack (falling back to oak stairs); slabs hide it and
        // let the exact slab-shaped donor show through.
        STAIRS_DISPLAY = registerDisplayItem("stairs_display", Items.OAK_STAIRS, false);
        SLAB_DISPLAY = registerDisplayItem("slab_display", Items.OAK_SLAB, true);

        Set<SoundType> donorSounds = new HashSet<>();
        List<Item> creativeItems = new ArrayList<>();
        for (String material : pending) {
            for (String color : COLORS) {
                String base = color + "_" + material;
                Block source = BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(base));
                // Inventory-item fallback is the matching FULL block: a vanilla client predicts
                // placement from the item it holds, reads that block's SoundType back, and plays it —
                // so holding a wool item makes the placer hear block.wool.place (no resource pack, no
                // sound-patcher). Pack-less clients then see a full-block icon; the world block still
                // falls back to a stair shape via STAIRS_DISPLAY/SLAB_DISPLAY.
                Block stairs = new WoolStairsBlock(source.defaultBlockState(), props(source, base + "_stairs"), base);
                Block slab = new WoolSlabBlock(props(source, base + "_slab"), source, base);
                creativeItems.add(register(base + "_stairs", stairs, source.asItem()));
                creativeItems.add(register(base + "_slab", slab, source.asItem()));
                collectDonorSounds(stairs, donorSounds);
                collectDonorSounds(slab, donorSounds);
            }
        }

        makeDonorStepSoundsServerAuthoritative(donorSounds);
        addToCreativeInventory(creativeItems);
        registerCreativeTab(creativeItems);

        // Polymer rewrites a donor's blockstate file with only the states it handed out, so this puts
        // vanilla's own variants back for the ones we borrowed (see restoreDonorVariants). Registered
        // after the blocks above, i.e. after Polymer registered its own creation hook, so we also get
        // to queue in front-of-nothing on the builder's build event (where it actually generates).
        PolymerResourcePackUtils.RESOURCE_PACK_CREATION_EVENT.register(WoolBackport::queueDonorVariantMerge);
    }

    /**
     * Puts the blocks into Building Blocks, in the order they were registered (dye order, stairs then
     * slab, wool family before concrete).
     *
     * The client builds creative tabs out of its own item registry, so it can only ever show items it
     * knows — and these ids are ours, not vanilla 26.1.2's. Polymer therefore syncs tab contents per
     * player, and it computes them through Fabric's tab events, so registering here is what makes them
     * appear. That path needs Polymer on the client: a vanilla client never receives tab contents (it
     * has no way to name the items anyway), so there {@code /give} stays the way to get them.
     */
    private static void addToCreativeInventory(List<Item> items) {
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.BUILDING_BLOCKS)
                .register(output -> {
                    for (Item item : items) {
                        output.accept(new ItemStack(item));
                    }
                });
    }

    /**
     * Polymer's own creative tab. Vanilla tabs are built by the client and then patched over the wire,
     * so they only gain our items on clients running Polymer, and only if that patch lands; a tab
     * definition is created client-side from our data instead, so it always shows the real items.
     *
     * Only clients running Polymer see it: it has to tell the client what the tab is, and a vanilla
     * client cannot name items its own registry does not contain, so {@code /give} stays their path.
     */
    private static void registerCreativeTab(List<Item> items) {
        var tab = PolymerCreativeModeTabUtils.builder()
                .title(Component.translatable("itemGroup.woolbackport.backport"))
                .icon(() -> new ItemStack(items.getFirst()))
                .displayItems((params, output) -> items.forEach(item -> output.accept(new ItemStack(item))))
                .build();
        PolymerCreativeModeTabUtils.registerPolymerCreativeModeTab(
                Identifier.fromNamespaceAndPath(MOD_ID, "backport"), tab);
    }

    /**
     * The donor blockstates do not exist yet during the creation hook: Polymer generates them on the
     * builder's own build event ({@code BlockResourceCreator.registerEvent}), which every creation hook
     * has already returned from. So queue the merge there. Both are consumer events invoked in
     * registration order, and Polymer registered first, so this runs with the generated files in hand.
     */
    private static void queueDonorVariantMerge(ResourcePackBuilder builder) {
        if (builder instanceof DefaultRPBuilder<?> late) {
            late.buildEvent.register(credits -> restoreDonorVariants(builder));
        } else {
            LOGGER.warn("[{}] unexpected pack builder {}, sculk variants left unmerged", MOD_ID, builder.getClass());
        }
    }

    /**
     * A real client block state that renders one of our models. Sculk sensors are the only vanilla
     * donor big enough for 32 colors at slab shape: 75 usable states per waterlogging (15 sensors + 60
     * calibrated), all of them at {@code power=1..15} — a property vanilla's blockstate ignores and a
     * real sensor only holds while it is active. Polymer normalises every real sensor to
     * {@code power=0} when sending it, and {@link #restoreDonorVariants} puts vanilla's variants back,
     * so borrowing these states cannot change how sculk sensors look.
     *
     * @return the allocated state, or null if the pool is exhausted (caller falls back to a visible
     *         donor plus a display entity)
     */
    static @Nullable BlockState requestSculkState(boolean waterlogged, Identifier model) {
        return PolymerBlockResourceUtils.requestBlock(
                waterlogged ? BlockModelType.SCULK_SENSOR_WATERLOGGED : BlockModelType.SCULK_SENSOR,
                PolymerBlockModel.of(model));
    }

    /**
     * Polymer replaces a donor block's blockstate file with just the states it allocated, which would
     * leave every other state of that block — including the one it normalises real blocks to — without
     * a model. We add vanilla's own variant entries back: ours name every property of a state, so they
     * win for the states we allocated, and the client resolves everything else exactly as vanilla.
     */
    private static void restoreDonorVariants(ResourcePackBuilder builder) {
        for (String donor : new String[]{"sculk_sensor", "calibrated_sculk_sensor"}) {
            String path = "assets/minecraft/blockstates/" + donor + ".json";
            byte[] generated = builder.getData(path);
            if (generated == null) {
                continue; // no state of this donor was allocated
            }
            // Copy of vanilla's file, shipped at data/woolbackport/vanilla_blockstates/ (extracted
            // from 26.1.2). Read from the classpath: once our data is in the builder, its own
            // getDataOrSource() returns ours, not vanilla's.
            try (InputStream vanilla = WoolBackport.class.getResourceAsStream(
                    "/data/" + MOD_ID + "/vanilla_blockstates/" + donor + ".json")) {
                if (vanilla == null) {
                    LOGGER.warn("[{}] no vanilla blockstate copy for {}, leaving it unmerged", MOD_ID, donor);
                    continue;
                }
                JsonObject merged = JsonParser.parseString(new String(generated, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                JsonObject variants = merged.getAsJsonObject("variants");
                JsonObject original = JsonParser.parseString(new String(vanilla.readAllBytes(), StandardCharsets.UTF_8))
                        .getAsJsonObject().getAsJsonObject("variants");
                for (var entry : original.entrySet()) {
                    // Gaps only: a full state name we allocated is more specific and must keep winning.
                    if (!variants.has(entry.getKey())) {
                        variants.add(entry.getKey(), entry.getValue());
                    }
                }
                builder.addData(path, merged.toString().getBytes(StandardCharsets.UTF_8));
                LOGGER.info("[{}] restored {} vanilla variants in {}'s blockstate", MOD_ID, original.size(), donor);
            } catch (IOException e) {
                LOGGER.warn("[{}] failed to merge vanilla variants for {}", MOD_ID, donor, e);
            }
        }
    }

    /**
     * Step / mining-hit / fall are predicted client-side from the invisible donor block (waxed cut
     * copper stairs → metallic, petrified oak slab → stone), which no per-packet override can reach.
     * polymer-sound-patcher silences the donor's predicted sounds (empty entries in the resource pack)
     * and makes the server send our real sound instead. Only step/hit/fall need this; place/break
     * already sound right. Blast radius: every block of the donor's material becomes
     * server-authoritative for those three sounds (same sound, just server-driven).
     */
    private static void makeDonorStepSoundsServerAuthoritative(Set<SoundType> donorSounds) {
        for (SoundType donor : donorSounds) {
            SoundPatcher.convertIntoServerSound(donor.getStepSound());
            SoundPatcher.convertIntoServerSound(donor.getHitSound());
            SoundPatcher.convertIntoServerSound(donor.getFallSound());
            LOGGER.info("[{}] donor sound '{}' is now server-authoritative so our blocks step/hit/fall correctly",
                    MOD_ID, donor.getStepSound().location());
        }
    }

    /**
     * Adds the SoundTypes of a Polymer block's client donor states (what a vanilla client predicts).
     *
     * Skips donors that already sound like the real block — the client's prediction is correct there,
     * so there is nothing to silence and no reason to widen the blast radius. This is per-state, not
     * per-material: a concrete slab's donor (petrified oak slab) is already stone, while a WOOL slab's
     * donor is that same stone donor and very much does need converting.
     */
    private static void collectDonorSounds(Block block, Set<SoundType> out) {
        if (!(block instanceof PolymerTexturedBlock textured)) {
            return;
        }
        for (BlockState state : block.getStateDefinition().getPossibleStates()) {
            BlockState donor = textured.getPolymerBlockState(state, null);
            if (donor != null && donor.getSoundType() != state.getSoundType()) {
                out.add(donor.getSoundType());
            }
        }
    }

    private static BlockBehaviour.Properties props(Block source, String path) {
        // ofFullCopy carries the source block's sound, hardness and tool requirement.
        return BlockBehaviour.Properties.ofFullCopy(source)
                .setId(ResourceKey.create(Registries.BLOCK, Identifier.withDefaultNamespace(path)));
    }

    private static Item register(String path, Block block, Item fallback) {
        Identifier id = Identifier.withDefaultNamespace(path);
        Registry.register(BuiltInRegistries.BLOCK, id, block);
        // useBlockDescriptionPrefix() makes the item's default name key "block.minecraft.<path>"
        // (matching our lang + native 26.3); without it a BlockItem defaults to "item.minecraft.<path>",
        // which our block.* translations never match — so the client shows the raw item.minecraft key.
        var itemProps = new Item.Properties()
                .useBlockDescriptionPrefix()
                .setId(ResourceKey.create(Registries.ITEM, id));
        return Registry.register(BuiltInRegistries.ITEM, id,
                new WoolBlockItem(block, itemProps, Identifier.withDefaultNamespace(path), fallback));
    }

    /** Registers a shared display-entity item under a woolbackport: id (never in a creative tab). */
    private static WoolDisplayItem registerDisplayItem(String path, Item fallback, boolean hideWithoutPack) {
        Identifier id = Identifier.fromNamespaceAndPath(MOD_ID, path);
        var props = new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id));
        return Registry.register(BuiltInRegistries.ITEM, id, new WoolDisplayItem(props, fallback, hideWithoutPack));
    }

    /** Implemented by the stairs/slab blocks to drive their display entity per block state. */
    public interface DisplayProvider {
        /** Item to display (its item-model is the block model), or null for no entity (e.g. double slab). */
        @Nullable ItemStack displayStack(BlockState state);

        /** Orientation for the display, matching the vanilla blockstate rotation. */
        Quaternionf displayRotation(BlockState state);
    }

    /**
     * One item-display entity per placed block that needs one, rendered flush in the cell
     * (ItemDisplayContext.NONE, holder auto-anchored at block center). Rebuilds itself on block-state
     * changes (e.g. a stair's corner shape changing when a neighbor is placed, or a slab becoming a
     * double). Lifecycle (create on chunk load, destroy on unload/break) is handled by Polymer.
     */
    public static class WoolDisplayHolder extends ElementHolder {
        private final DisplayProvider provider;
        private @Nullable ItemDisplayElement element;

        public WoolDisplayHolder(DisplayProvider provider, BlockState initialState) {
            this.provider = provider;
            refresh(initialState);
        }

        private void refresh(BlockState state) {
            ItemStack stack = provider.displayStack(state);
            if (stack == null) {
                if (element != null) {
                    removeElement(element);
                    element = null;
                }
                return;
            }
            if (element == null) {
                element = new ItemDisplayElement();
                // FIXED is the "placed block" context but bakes a 0.5 scale, so x2 -> flush 1x1x1.
                // (NONE does not sit flush for item displays.) Anchored at block center by the holder.
                // The clearance over the donor lives in the MODEL, not here: gen_assets grows every
                // cuboid by 0.08 (5mm) when it builds the display models. Scaling the display instead
                // cannot work - a uniform scale about the model center leaves the plane through that
                // center exactly where it was, and a stair tread and a top slab's underside both lie
                // on y=8, i.e. on that plane, so those faces kept z-fighting no matter the factor.
                element.setItemDisplayContext(ItemDisplayContext.FIXED);
                element.setScale(new Vector3f(2.0f));
                element.setInvisible(true);
                element.setDisplaySize(1, 1);
                addElement(element);
            }
            element.setItem(stack);
            element.setLeftRotation(provider.displayRotation(state));
        }

        @Override
        public void notifyUpdate(HolderAttachment.UpdateType updateType) {
            super.notifyUpdate(updateType);
            if (updateType == BlockAwareAttachment.BLOCK_STATE_UPDATE) {
                var attachment = BlockAwareAttachment.get(this);
                if (attachment != null) {
                    refresh(attachment.getBlockState());
                }
            }
        }
    }
}
