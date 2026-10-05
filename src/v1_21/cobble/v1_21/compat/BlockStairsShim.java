package cobble.v1_21.compat;

import net.minecraft.block.StairsBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.util.math.Direction;

/** 1.8.9's BlockStairs properties on 1.21.11's StairsBlock. */
public final class BlockStairsShim {
    private BlockStairsShim() {}

    public static EnumProperty<Direction> FACING() {
        return StairsBlock.FACING;
    }

    public static EnumProperty<BlockHalf> HALF() {
        return StairsBlock.HALF;
    }
}
