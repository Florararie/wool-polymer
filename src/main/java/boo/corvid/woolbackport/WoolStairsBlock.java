package boo.corvid.woolbackport;

import eu.pb4.polymer.blocks.api.PolymerTexturedBlock;
import eu.pb4.polymer.virtualentity.api.BlockWithElementHolder;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.StairsShape;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * A real wool/concrete stair. The client sees a stair-shaped donor state matching this state's
 * orientation, so collision and targeting line up with the real block: you walk up smoothly and can
 * click/place on the whole stair, including the top step. An item-display entity carries the right
 * shape model (straight/inner/outer), rotated to match the vanilla blockstate.
 *
 * <p>Stairs cannot be drawn as real blocks the way bottom slabs are (see {@link WoolSlabBlock}): they
 * need 40 shapes per colour, and Polymer's stair pools hold only 4 states per orientation (the four
 * copper stairs), so there is room for four colours, not thirty-two. The donor is therefore
 * <i>visible</i> — the matching waxed cut copper stair — rather than an empty model. An empty donor
 * is a hole in the world for the tick before the display entity is sent, which reads as another
 * block flashing on placement; a visible donor of the same shape is hidden by the display, whose
 * model cuboids are grown ~5 mm past the donor's so the two never z-fight (generated that way in
 * tools/gen_assets.py, not scaled on the entity - a scale about the model centre cannot move the
 * tread at y=8), so placing simply shows a solid stair that immediately becomes wool.
 */
public class WoolStairsBlock extends StairBlock
        implements PolymerTexturedBlock, BlockWithElementHolder, WoolBackport.DisplayProvider {
    private final Map<BlockState, BlockState> clientStates = new IdentityHashMap<>();
    private final BlockState sourceState;
    private final Identifier straightModel;
    private final Identifier innerModel;
    private final Identifier outerModel;

    /** {@code base} is the id stem, e.g. "white_wool" or "white_concrete". */
    public WoolStairsBlock(BlockState baseState, Properties properties, String base) {
        super(baseState, properties);
        this.sourceState = baseState; // the matching full block; drives break particles + sound
        // Model ids are the native minecraft: ids (not our namespace): the ITEM_MODEL component and
        // third-party item resolvers look the model up by the id they hold, which is the real one.
        this.straightModel = Identifier.withDefaultNamespace(base + "_stairs");
        this.innerModel = Identifier.withDefaultNamespace(base + "_stairs_inner");
        this.outerModel = Identifier.withDefaultNamespace(base + "_stairs_outer");

        // Visible client donor: the matching waxed cut copper stair, which copies this state's
        // facing/half/shape/waterlogged, so the client's collision, outline and targeting are the true
        // stair shape and the block is never a hole. Its own metallic texture is hidden behind the
        // display entity. (The real stair shape stays authoritative server-side.)
        for (BlockState state : getStateDefinition().getPossibleStates()) {
            clientStates.put(state, Blocks.WAXED_CUT_COPPER_STAIRS.withPropertiesOf(state));
        }
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, @Nullable PacketContext context) {
        return clientStates.get(state);
    }

    /**
     * The block-break effect (particles + sound, LevelEvent 2001) is derived by the client from
     * whatever state we send here. Default Polymer would send the stair donor -> generic (copper)
     * sound; send the real source block so the client plays its break sound with right particles.
     */
    @Override
    public BlockState getPolymerBreakEventBlockState(BlockState state, @Nullable PacketContext context) {
        return sourceState;
    }

    @Override
    public @Nullable ItemStack displayStack(BlockState state) {
        StairsShape shape = state.getValue(SHAPE);
        Identifier model = switch (shape) {
            case STRAIGHT -> straightModel;
            case INNER_LEFT, INNER_RIGHT -> innerModel;
            case OUTER_LEFT, OUTER_RIGHT -> outerModel;
        };
        ItemStack stack = new ItemStack(WoolBackport.STAIRS_DISPLAY);
        stack.set(DataComponents.ITEM_MODEL, model);
        return stack;
    }

    @Override
    public Quaternionf displayRotation(BlockState state) {
        return stairRotation(state.getValue(FACING), state.getValue(HALF), state.getValue(SHAPE));
    }

    /**
     * Required for corner shapes to appear: Polymer only sends element data (the display's model and
     * rotation) while ticking the holder, and this defaults to false. Un-ticked, a holder keeps
     * showing the state it was created with, so a straight stair never turns into a corner when a
     * neighbour is placed next to it.
     */
    @Override
    public boolean tickElementHolder(ServerLevel world, BlockPos pos, BlockState initialBlockState) {
        return true;
    }

    @Override
    public @Nullable ElementHolder createElementHolder(ServerLevel world, BlockPos pos, BlockState initialBlockState) {
        return new WoolBackport.WoolDisplayHolder(this, initialBlockState);
    }

    /**
     * Rotation to orient the item-display model like a vanilla stair.
     *
     * Base rotation = the vanilla oak_stairs.json (x,y) for this state, converted
     * to MC's matrix
     * R_Y(-y)*R_X(-x) (== OctahedralGroup math; x,y unit-verified against all 40
     * blockstate variants).
     *
     * Item-displays render a block model rotated a global 180 about Y relative to
     * how a blockstate
     * renders it (observed in-game: stairs faced "away"; the offset hits every
     * facing equally, so it
     * is a single global flip, not a per-state error). We correct it by
     * post-multiplying R_Y(180):
     * R = R_Y(-y) * R_X(-x) * R_Y(180).
     */
    static Quaternionf stairRotation(Direction facing, Half half, StairsShape shape) {
        int base = switch (facing) {
            case NORTH -> 270;
            case EAST -> 0;
            case SOUTH -> 90;
            case WEST -> 180;
            default -> 0; // stairs are always horizontal
        };
        int x;
        int y;
        if (half == Half.BOTTOM) {
            x = 0;
            boolean left = shape == StairsShape.INNER_LEFT || shape == StairsShape.OUTER_LEFT;
            y = left ? (base + 270) % 360 : base;
        } else {
            x = 180;
            boolean right = shape == StairsShape.INNER_RIGHT || shape == StairsShape.OUTER_RIGHT;
            y = right ? (base + 90) % 360 : base;
        }
        return new Quaternionf()
                .rotateY((float) Math.toRadians(-y))
                .rotateX((float) Math.toRadians(-x))
                .rotateY((float) Math.PI); // global item-display vs blockstate 180 Y correction
    }
}
