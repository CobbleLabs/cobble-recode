package cobble.v1_21.compat;

/** 1.8.9's OpenGlHelper: its texture unit names and the lightmap, which the overlay does not use. */
public final class OpenGlHelper {
    public static final int defaultTexUnit = 0x84C0;
    public static final int lightmapTexUnit = 0x84C1;

    private OpenGlHelper() {}

    public static void setLightmapTextureCoords(int unit, float u, float v) {}
}
