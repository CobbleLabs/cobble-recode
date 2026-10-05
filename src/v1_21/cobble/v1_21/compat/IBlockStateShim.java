package cobble.v1_21.compat;

import net.minecraft.block.BlockState;
import net.minecraft.state.property.Property;

/** 1.8.9's IBlockState on 1.21.11's BlockState. */
public final class IBlockStateShim {
    private IBlockStateShim() {}

    public static Comparable<?> getValue(BlockState state, Property<?> property) {
        return state.get(property);
    }
}
