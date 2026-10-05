package cobble.v1_21.compat;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

/** 1.8.9's ItemBlock on 1.21.11's BlockItem. */
public final class ItemBlockShim {
    private ItemBlockShim() {}

    /* What 1.8 asked first: whether the block against that face would make way for this one. */
    public static boolean canPlaceBlockOnSide(BlockItem item, World world, BlockPos pos, Direction side,
                                              PlayerEntity player, ItemStack stack) {
        return world.getBlockState(pos.offset(side)).isReplaceable();
    }
}
