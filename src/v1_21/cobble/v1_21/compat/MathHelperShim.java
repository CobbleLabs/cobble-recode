package cobble.v1_21.compat;

import net.minecraft.util.math.MathHelper;

/** 1.8.9's MathHelper on 1.21.11's. */
public final class MathHelperShim {
    private MathHelperShim() {}

    public static int ceiling_float_int(float value) {
        return MathHelper.ceil(value);
    }

    public static double clamp_double(double value, double min, double max) {
        return MathHelper.clamp(value, min, max);
    }

    public static float clamp_float(float value, float min, float max) {
        return MathHelper.clamp(value, min, max);
    }

    public static int clamp_int(int value, int min, int max) {
        return MathHelper.clamp(value, min, max);
    }

    public static float sin(float value) {
        return MathHelper.sin(value);
    }

    public static float cos(float value) {
        return MathHelper.cos(value);
    }

    public static int floor_double(double value) {
        return MathHelper.floor(value);
    }

    public static float sqrt_double(double value) {
        return (float) Math.sqrt(value);
    }

    public static float sqrt_float(float value) {
        return MathHelper.sqrt(value);
    }

    public static double wrapAngleTo180_double(double angle) {
        return MathHelper.wrapDegrees(angle);
    }

    public static float wrapAngleTo180_float(float angle) {
        return MathHelper.wrapDegrees(angle);
    }
}
