package cobble.v1_7.compat;

/**
 * Stands in for a 1.8.9 packet 1.7.10 has no counterpart of. Nothing ever is
 * one, so a test against it is always false, as the packet never arrives.
 */
public abstract class Absent {
    private Absent() {}
}
