package cobble.v1_21.compat;

/** 1.8.9's RenderHelper; the overlay has no lighting to switch. */
public final class RenderHelper {
    private RenderHelper() {}

    public static void enableStandardItemLighting() {}

    public static void disableStandardItemLighting() {}
}
