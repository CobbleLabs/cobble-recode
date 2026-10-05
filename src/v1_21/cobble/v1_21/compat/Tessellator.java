package cobble.v1_21.compat;

/** 1.8.9's tessellator over {@link GlEmu}. */
public final class Tessellator {
    private static final Tessellator INSTANCE = new Tessellator();

    private final WorldRenderer renderer = new WorldRenderer();

    private Tessellator() {}

    public static Tessellator getInstance() {
        return INSTANCE;
    }

    public WorldRenderer getWorldRenderer() {
        return renderer;
    }

    public void draw() {
        GlEmu.end();
    }
}
