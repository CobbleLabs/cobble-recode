package cobble.core;

/**
 * Millisecond stopwatch used by everything that fires on a delay: click
 * timers, place cooldowns, HUD fades.
 */
public final class Timer {
    private long mark;

    public void reset() {
        mark = Time.nowMillis();
    }

    /**
     * Banks part of the wait up front, so the mark reads as already old. Used
     * for first-engagement reaction: a freshly enabled module must not wait out
     * a full delay while already staring at someone.
     */
    public void elapse(long millis) {
        mark -= millis;
    }

    public long elapsed() {
        return Time.nowMillis() - mark;
    }

    public boolean elapsed(long millis) {
        return elapsed() >= millis;
    }

    public boolean tryReset(long millis) {
        if (!elapsed(millis)) {
            return false;
        }
        reset();
        return true;
    }
}
