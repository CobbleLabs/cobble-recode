package cobble.v1_21.compat;

/**
 * The legacy code's own shader-drawn shapes, which the retarget rules send
 * here: on 1.21.11 they are the overlay renderer's rounded rectangles.
 */
public final class LegacyDraw {
    private LegacyDraw() {}

    public static void roundedRect(float x, float y, float width, float height, float radius, int argb) {
        GlEmu.roundedRect(x, y, width, height, radius, argb);
    }

    /* The renderer rounds every corner alike; a shape with any corner rounded gets all four. */
    public static void roundedRect(float x, float y, float width, float height, float radius, int argb,
                                   boolean topLeft, boolean topRight, boolean bottomRight, boolean bottomLeft) {
        boolean rounded = topLeft || topRight || bottomRight || bottomLeft;
        GlEmu.roundedRect(x, y, width, height, rounded ? radius : 0.0f, argb);
    }
}
