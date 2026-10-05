package cobble.v1_21.compat;

import net.minecraft.util.math.Vec3i;

/** 1.8.9's Vec3i on 1.21.11's. */
public final class Vec3iShim {
    private Vec3iShim() {}

    public static double distanceSq(Vec3i vec, Vec3i other) {
        return vec.getSquaredDistance(other);
    }

    public static double distanceSqToCenter(Vec3i vec, double x, double y, double z) {
        return vec.getSquaredDistanceFromCenter(x, y, z);
    }
}
