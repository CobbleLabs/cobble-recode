package cobble.v1_21.compat;

import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.util.math.Direction;

/** 1.8.9's BlockDirectional on 1.21.11's HorizontalFacingBlock. */
public final class BlockDirectionalShim {
    private BlockDirectionalShim() {}

    public static EnumProperty<Direction> FACING() {
        return HorizontalFacingBlock.FACING;
    }
}
