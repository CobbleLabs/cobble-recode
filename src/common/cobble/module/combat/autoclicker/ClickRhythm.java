package cobble.module.combat.autoclicker;

import java.util.SplittableRandom;

/**
 * The gaps between left clicks, from one press to the next, and how long each
 * press is held.
 *
 * <p>The rate wanders through the set range the way a hand's does: it eases
 * toward a target drawn again every second or two, so any few seconds of
 * clicking average out inside the range. Around that rate each gap is a
 * timekeeper's interval plus the difference of two motor delays, Wing and
 * Kristofferson's model of tapping, which is what makes a short gap tend to
 * follow a long one. The timekeeper leans slightly long, the way tapping
 * intervals do. Randomization scales that jitter, never the range.
 */
public final class ClickRhythm {
    private static final double RETARGET_MIN_MS = 800.0;
    private static final double RETARGET_MAX_MS = 2500.0;
    private static final double EASE_MS = 600.0;
    /* Shares of the current base interval, at full randomization. */
    private static final double CLOCK_SPREAD = 0.11;
    private static final double CLOCK_SKEW = 0.05;
    private static final double MOTOR_SPREAD = 0.06;
    private static final double SHORTEST = 0.6;
    private static final double LONGEST = 1.6;
    private static final double HOLD_SHARE = 0.40;

    private final SplittableRandom random;
    private boolean started;
    private double tempo;
    private double target;
    private double retargetIn;
    private double motorDelay;

    public ClickRhythm(SplittableRandom random) {
        this.random = random;
    }

    /** Forgets the rate, so the next run of clicks starts somewhere fresh in the range. */
    public void reset() {
        started = false;
    }

    /** Milliseconds from this press to the next; {@code randomization} runs from 0 to 1. */
    public double nextInterval(double minCps, double maxCps, double randomization) {
        double low = Math.min(minCps, maxCps);
        double high = Math.max(minCps, maxCps);
        if (!started) {
            started = true;
            tempo = uniform(low, high);
            target = tempo;
            retargetIn = uniform(RETARGET_MIN_MS, RETARGET_MAX_MS);
            motorDelay = normal() * MOTOR_SPREAD * randomization * 1000.0 / tempo;
        }
        /* The range can be moved while clicking. */
        tempo = clamp(tempo, low, high);
        target = clamp(target, low, high);

        double base = 1000.0 / tempo;
        double skew = CLOCK_SKEW * randomization * base;
        double clock = base - skew + normal() * CLOCK_SPREAD * randomization * base + exponential(skew);
        double nextMotorDelay = normal() * MOTOR_SPREAD * randomization * base;
        double interval = clamp(clock + nextMotorDelay - motorDelay, base * SHORTEST, base * LONGEST);
        motorDelay = nextMotorDelay;

        retargetIn -= interval;
        if (retargetIn <= 0.0) {
            target = uniform(low, high);
            retargetIn = uniform(RETARGET_MIN_MS, RETARGET_MAX_MS);
        }
        tempo += (target - tempo) * (1.0 - Math.exp(-interval / EASE_MS));
        return interval;
    }

    /** How long the press is held: a share of its interval, let go well before the next one. */
    public double holdFor(double interval, double randomization) {
        return interval * clamp(HOLD_SHARE + normal() * 0.05 * randomization, 0.28, 0.52);
    }

    private double normal() {
        double u1 = Math.max(1e-9, random.nextDouble());
        return Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * random.nextDouble());
    }

    private double exponential(double mean) {
        return -mean * Math.log(Math.max(1e-9, random.nextDouble()));
    }

    private double uniform(double low, double high) {
        return low + random.nextDouble() * (high - low);
    }

    private static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }
}
