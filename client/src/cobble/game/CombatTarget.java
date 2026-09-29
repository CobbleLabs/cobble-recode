package cobble.game;

/**
 * Whoever a combat module is currently swinging at.
 *
 * <p>Several modules pick a target and none of them owns the answer, so the
 * target HUD would otherwise have to know about every one of them. They publish
 * an entity id instead and the last publish wins for as long as it stays fresh.
 * An id is resolved against the frame being drawn, so a target that despawned
 * simply stops resolving.
 */
public final class CombatTarget {
    private static final double MAX_AGE_SECONDS = 0.35;

    private static int id = -1;
    private static double at;

    private CombatTarget() {}

    public static void publish(int entityId, double atSeconds) {
        id = entityId;
        at = atSeconds;
    }

    public static void clear() {
        id = -1;
    }

    /** The published id while it is fresh, or -1. */
    public static int current(double nowSeconds) {
        return id < 0 || nowSeconds - at > MAX_AGE_SECONDS ? -1 : id;
    }
}
