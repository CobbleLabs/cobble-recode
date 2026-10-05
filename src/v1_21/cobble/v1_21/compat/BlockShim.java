package cobble.v1_21.compat;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

/**
 * 1.8.9's Block on 1.21.11's. Shapes and hardness belong to the block state
 * on 1.21, so the questions 1.8 put to a block are put to its state; 1.21
 * has no metadata, which reads as 0.
 */
public final class BlockShim {
    private BlockShim() {}

    /* Null for a name the version has no block for, as on 1.8. */
    public static Block getBlockFromName(String name) {
        Identifier id = Identifier.tryParse(name);
        return id == null ? null : Registries.BLOCK.getOptionalValue(id).orElse(null);
    }

    public static Registry<Block> blockRegistry() {
        return Registries.BLOCK;
    }

    public static Material getMaterial(Block block) {
        return Material.of(block);
    }

    public static float slipperiness(Block block) {
        return block.getSlipperiness();
    }

    public static boolean canCollideCheck(Block block, BlockState state, boolean hitIfLiquid) {
        return !state.isAir() && (hitIfLiquid || !state.isLiquid());
    }

    public static HitResult collisionRayTrace(Block block, World world, BlockPos pos, Vec3d from, Vec3d to) {
        return world.getBlockState(pos).getOutlineShape(world, pos).raycast(from, to, pos);
    }

    public static float getBlockHardness(Block block, World world, BlockPos pos) {
        return world.getBlockState(pos).getHardness(world, pos);
    }

    public static Box getCollisionBoundingBox(Block block, World world, BlockPos pos, BlockState state) {
        return box(state.getCollisionShape(world, pos), pos);
    }

    public static Box getSelectedBoundingBox(Block block, World world, BlockPos pos) {
        return box(world.getBlockState(pos).getOutlineShape(world, pos), pos);
    }

    private static Box box(VoxelShape shape, BlockPos pos) {
        return shape.isEmpty() ? null : shape.getBoundingBox().offset(pos);
    }

    public static int getDamageValue(Block block, World world, BlockPos pos) {
        return 0;
    }

    public static int getMetaFromState(Block block, BlockState state) {
        return 0;
    }

    public static int getIdFromBlock(Block block) {
        return Registries.BLOCK.getRawId(block);
    }

    public static int getStateId(BlockState state) {
        return Block.getRawIdFromState(state);
    }

    public static Item getItem(Block block, World world, BlockPos pos) {
        return block.asItem();
    }

    public static String getLocalizedName(Block block) {
        return block.getName().getString();
    }

    public static String getUnlocalizedName(Block block) {
        return block.getTranslationKey();
    }

    public static MapColor getMapColor(Block block, BlockState state) {
        return state.getMapColor(null, BlockPos.ORIGIN);
    }

    public static boolean isCollidable(Block block) {
        return true;
    }

    public static boolean isFullBlock(Block block) {
        return block.getDefaultState().isOpaqueFullCube();
    }

    public static boolean isOpaqueCube(Block block) {
        return block.getDefaultState().isOpaque();
    }

    public static boolean isReplaceable(Block block, World world, BlockPos pos) {
        return world.getBlockState(pos).isReplaceable();
    }

    /* 1.21's shapes come from the state each time; there are no bounds to set. */
    public static void setBlockBoundsBasedOnState(Block block, BlockView world, BlockPos pos) {}
}
