package cobble.v1_21.compat;

/** 1.8.9's vertex builder over {@link GlEmu}: each vertex is handed over when it ends. */
public final class WorldRenderer {
    private double x;
    private double y;
    private double z;
    private double u;
    private double v;
    private float[] color;

    WorldRenderer() {}

    public void begin(int mode, VertexFormat format) {
        color = null;
        GlEmu.begin(mode);
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
        color = new float[] {red, green, blue, alpha};
        return this;
    }

    public WorldRenderer color(int red, int green, int blue, int alpha) {
        return color(red / 255.0f, green / 255.0f, blue / 255.0f, alpha / 255.0f);
    }

    public void endVertex() {
        GlEmu.vertex(x, y, z, color, u, v);
    }
}
