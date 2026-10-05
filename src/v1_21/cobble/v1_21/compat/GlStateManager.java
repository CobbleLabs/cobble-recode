package cobble.v1_21.compat;

/** 1.8.9's GlStateManager, for the legacy code only, over {@link GlEmu}. */
public final class GlStateManager {
    private static final int TEXTURE_UNIT_0 = 0x84C0;

    private GlStateManager() {}

    public static void pushMatrix() {
        GlEmu.pushMatrix();
    }

    public static void popMatrix() {
        GlEmu.popMatrix();
    }

    public static void matrixMode(int mode) {
        GlEmu.matrixMode(mode);
    }

    public static void loadIdentity() {
        GlEmu.loadIdentity();
    }

    public static void ortho(double left, double right, double bottom, double top, double near, double far) {
        GlEmu.multMatrix(GlEmu.ortho(left, right, bottom, top, near, far));
    }

    public static void translate(double x, double y, double z) {
        GlEmu.translate(x, y, z);
    }

    public static void translate(float x, float y, float z) {
        GlEmu.translate(x, y, z);
    }

    public static void scale(double x, double y, double z) {
        GlEmu.scale(x, y, z);
    }

    public static void scale(float x, float y, float z) {
        GlEmu.scale(x, y, z);
    }

    public static void rotate(float angle, float x, float y, float z) {
        GlEmu.rotate(angle, x, y, z);
    }

    public static void color(float red, float green, float blue, float alpha) {
        GlEmu.color(red, green, blue, alpha);
    }

    public static void pushAttrib() {
        GlEmu.pushAttributes();
    }

    public static void popAttrib() {
        GlEmu.popAttributes();
    }

    public static void enableTexture2D() {
        GlEmu.enable(GlEmu.TEXTURE_2D);
    }

    public static void disableTexture2D() {
        GlEmu.disable(GlEmu.TEXTURE_2D);
    }

    public static void bindTexture(int texture) {
        GlEmu.bindTexture(texture);
    }

    public static void setActiveTexture(int unit) {
        GlEmu.activeTexture(unit - TEXTURE_UNIT_0);
    }

    /* Blending is always on in the overlay, and depth, culling, lighting and the alpha test do not apply to it. */
    public static void enableBlend() {}

    public static void disableBlend() {}

    public static void blendFunc(int source, int destination) {}

    public static void tryBlendFuncSeparate(int source, int destination, int sourceAlpha, int destinationAlpha) {}

    public static void enableDepth() {}

    public static void disableDepth() {}

    public static void depthMask(boolean write) {}

    public static void enableCull() {}

    public static void disableCull() {}

    public static void enableAlpha() {}

    public static void disableAlpha() {}

    public static void alphaFunc(int function, float reference) {}

    public static void enableLighting() {}

    public static void disableLighting() {}

    public static void enableRescaleNormal() {}

    public static void disableRescaleNormal() {}

    public static void clear(int mask) {}
}
