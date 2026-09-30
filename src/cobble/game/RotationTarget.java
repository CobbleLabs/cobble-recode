package cobble.game;

public final class RotationTarget {
    public float yaw;
    public float pitch;

    /** Degrees the aim may travel in one tick before the step is clamped. */
    public float speed = 180.0f;
    /* 0 snaps as fast as `speed` allows, 1 barely moves. Shapes the approach
     * curve so the aim decelerates into the target instead of stopping dead. */
    public float smoothing = 0.3f;
    /* Extra whole mouse counts of noise per tick. Stays on the sensitivity
     * grid, so it looks like a human hand without breaking the GCD. */
    public int jitter;
    /** Send the rotation without moving the camera. See {@link RotationEngine}. */
    public boolean silent;
    public int priority;

    public RotationTarget() {}

    public RotationTarget(float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = pitch;
    }
}
