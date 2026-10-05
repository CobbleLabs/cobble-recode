package cobble.util.block;

import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.UnloadChunkS2CPacket;

/** 1.21.11's block and chunk packets, which unload a chunk with a packet of its own. */
public final class BlockChangeReader {
    private BlockChangeReader() {
    }

    public static void dispatchBlockChange(Packet<?> packet, BlockChangeReader$0 listener) {
        if (packet instanceof BlockUpdateS2CPacket update) {
            listener.onBlockChanged(update.getPos(), update.getState());
        } else if (packet instanceof ChunkDeltaUpdateS2CPacket delta) {
            delta.visitUpdates((pos, state) -> listener.onBlockChanged(pos.toImmutable(), state));
        } else if (packet instanceof ChunkDataS2CPacket data) {
            listener.onChunkLoaded(data.getChunkX(), data.getChunkZ());
        } else if (packet instanceof UnloadChunkS2CPacket unload) {
            listener.onChunkUnloaded(unload.pos().x, unload.pos().z);
        }
    }
}
