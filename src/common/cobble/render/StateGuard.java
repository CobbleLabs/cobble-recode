package cobble.render;

import cobble.platform.Platform;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;

/**
 * Everything the overlay touches, captured before its draws and put back after
 * them. GlStateManager caches most of this on the Java side, so the restore has
 * to be exact or the game's next draw trusts a cache that is no longer true.
 */
public final class StateGuard {
    private static final int UNITS = 4;

    // LWJGL checks that a query buffer can hold the largest answer any pname has.
    private final IntBuffer query = BufferUtils.createIntBuffer(16);
    private final ByteBuffer flags = BufferUtils.createByteBuffer(16);

    private int program;
    private int vertexArray;
    private int arrayBuffer;
    private int elementBuffer;
    private int activeTexture;
    private int framebuffer;
    private final int[] viewport = new int[4];
    private final int[] scissorBox = new int[4];
    private int blendSrcRgb;
    private int blendDstRgb;
    private int blendSrcAlpha;
    private int blendDstAlpha;
    private int blendEquationRgb;
    private int blendEquationAlpha;
    private boolean depthWriteMask;
    private final boolean[] colorWriteMask = new boolean[4];
    private int matrixMode;
    private final FloatBuffer modelview = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer projection = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer textureMatrix = BufferUtils.createFloatBuffer(16);
    private final int[] textureBinding = new int[UNITS];
    private final int[] samplerBinding = new int[UNITS];
    private final boolean[] textureEnabled = new boolean[UNITS];
    private boolean blend;
    private boolean depthTest;
    private boolean scissorTest;
    private boolean cullFace;
    private boolean stencilTest;
    private boolean alphaTest;
    private boolean lighting;
    private boolean fixedFunction;

    public void capture() {
        Platform platform = Platform.get();
        program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        vertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        elementBuffer = GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        framebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        readInts(platform, GL11.GL_VIEWPORT, viewport);
        readInts(platform, GL11.GL_SCISSOR_BOX, scissorBox);
        blendSrcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        blendDstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        blendSrcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        blendDstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        blendEquationRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
        blendEquationAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
        depthWriteMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        platform.glGetBooleans(GL11.GL_COLOR_WRITEMASK, flags);
        for (int index = 0; index < 4; ++index) {
            colorWriteMask[index] = flags.get(index) != 0;
        }
        fixedFunction = platform.compatibilityProfile();
        if (fixedFunction) {
            matrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
            /* Read back rather than pushed. The stacks are only a few deep and the
             * game and Lunar push onto them too, so a push of ours could overflow and
             * the matching pop would then take one of theirs. The texture matrix is
             * the active unit's, so it is read before the loop below moves the unit. */
            readMatrix(platform, GL11.GL_MODELVIEW_MATRIX, modelview);
            readMatrix(platform, GL11.GL_PROJECTION_MATRIX, projection);
            readMatrix(platform, GL11.GL_TEXTURE_MATRIX, textureMatrix);
            alphaTest = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
            lighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
        }

        for (int unit = 0; unit < UNITS; ++unit) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
            textureBinding[unit] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            textureEnabled[unit] = fixedFunction && GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
            samplerBinding[unit] = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        }

        blend = GL11.glIsEnabled(GL11.GL_BLEND);
        depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        scissorTest = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        cullFace = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        stencilTest = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);

    }

    public void restore() {
        for (int unit = UNITS - 1; unit >= 0; --unit) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureBinding[unit]);
            GL33.glBindSampler(unit, samplerBinding[unit]);
            if (fixedFunction) {
                // Restored rather than cleared: the game's font and GUI draws sample through whatever unit it left on.
                set(GL11.GL_TEXTURE_2D, textureEnabled[unit]);
            }
        }
        GL13.glActiveTexture(activeTexture);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        GL30.glBindVertexArray(vertexArray);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuffer);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, elementBuffer);
        GL20.glUseProgram(program);

        GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        GL11.glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
        GL14.glBlendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha);
        if (blendEquationRgb == blendEquationAlpha) {
            GL14.glBlendEquation(blendEquationRgb);
        }
        GL11.glDepthMask(depthWriteMask);
        GL11.glColorMask(colorWriteMask[0], colorWriteMask[1], colorWriteMask[2], colorWriteMask[3]);

        set(GL11.GL_BLEND, blend);
        set(GL11.GL_DEPTH_TEST, depthTest);
        set(GL11.GL_SCISSOR_TEST, scissorTest);
        set(GL11.GL_CULL_FACE, cullFace);
        set(GL11.GL_STENCIL_TEST, stencilTest);
        Platform platform = Platform.get();
        if (fixedFunction) {
            GL11.glMatrixMode(GL11.GL_TEXTURE);
            platform.glLoadMatrix(textureMatrix);
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            platform.glLoadMatrix(projection);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            platform.glLoadMatrix(modelview);
            GL11.glMatrixMode(matrixMode);
            set(GL11.GL_ALPHA_TEST, alphaTest);
            set(GL11.GL_LIGHTING, lighting);
        }
        platform.resyncGlState();
    }

    private static void readMatrix(Platform platform, int name, FloatBuffer into) {
        into.clear();
        platform.glGetFloats(name, into);
    }

    private void readInts(Platform platform, int name, int[] into) {
        query.clear();
        platform.glGetIntegers(name, query);
        for (int index = 0; index < into.length; ++index) {
            into[index] = query.get(index);
        }
    }

    private static void set(int capability, boolean enabled) {
        if (enabled) {
            GL11.glEnable(capability);
        } else {
            GL11.glDisable(capability);
        }
    }
}
