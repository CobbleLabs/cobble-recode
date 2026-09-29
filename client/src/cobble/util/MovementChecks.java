package cobble.util;

import net.minecraft.block.BlockLadder;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;

public final class MovementChecks {
    private MovementChecks() {
    }

    public static boolean isOnLadder(Minecraft var0, Entity var1) {
        BlockPos var2 = new BlockPos(MathHelper.floor_double(var1.posX), MathHelper.floor_double(var1.posY - 0.2F), MathHelper.floor_double(var1.posZ));
        boolean var3 = var0.theWorld.getBlockState(var2).getBlock() instanceof BlockLadder;
        return var3 && !var1.onGround;
    }
}
