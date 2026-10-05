package cobble.v1_21.compat;

import net.minecraft.client.render.RenderTickCounter;

/**
 * 1.8.9's Timer as 1.21.11's RenderTickCounter. 1.21 takes its tick length
 * from the world's tick rate each frame; the tick length hook divides it by
 * {@link #speed}, which is what 1.8's timer speed did.
 */
public final class TimerShim {
    private static float speed = 1.0f;

    private TimerShim() {}

    public static float speed() {
        return speed;
    }

    public static float renderPartialTicks(RenderTickCounter counter) {
        return counter.getTickProgress(true);
    }

    public static float timerSpeed(RenderTickCounter counter) {
        return speed;
    }

    public static void timerSpeed(RenderTickCounter counter, float value) {
        speed = value;
    }
}
