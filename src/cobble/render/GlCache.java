package cobble.render;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;

/**
 * GlStateManager answers enable, bind and blend calls from its own cache
 * instead of asking the driver. Anything that changes that state behind its
 * back - a glPopAttrib, a raw glEnable, the overlay's own restore - leaves the
 * cache wrong, and the game's next call is then skipped as already done: its
 * text draws without blending or with the wrong texture, every glyph a solid
 * block. Reading the driver's state back into the cache puts that right.
 */
public final class GlCache {
    private static final FloatBuffer colour = BufferUtils.createFloatBuffer(16);
    private static final ByteBuffer mask = BufferUtils.createByteBuffer(16);

    private GlCache() {}

    /** Brings GlStateManager's cache back to what the driver actually has. Client thread only. */
    public static void resync() {
        read(GlStateManager.alphaState.alphaTest);
        read(GlStateManager.lightingState);
        for (GlStateManager.BooleanState light : GlStateManager.lightState) {
            read(light);
        }
        read(GlStateManager.colorMaterialState.colorMaterial);
        read(GlStateManager.blendState.blend);
        read(GlStateManager.depthState.depthTest);
        read(GlStateManager.fogState.fog);
        read(GlStateManager.cullState.cullFace);
        read(GlStateManager.polygonOffsetState.polygonOffsetFill);
        read(GlStateManager.polygonOffsetState.polygonOffsetLine);
        read(GlStateManager.colorLogicState.colorLogicOp);
        read(GlStateManager.normalizeState);
        read(GlStateManager.rescaleNormalState);

        GlStateManager.blendState.srcFactor = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        GlStateManager.blendState.dstFactor = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        GlStateManager.blendState.srcFactorAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        GlStateManager.blendState.dstFactorAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        GlStateManager.depthState.maskEnabled = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        GlStateManager.depthState.depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        GlStateManager.alphaState.func = GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC);
        GlStateManager.alphaState.ref = GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF);
        GlStateManager.cullState.mode = GL11.glGetInteger(GL11.GL_CULL_FACE_MODE);

        mask.clear();
        GL11.glGetBoolean(GL11.GL_COLOR_WRITEMASK, mask);
        GlStateManager.colorMaskState.red = mask.get(0) != 0;
        GlStateManager.colorMaskState.green = mask.get(1) != 0;
        GlStateManager.colorMaskState.blue = mask.get(2) != 0;
        GlStateManager.colorMaskState.alpha = mask.get(3) != 0;

        colour.clear();
        GL11.glGetFloat(GL11.GL_CURRENT_COLOR, colour);
        GlStateManager.colorState.red = colour.get(0);
        GlStateManager.colorState.green = colour.get(1);
        GlStateManager.colorState.blue = colour.get(2);
        GlStateManager.colorState.alpha = colour.get(3);

        /* Each unit's enable and binding is read on that unit, then the active one is put back. Past the
         * fixed-function units there is no GL_TEXTURE_2D enable to read. */
        int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int units = Math.min(GlStateManager.textureState.length, GL11.glGetInteger(GL13.GL_MAX_TEXTURE_UNITS));
        for (int unit = 0; unit < units; ++unit) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
            GlStateManager.textureState[unit].texture2DState.currentState = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
            GlStateManager.textureState[unit].textureName = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        }
        GL13.glActiveTexture(active);
        GlStateManager.activeTextureUnit = active - GL13.GL_TEXTURE0;
    }

    private static void read(GlStateManager.BooleanState state) {
        state.currentState = GL11.glIsEnabled(state.capability);
    }
}
