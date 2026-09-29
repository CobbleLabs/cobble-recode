package cobble.util;

import net.minecraft.entity.Entity;

public final class MotionUtils {
    private MotionUtils() {
    }

    public static double getHorizontalSpeed(Entity var0) {
        return Math.hypot(var0.motionX, var0.motionZ);
    }
}
