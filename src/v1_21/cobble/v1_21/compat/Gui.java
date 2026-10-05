package cobble.v1_21.compat;

/** 1.8.9's Gui drawing statics over {@link GlEmu}. */
public final class Gui {
    private Gui() {}

    public static void drawModalRectWithCustomSizedTexture(int x, int y, float u, float v, int width, int height,
                                                           float textureWidth, float textureHeight) {
        GlEmu.enable(GlEmu.TEXTURE_2D);
        GlEmu.begin(GlEmu.QUADS);
        GlEmu.vertex(x, y + height, 0, null, u / textureWidth, (v + height) / textureHeight);
        GlEmu.vertex(x + width, y + height, 0, null, (u + width) / textureWidth, (v + height) / textureHeight);
        GlEmu.vertex(x + width, y, 0, null, (u + width) / textureWidth, v / textureHeight);
        GlEmu.vertex(x, y, 0, null, u / textureWidth, v / textureHeight);
        GlEmu.end();
    }
}
