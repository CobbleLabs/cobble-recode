package cobble.v1_7.compat;

/**
 * 1.8.9's vertex builder. 1.7.10's tessellator takes the colour before the
 * vertex and the position last, so pos and tex are held until endVertex.
 */
public final class WorldRenderer {
    private final net.minecraft.client.renderer.Tessellator tessellator;
    private boolean textured;
    private double x;
    private double y;
    private double z;
    private double u;
    private double v;

    WorldRenderer(net.minecraft.client.renderer.Tessellator tessellator) {
        this.tessellator = tessellator;
    }

    public void begin(int mode, VertexFormat format) {
        textured = format.textured;
        tessellator.startDrawing(mode);
    }

    public WorldRenderer pos(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
        return this;
    }

    public WorldRenderer tex(double u, double v) {
        this.u = u;
        this.v = v;
        return this;
    }

    public WorldRenderer color(float red, float green, float blue, float alpha) {
        tessellator.setColorRGBA_F(red, green, blue, alpha);
        return this;
    }

    public WorldRenderer color(int red, int green, int blue, int alpha) {
        tessellator.setColorRGBA(red, green, blue, alpha);
        return this;
    }

    public void endVertex() {
        if (textured) {
            tessellator.addVertexWithUV(x, y, z, u, v);
        } else {
            tessellator.addVertex(x, y, z);
        }
    }
}
