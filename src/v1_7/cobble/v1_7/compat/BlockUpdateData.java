package cobble.v1_7.compat;

/** One change of a multi-block change packet, which 1.7.10 leaves packed in bytes. */
public record BlockUpdateData(BlockPos getPos, IBlockState getBlockState) {}
