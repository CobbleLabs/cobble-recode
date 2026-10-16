package cobble.v1_7.compat;

import net.minecraft.block.Block;

record BlockState(Block getBlock, int metadata) implements IBlockState {}
