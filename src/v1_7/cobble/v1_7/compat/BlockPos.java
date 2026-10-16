package cobble.v1_7.compat;

import net.minecraft.entity.Entity;
import net.minecraft.util.Vec3;

/** 1.8.9's block position, the part of it the legacy client code calls. */
public class BlockPos extends Vec3i {
    public static final BlockPos ORIGIN = new BlockPos(0, 0, 0);

    public BlockPos(int x, int y, int z) {
        super(x, y, z);
    }

    public BlockPos(double x, double y, double z) {
        super(x, y, z);
    }

    public BlockPos(Entity entity) {
        this(entity.posX, EntityShim.posY(entity), entity.posZ);
    }

    public BlockPos(Vec3 vector) {
        this(vector.xCoord, vector.yCoord, vector.zCoord);
    }

    public BlockPos(Vec3i vector) {
        this(vector.getX(), vector.getY(), vector.getZ());
    }

    public BlockPos add(int x, int y, int z) {
        return x == 0 && y == 0 && z == 0 ? this : new BlockPos(getX() + x, getY() + y, getZ() + z);
    }

    public BlockPos add(Vec3i vector) {
        return add(vector.getX(), vector.getY(), vector.getZ());
    }

    public BlockPos up() {
        return up(1);
    }

    public BlockPos up(int distance) {
        return add(0, distance, 0);
    }

    public BlockPos down() {
        return add(0, -1, 0);
    }

    public BlockPos offset(EnumFacing facing) {
        return add(facing.getFrontOffsetX(), facing.getFrontOffsetY(), facing.getFrontOffsetZ());
    }

    public double distanceSq(Vec3i to) {
        double x = getX() - to.getX();
        double y = getY() - to.getY();
        double z = getZ() - to.getZ();
        return x * x + y * y + z * z;
    }

    public double distanceSqToCenter(double x, double y, double z) {
        double dx = getX() + 0.5 - x;
        double dy = getY() + 0.5 - y;
        double dz = getZ() + 0.5 - z;
        return dx * dx + dy * dy + dz * dz;
    }

    /* 1.8.9's packing: 26 bits of x, 12 of y, 26 of z. */
    public long toLong() {
        return ((long) getX() & 0x3FFFFFFL) << 38 | ((long) getY() & 0xFFFL) << 26 | (long) getZ() & 0x3FFFFFFL;
    }

    public static final class MutableBlockPos extends BlockPos {
        private int x;
        private int y;
        private int z;

        public MutableBlockPos() {
            super(0, 0, 0);
        }

        public MutableBlockPos set(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
            return this;
        }

        @Override
        public int getX() {
            return x;
        }

        @Override
        public int getY() {
            return y;
        }

        @Override
        public int getZ() {
            return z;
        }
    }
}
