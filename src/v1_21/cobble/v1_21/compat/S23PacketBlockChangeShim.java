package cobble.v1_21.compat;

import net.minecraft.block.BlockState;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.util.math.BlockPos;

/** 1.8.9's S23PacketBlockChange on 1.21.11's BlockUpdateS2CPacket. */
public final class S23PacketBlockChangeShim {
    private S23PacketBlockChangeShim() {}

    public static BlockPos getBlockPosition(BlockUpdateS2CPacket packet) {
        return packet.getPos();
    }

    public static BlockState getBlockState(BlockUpdateS2CPacket packet) {
        return packet.getState();
    }
}
