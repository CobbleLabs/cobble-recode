package cobble.v1_21.compat;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;

/** 1.8.9's BlockPos on 1.21.11's; 1.8 floored its double constructors, which 1.21 names ofFloored. */
public final class BlockPosShim {
    private BlockPosShim() {}

    public static BlockPos create(double x, double y, double z) {
        return BlockPos.ofFloored(x, y, z);
    }

    public static BlockPos create(Entity entity) {
        return entity.getBlockPos();
    }

    public static BlockPos create(Vec3d vec) {
        return BlockPos.ofFloored(vec);
    }

    public static BlockPos create(Vec3i vec) {
        return new BlockPos(vec);
    }

    public static long toLong(BlockPos pos) {
        return pos.asLong();
    }
}
