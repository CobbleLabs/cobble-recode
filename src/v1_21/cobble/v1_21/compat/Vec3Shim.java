package cobble.v1_21.compat;

import net.minecraft.util.math.Vec3d;

/** 1.8.9's Vec3 on 1.21.11's Vec3d. */
public final class Vec3Shim {
    private Vec3Shim() {}

    public static double xCoord(Vec3d vec) {
        return vec.x;
    }

    public static double yCoord(Vec3d vec) {
        return vec.y;
    }

    public static double zCoord(Vec3d vec) {
        return vec.z;
    }

    public static Vec3d addVector(Vec3d vec, double x, double y, double z) {
        return vec.add(x, y, z);
    }

    public static double lengthVector(Vec3d vec) {
        return vec.length();
    }

    public static double squareDistanceTo(Vec3d vec, Vec3d other) {
        return vec.squaredDistanceTo(other);
    }
}
