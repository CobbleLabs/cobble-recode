package cobble.v1_21.compat;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * LWJGL's GL11, for the legacy code only, over {@link GlEmu}: 1.21.11's core
 * context has no fixed-function pipeline to call. State the emulator does
 * not model is accepted and dropped, and reads of it come back empty.
 */
public final class GL11 {
    private GL11() {}

    public static void glBegin(int mode) {
        GlEmu.begin(mode);
    }

    public static void glEnd() {
        GlEmu.end();
    }

    public static void glVertex2d(double x, double y) {
        GlEmu.vertex(x, y, 0.0, null, 0.0, 0.0);
    }

    public static void glVertex2f(float x, float y) {
        GlEmu.vertex(x, y, 0.0, null, 0.0, 0.0);
    }

    public static void glVertex3d(double x, double y, double z) {
        GlEmu.vertex(x, y, z, null, 0.0, 0.0);
    }

    /* Texture coordinates go with the next vertex in GL; the emulator keeps no textured immediate mode. */
    public static void glTexCoord2f(float u, float v) {}

    public static void glColor4f(float red, float green, float blue, float alpha) {
        GlEmu.color(red, green, blue, alpha);
    }

    public static void glLineWidth(float width) {
        GlEmu.lineWidth(width);
    }

    public static void glPointSize(float size) {}

    public static void glEnable(int capability) {
        GlEmu.enable(capability);
    }

    public static void glDisable(int capability) {
        GlEmu.disable(capability);
    }

    public static boolean glIsEnabled(int capability) {
        return GlEmu.isEnabled(capability);
    }

    public static void glPushMatrix() {
        GlEmu.pushMatrix();
    }

    public static void glPopMatrix() {
        GlEmu.popMatrix();
    }

    public static void glMatrixMode(int mode) {
        GlEmu.matrixMode(mode);
    }

    public static void glLoadMatrix(FloatBuffer matrix) {
        float[] values = new float[16];
        matrix.get(matrix.position(), values);
        GlEmu.loadMatrix(values);
    }

    public static void glTranslated(double x, double y, double z) {
        GlEmu.translate(x, y, z);
    }

    public static void glTranslatef(float x, float y, float z) {
        GlEmu.translate(x, y, z);
    }

    public static void glScaled(double x, double y, double z) {
        GlEmu.scale(x, y, z);
    }

    public static void glRotated(double angle, double x, double y, double z) {
        GlEmu.rotate(angle, x, y, z);
    }

    public static void glPushAttrib(int mask) {
        GlEmu.pushAttributes();
    }

    public static void glPopAttrib() {
        GlEmu.popAttributes();
    }

    public static void glBlendFunc(int source, int destination) {}

    public static void glDepthFunc(int function) {}

    public static void glDepthMask(boolean write) {}

    public static void glColorMask(boolean red, boolean green, boolean blue, boolean alpha) {}

    public static void glShadeModel(int model) {}

    public static void glHint(int target, int hint) {}

    public static void glPolygonOffset(float factor, float units) {}

    public static void glStencilFunc(int function, int reference, int mask) {}

    public static void glStencilMask(int mask) {}

    public static void glStencilOp(int fail, int depthFail, int pass) {}

    public static void glTexEnvi(int target, int name, int value) {}

    public static void glTexParameteri(int target, int name, int value) {}

    public static void glClear(int mask) {}

    public static void glDeleteTextures(int texture) {
        org.lwjgl.opengl.GL11.glDeleteTextures(texture);
    }

    public static int glGetError() {
        return 0;
    }

    public static boolean glGetBoolean(int name) {
        return GlEmu.isEnabled(name);
    }

    public static void glGetBoolean(int name, ByteBuffer into) {
        into.put(into.position(), (byte) (GlEmu.isEnabled(name) ? 1 : 0));
    }

    public static float glGetFloat(int name) {
        return name == GlEmu.LINE_WIDTH ? GlEmu.lineWidth() : 0.0f;
    }

    public static void glGetFloat(int name, FloatBuffer into) {
        float[] values = switch (name) {
            case GlEmu.MODELVIEW_MATRIX -> GlEmu.modelview();
            case GlEmu.PROJECTION_MATRIX -> GlEmu.projection();
            case GlEmu.CURRENT_COLOR -> GlEmu.color();
            case GlEmu.LINE_WIDTH -> new float[] {GlEmu.lineWidth()};
            default -> new float[0];
        };
        into.put(into.position(), values);
    }

    public static int glGetInteger(int name) {
        return name == GlEmu.TEXTURE_BINDING_2D ? GlEmu.boundTexture() : 0;
    }

    public static void glGetInteger(int name, IntBuffer into) {
        if (name == GlEmu.VIEWPORT) {
            into.put(into.position(), GlEmu.viewport());
        } else {
            into.put(into.position(), glGetInteger(name));
        }
    }
}
