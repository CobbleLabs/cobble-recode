package cobble.v1_21.compat;

import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

/** 1.8.9's Chunk on 1.21.11's WorldChunk. */
public final class ChunkShim {
    private ChunkShim() {}

    public static int xPosition(WorldChunk chunk) {
        return chunk.getPos().x;
    }

    public static int zPosition(WorldChunk chunk) {
        return chunk.getPos().z;
    }

    public static ChunkSection[] getBlockStorageArray(WorldChunk chunk) {
        return chunk.getSectionArray();
    }
}
