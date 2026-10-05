package cobble.v1_21.compat;

import net.minecraft.block.AnvilBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.VineBlock;
import net.minecraft.fluid.Fluids;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;

/**
 * 1.8.9's Material, which 1.21.11 dropped for block properties and tags. Only
 * the materials the legacy code names are told apart; a block's material is
 * worked out from its default state, and anything else is {@link #OTHER}.
 */
public final class Material {
    public static final Material air = new Material(false, false, true);
    public static final Material water = new Material(true, false, true);
    public static final Material lava = new Material(true, false, true);
    public static final Material vine = new Material(false, false, true);
    public static final Material anvil = new Material(false, true, false);
    public static final Material iron = new Material(false, true, false);
    public static final Material rock = new Material(false, true, false);
    private static final Material OTHER = new Material(false, true, true);

    private final boolean liquid;
    private final boolean solid;
    private final boolean toolNotRequired;

    private Material(boolean liquid, boolean solid, boolean toolNotRequired) {
        this.liquid = liquid;
        this.solid = solid;
        this.toolNotRequired = toolNotRequired;
    }

    static Material of(Block block) {
        BlockState state = block.getDefaultState();
        if (state.isAir()) {
            return air;
        }
        if (state.getFluidState().isIn(FluidTags.WATER)) {
            return water;
        }
        if (state.getFluidState().isOf(Fluids.LAVA) || state.getFluidState().isOf(Fluids.FLOWING_LAVA)) {
            return lava;
        }
        if (block instanceof VineBlock) {
            return vine;
        }
        if (block instanceof AnvilBlock) {
            return anvil;
        }
        if (state.isIn(BlockTags.PICKAXE_MINEABLE)) {
            return state.isToolRequired() && state.isIn(BlockTags.NEEDS_STONE_TOOL) ? iron : rock;
        }
        return OTHER;
    }

    public boolean isLiquid() {
        return liquid;
    }

    public boolean blocksMovement() {
        return solid;
    }

    public boolean isToolNotRequired() {
        return toolNotRequired;
    }

    public boolean isSolid() {
        return solid;
    }
}
