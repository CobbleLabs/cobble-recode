package cobble.v1_7.compat;

import net.minecraft.block.Block;

/** 1.8.9's block state, which on 1.7.10 is a block and its metadata. */
public interface IBlockState {
    Block getBlock();

    int metadata();

    @SuppressWarnings("rawtypes")
    default Comparable getValue(IProperty property) {
        return property.read(this);
    }
}
