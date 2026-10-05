package cobble.v1_21.compat;

import java.nio.FloatBuffer;
import org.lwjgl.BufferUtils;

/** 1.8.9's ActiveRenderInfo: the world pass's matrices, as the legacy code reads them to project points. */
public final class ActiveRenderInfo {
    public static final FloatBuffer MODELVIEW = BufferUtils.createFloatBuffer(16);
    public static final FloatBuffer PROJECTION = BufferUtils.createFloatBuffer(16);

    private ActiveRenderInfo() {}

    static void set(float[] modelview, float[] projection) {
        MODELVIEW.clear();
        MODELVIEW.put(modelview).flip();
        PROJECTION.clear();
        PROJECTION.put(projection).flip();
    }
}
