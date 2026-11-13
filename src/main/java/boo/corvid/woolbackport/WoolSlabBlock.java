package boo.corvid.woolbackport;

import eu.pb4.polymer.blocks.api.PolymerTexturedBlock;
import eu.pb4.polymer.virtualentity.api.BlockWithElementHolder;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A real wool/concrete slab. Double slabs are shown to the client as the plain full source block (it
 * already exists in 26.1.2 and looks identical). Bottom slabs are sent as a real sculk-sensor state
 * carrying our model: the client renders them itself, so they appear instantly, are smooth-lit and
 * ambient-occluded, and collide at exactly the slab shape (a sculk sensor is 8/16 of a block high).
 * Top slabs need the upper half of the cell, which no pool big enough for 32 colours offers, so those
 * stay a shape-correct donor block plus an item-display entity.
 *
 * <p>The top donor is <b>visible</b> (a petrified oak slab of the same half and waterlogging) rather
 * than an empty model. An empty donor is a hole for the tick between the block update and the display
 * entity being sent, which reads in game as another block flashing on placement; with a visible donor
 * the cell is always solid and only the texture swaps. The display is inflated ~5 mm past it so the
 * coincident faces can't z-fight (see {@link WoolBackport.WoolDisplayHolder}).
 *
 * <p>Borrowing sculk states leaves sculk sensors working: the pool only holds {@code power=1..15}
 * states (vanilla's blockstate ignores power, and a real sensor only has it set while active),
 * Polymer normalises real sensors back to {@code power=0} when it sends them, and the pack restores
 * vanilla's own variant entries alongside ours.
 */
public class WoolSlabBlock extends SlabBlock implements PolymerTexturedBlock, BlockWithElementHolder, WoolBackport.DisplayProvider {
    private final Map<BlockState, BlockState> clientStates = new IdentityHashMap<>();
    /** States whose client form is a plain donor, i.e. the only ones that need a display entity. */
    private final Set<BlockState> displayStates = Collections.newSetFromMap(new IdentityHashMap<>());
    private final BlockState sourceState;
    private final Identifier bottomModel;
    private final Identifier topModel;

    /** {@code base} is the id stem, e.g. "white_wool" or "white_concrete". */
    public WoolSlabBlock(Properties properties, Block sourceBlock, String base) {
        super(properties);
        // Native ids, matching where the pack serves the item model definition (see gen_assets.py).
        this.bottomModel = Identifier.withDefaultNamespace(base + "_slab");
        this.topModel = Identifier.withDefaultNamespace(base + "_slab_top");

        BlockState source = sourceBlock.defaultBlockState();
        this.sourceState = source; // drives break particles + sound (e.g. block.wool.break)
        Identifier slabModel = Identifier.fromNamespaceAndPath(WoolBackport.MOD_ID, "block/" + base + "_slab");
        for (BlockState state : getStateDefinition().getPossibleStates()) {
            boolean wl = state.getValue(WATERLOGGED);
            BlockState client = switch (state.getValue(TYPE)) {
                case DOUBLE -> source; // full cube = a real vanilla block, no entity needed
                case BOTTOM -> {
                    BlockState real = WoolBackport.requestSculkState(wl, slabModel);
                    if (real != null) {
                        yield real; // real client block: instant, smooth-lit, no entity
                    }
                    displayStates.add(state); // pool exhausted (not with 32 colours): donor + entity
                    yield donor(SlabType.BOTTOM, wl);
                }
                case TOP -> {
                    displayStates.add(state);
                    yield donor(SlabType.TOP, wl);
                }
            };
            clientStates.put(state, client);
        }
    }

    /**
     * Visible donor with this slab's exact shape, collision and waterlogging. Petrified oak is the
     * block Polymer already uses as its slab donor: unobtainable in survival, so nothing legitimate
     * looks different, and it is a real {@link SlabBlock} state, so the client's shape is the true slab
     * shape (unlike a full-cube stand-in).
     */
    private static BlockState donor(SlabType type, boolean waterlogged) {
        return Blocks.PETRIFIED_OAK_SLAB.defaultBlockState()
                .setValue(TYPE, type)
                .setValue(WATERLOGGED, waterlogged);
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, @Nullable PacketContext context) {
        return clientStates.get(state);
    }

    /** Send the real source block for the break effect so the client plays its break sound. */
    @Override
    public BlockState getPolymerBreakEventBlockState(BlockState state, @Nullable PacketContext context) {
        return sourceState;
    }

    @Override
    public @Nullable ItemStack displayStack(BlockState state) {
        SlabType type = state.getValue(TYPE);
        if (type == SlabType.DOUBLE) {
            return null; // rendered as a real full vanilla block
        }
        ItemStack stack = new ItemStack(WoolBackport.SLAB_DISPLAY);
        stack.set(DataComponents.ITEM_MODEL, type == SlabType.TOP ? topModel : bottomModel);
        return stack;
    }

    @Override
    public Quaternionf displayRotation(BlockState state) {
        return WoolBackport.NO_ROTATION; // slab/slab_top models are pre-shaped
    }

    /**
     * Lets Polymer tick the holder so a slab's display follows state changes (bottom -> top becoming
     * a double slab and back); without it only the initially created element data ever reaches the
     * client.
     */
    @Override
    public boolean tickElementHolder(ServerLevel world, BlockPos pos, BlockState initialBlockState) {
        return true;
    }

    @Override
    public @Nullable ElementHolder createElementHolder(ServerLevel world, BlockPos pos, BlockState initialBlockState) {
        // Bottom slabs are real client blocks; top slabs (and any bottom that could not allocate a
        // sculk state) are drawn by the entity. Polymer evaluates this per state, including when a
        // double slab is broken back into a single one, so switching halves still gets a holder.
        return displayStates.contains(initialBlockState)
                ? new WoolBackport.WoolDisplayHolder(this, initialBlockState)
                : null;
    }
}
