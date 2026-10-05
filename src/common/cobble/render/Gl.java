package cobble.render;

import cobble.core.Log;
import cobble.platform.Platform;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;

public final class Gl {
    private static final String SCOPE = "gl";

    private Gl() {}

    /** Logs any pending GL error with a caller tag. */
    public static void checkError(String stage) {
        int error = GL11.glGetError();
        for (int guard = 0; error != GL11.GL_NO_ERROR && guard < 8; ++guard) {
            Log.error(SCOPE, "GL error 0x%04X during %s", error, stage);
            error = GL11.glGetError();
        }
    }

    /*
     * The fixed-function matrix stacks are live state on the legacy versions,
     * and Lunar's sprite pass scales coordinates through the texture matrix;
     * the game also leaves client arrays enabled, and a stray enabled array
     * makes the fetch read the wrong pointer. The state guard captured all of
     * it before this point. A core context has none of it.
     */
    static void neutraliseFixedFunction(int width, int height) {
        if (!Platform.get().compatibilityProfile()) {
            return;
        }
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glLoadIdentity();
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        GL11.glOrtho(0.0, width, height, 0.0, -1.0, 1.0);
        GL11.glMatrixMode(GL11.GL_TEXTURE);
        GL11.glLoadIdentity();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        disableClientArrays(3);
    }

    private static void disableClientArrays(int units) {
        for (int unit = 0; unit < units; ++unit) {
            GL13.glClientActiveTexture(GL13.GL_TEXTURE0 + unit);
            GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
            GL11.glDisableClientState(GL11.GL_COLOR_ARRAY);
            GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        }
        GL13.glClientActiveTexture(GL13.GL_TEXTURE0);
    }

    /**
     * Whether a texture name still holds the image this client gave it. The game
     * shares the context and can wipe our objects, after which it may hand the
     * same names out for its own; one of those must never be drawn or deleted.
     */
    static boolean holdsTexture(int texture, int width, int height, int internalFormat) {
        if (texture == 0 || !GL11.glIsTexture(texture)) {
            return false;
        }
        int bound = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        boolean same = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH) == width
                && GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT) == height
                && GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT)
                == internalFormat;
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, bound);
        return same;
    }

    /** Uploads a single-channel distance field into {@code texture}, creating it when zero, and returns the name. */
    static int uploadField(int texture, int width, int height, byte[] pixels) {
        ByteBuffer data = BufferUtils.createByteBuffer(pixels.length);
        data.put(pixels).flip();
        return upload(texture == 0 ? GL11.glGenTextures() : texture, width, height, GL30.GL_R8, GL11.GL_RED, data);
    }

    /** A new linear-filtered RGBA texture holding {@code data}, rows packed tight. */
    public static int uploadRgba(int width, int height, ByteBuffer data) {
        return upload(GL11.glGenTextures(), width, height, GL11.GL_RGBA8, GL11.GL_RGBA, data);
    }

    /** Replaces a rectangle of an RGBA texture with packed ARGB pixels, the layout BufferedImage uses. */
    public static void updateRegion(int texture, int x, int y, int width, int height, int[] argb) {
        IntBuffer data = BufferUtils.createIntBuffer(argb.length);
        data.put(argb).flip();
        int bound = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        int[] unpack = neutralUnpack();
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, x, y, width, height, GL12.GL_BGRA,
                GL12.GL_UNSIGNED_INT_8_8_8_8_REV, data);
        restoreUnpack(unpack);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, bound);
    }

    /*
     * Every upload binds its texture directly and puts the previous binding back
     * the same way, so GlStateManager's idea of what is bound stays true. Going
     * through its bindTexture instead, a stale cache could skip the bind and the
     * upload would land in whatever texture was really bound - the game's font,
     * as often as not.
     */
    private static int upload(int texture, int width, int height, int internalFormat, int format, ByteBuffer data) {
        int bound = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        int[] unpack = neutralUnpack();
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internalFormat, width, height, 0, format, GL11.GL_UNSIGNED_BYTE,
                data);
        restoreUnpack(unpack);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, bound);
        return texture;
    }

    /*
     * Minecraft's own uploads leave the row length and skip offsets set, and with
     * a pixel unpack buffer bound the data would be read as an offset into that
     * buffer, so the transfer state is neutralised around each upload.
     */
    private static int[] neutralUnpack() {
        int[] saved = {
            GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT),
            GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH),
            GL11.glGetInteger(GL11.GL_UNPACK_SKIP_ROWS),
            GL11.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS),
            GL11.glGetInteger(GL11.GL_UNPACK_SWAP_BYTES),
            GL11.glGetInteger(GL11.GL_UNPACK_LSB_FIRST),
            GL11.glGetInteger(GL12.GL_UNPACK_IMAGE_HEIGHT),
            GL11.glGetInteger(GL12.GL_UNPACK_SKIP_IMAGES),
            GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING),
        };
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SWAP_BYTES, GL11.GL_FALSE);
        GL11.glPixelStorei(GL11.GL_UNPACK_LSB_FIRST, GL11.GL_FALSE);
        GL11.glPixelStorei(GL12.GL_UNPACK_IMAGE_HEIGHT, 0);
        GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_IMAGES, 0);
        if (saved[8] != 0) {
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        }
        return saved;
    }

    private static void restoreUnpack(int[] saved) {
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, saved[0]);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, saved[1]);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, saved[2]);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, saved[3]);
        GL11.glPixelStorei(GL11.GL_UNPACK_SWAP_BYTES, saved[4]);
        GL11.glPixelStorei(GL11.GL_UNPACK_LSB_FIRST, saved[5]);
        GL11.glPixelStorei(GL12.GL_UNPACK_IMAGE_HEIGHT, saved[6]);
        GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_IMAGES, saved[7]);
        if (saved[8] != 0) {
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, saved[8]);
        }
    }
}
