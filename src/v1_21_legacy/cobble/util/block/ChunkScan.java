package cobble.util.block;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

/** 1.21.11's chunks, whose sections start below y 0. */
public final class ChunkScan {
    private final int sectionCount;
    private final Set<BlockPos> primaryMatches;
    private final Set<BlockPos> secondaryMatches;

    private ChunkScan(int sectionCount, Set<BlockPos> primaryMatches, Set<BlockPos> secondaryMatches) {
        this.sectionCount = sectionCount;
        this.primaryMatches = primaryMatches;
        this.secondaryMatches = secondaryMatches;
    }

    public static ChunkScan scanChunk(WorldChunk chunk, BlockFilter primary, BlockFilter secondary) {
        Set<BlockPos> primaryMatches = ConcurrentHashMap.newKeySet();
        Set<BlockPos> secondaryMatches = ConcurrentHashMap.newKeySet();
        ChunkSection[] sections = chunk.getSectionArray();
        int scanned = 0;
        int baseX = chunk.getPos().getStartX();
        int baseZ = chunk.getPos().getStartZ();
        for (int index = 0; index < sections.length; ++index) {
            ChunkSection section = sections[index];
            if (section == null || section.isEmpty()) {
                continue;
            }
            ++scanned;
            int baseY = chunk.sectionIndexToCoord(index) << 4;
            for (int i = 0; i < 4096; ++i) {
                int x = i & 15;
                int z = i >>> 4 & 15;
                int y = i >>> 8;
                BlockState state = section.getBlockState(x, y, z);
                BlockPos pos = new BlockPos(baseX + x, baseY + y, baseZ + z);
                collectMatches(primaryMatches, primary, pos, state);
                collectMatches(secondaryMatches, secondary, pos, state);
            }
        }
        return new ChunkScan(Math.max(scanned, 1), primaryMatches, secondaryMatches);
    }

    private static void collectMatches(Set<BlockPos> matches, BlockFilter filter, BlockPos pos, BlockState state) {
        if (filter != null && filter.matches(state) && filter.matchesAt(pos, state)) {
            matches.add(pos);
        }
    }

    public int getSectionCount() {
        return sectionCount;
    }

    public Set<BlockPos> SQIOn() {
        return primaryMatches;
    }

    public Set<BlockPos> getSecondaryMatches() {
        return secondaryMatches;
    }
}
