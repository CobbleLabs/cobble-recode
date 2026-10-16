package cobble.v1_7.compat;

/** 1.8.9's tessellator: a WorldRenderer to fill and a draw, over 1.7.10's single tessellator. */
public final class Tessellator {
    private static final Tessellator INSTANCE = new Tessellator();

    private final WorldRenderer renderer = new WorldRenderer(net.minecraft.client.renderer.Tessellator.instance);

    private Tessellator() {}

    public static Tessellator getInstance() {
        return INSTANCE;
    }

    public WorldRenderer getWorldRenderer() {
        return renderer;
    }

    public void draw() {
        net.minecraft.client.renderer.Tessellator.instance.draw();
    }
}
