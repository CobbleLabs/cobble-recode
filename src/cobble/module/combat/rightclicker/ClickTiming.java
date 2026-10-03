package cobble.module.combat.rightclicker;

import java.util.SplittableRandom;

/**
 * The gaps between clicks. Normal is one rate per click. Extra clicks in bursts
 * and long slow stretches with a hiccup now and then. Extra+ drifts its rate
 * toward a target that moves every half a minute or so, tires as it goes and
 * recovers when you stop, and adds the small repeats and pauses of a hand.
 */
public final class ClickTiming {
    private static final int NORMAL = 0;
    private static final int EXTRA = 1;
    private static final long IDLE_RESET_MS = 1200;
    private static final double MAX_FATIGUE = 0.4;

    private final SplittableRandom random;

    /* Extra. */
    private long lastDelay;
    private boolean burst;
    private int burstLength;
    private int burstClicks;
    private boolean fastPhase = true;
    private int fastLength;
    private int fastClicks;
    private int slowLength;
    private int slowClicks;

    /* Extra+. */
    private boolean started;
    private double minimum;
    private double maximum;
    private double current;
    private double target;
    private double fatigue;
    private double repeatChance = 0.06;
    private double pauseOffset;
    private int clicksSinceVariation;
    private int burstLeft;
    private long lastClick;
    private long previousDelay;
    private long nextDrift;
    private long nextTarget;

    public ClickTiming(SplittableRandom random) {
        this.random = random;
    }

    public long nextDelay(int mode, int minimumCps, int maximumCps) {
        int span = maximumCps - minimumCps;
        int cps = Math.max(1, span <= 0 ? minimumCps : random.nextInt(span) + minimumCps + 1);
        if (mode == NORMAL) {
            return 1000 / cps;
        }
        if (mode == EXTRA) {
            return extra(cps);
        }
        configure(minimumCps, maximumCps);
        return extraPlus();
    }

    private long extra(int cps) {
        if (!burst) {
            lastDelay = 1000 / cps;
            if (random.nextInt(4) == 1) {
                burst = true;
                burstLength = 1 + random.nextInt(5);
            } else if (random.nextInt(10) != 1 && random.nextInt(10) == 1) {
                burst = true;
                burstLength = 5 + random.nextInt(10);
            }
        }
        if (burst && ++burstClicks >= burstLength) {
            burstClicks = 0;
            burst = false;
        }
        if (random.nextInt(48) % (fastPhase ? 6 : 10) == 0 && !burst) {
            lastDelay += random.nextInt(45) + 40L;
        }
        if (fastPhase) {
            if (++fastClicks >= fastLength) {
                slowLength = 75 + random.nextInt(125);
                fastPhase = false;
                fastClicks = 0;
            }
            return lastDelay + (random.nextInt(5) == 3 ? 50L : 25L);
        }
        if (++slowClicks >= slowLength) {
            fastPhase = true;
            fastLength = 7 + random.nextInt(8);
            slowClicks = 0;
        }
        return lastDelay;
    }

    /* Aimed half again above the set range, which fatigue then drags back down by up to two fifths. */
    private void configure(int minimumCps, int maximumCps) {
        double low = Math.max(0.5, Math.max(0, minimumCps) * 1.5);
        double high = Math.max(0.5, Math.max(Math.max(0, minimumCps), maximumCps) * 1.5);
        minimum = low;
        maximum = Math.max(low + 0.1, high);
        if (!started) {
            target = (minimum + maximum) * 0.5;
            current = clamp(target + normal() * 0.25);
        }
    }

    private long extraPlus() {
        long now = System.currentTimeMillis();
        if (!started) {
            started = true;
            nextDrift = now + duration(1200, 3200);
            nextTarget = now + duration(30000, 90000);
            target = clamp((minimum + maximum) * 0.5 + (random.nextDouble() * 2.0 - 1.0) * 0.75);
            lastClick = now;
        }
        long idle = now - lastClick;
        if (idle >= IDLE_RESET_MS) {
            fatigue = Math.max(0.0, fatigue - 0.004 * Math.min(5000L, idle) / 10.0);
            burstLeft = random.nextDouble() < 0.7 ? 2 + random.nextInt(4) : 0;
            vary();
        }
        if (++clicksSinceVariation > 80 + random.nextInt(120)) {
            vary();
            clicksSinceVariation = 0;
        }
        if (now >= nextDrift) {
            current += 0.25 * (target - current) + 0.45 * normal();
            if (random.nextDouble() < 0.03) {
                current += (random.nextDouble() - 0.5) * 1.2;
            }
            current = clamp(current);
            nextDrift = now + duration(1200, 3200);
        }
        if (now >= nextTarget) {
            target = clamp((minimum + maximum) * 0.5 + (random.nextDouble() * 2.0 - 1.0) * 0.75);
            nextTarget = now + duration(30000, 90000);
        }
        boolean bursting = burstLeft > 0;
        double cps = current;
        if (bursting) {
            cps *= 1.0 + 0.05 * (0.4 + 0.8 * random.nextDouble());
            --burstLeft;
        }
        cps = clamp(cps * (1.0 - Math.min(MAX_FATIGUE, fatigue)));
        int microJitter = 35 + random.nextInt(11) - 5;
        double delay = 1000.0 / cps * Math.exp(0.24 * noise()) + random.nextInt(Math.max(1, microJitter) + 1);
        if (bursting) {
            delay += random.nextInt(15);
        }
        if (previousDelay > 0 && random.nextDouble() < repeatChance) {
            delay = previousDelay + random.nextInt(7) - 3;
        }
        if (random.nextDouble() < 0.07) {
            delay *= 0.7 + random.nextDouble() * 0.2;
        }
        if (random.nextDouble() < Math.min(0.04 + 0.12 * fatigue + pauseOffset, 0.18)) {
            delay += 50 + random.nextInt(101);
        }
        fatigue = Math.min(MAX_FATIGUE, fatigue + 0.015);
        lastClick = now;
        previousDelay = (long) Math.max(1.0, Math.min(delay, Integer.MAX_VALUE));
        return previousDelay;
    }

    private void vary() {
        repeatChance = 0.03 + random.nextDouble() * 0.06;
        pauseOffset = random.nextDouble() * 0.04;
    }

    private long duration(long low, long high) {
        return low + random.nextLong(high - low + 1);
    }

    /* Mostly bell-shaped, with a flat spread or a wider bell one click in eight. */
    private double noise() {
        double roll = random.nextDouble();
        if (roll < 0.12) {
            return (random.nextDouble() - 0.5) * 3.0;
        }
        if (roll < 0.17) {
            return normal() * (1.4 + random.nextDouble() * 0.6);
        }
        return normal();
    }

    /* Six uniforms summed: close enough to a unit normal, and bounded. */
    private double normal() {
        double sum = 0.0;
        for (int sample = 0; sample < 6; ++sample) {
            sum += random.nextDouble();
        }
        return (sum - 3.0) * 1.22474487139;
    }

    private double clamp(double value) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
