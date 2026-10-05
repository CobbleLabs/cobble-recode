package cobble.v1_21.compat;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/** LWJGL 2's GLU.gluProject, which is plain arithmetic. */
public final class GLU {
    private GLU() {}

    public static boolean gluProject(float x, float y, float z, FloatBuffer modelview, FloatBuffer projection,
                                     IntBuffer viewport, FloatBuffer out) {
        float[] eye = transform(modelview, x, y, z, 1.0f);
        float[] clip = transform(projection, eye[0], eye[1], eye[2], eye[3]);
        if (clip[3] == 0.0f) {
            return false;
        }
        float nx = clip[0] / clip[3];
        float ny = clip[1] / clip[3];
        float nz = clip[2] / clip[3];
        int origin = viewport.position();
        out.put(out.position(), viewport.get(origin) + (nx * 0.5f + 0.5f) * viewport.get(origin + 2));
        out.put(out.position() + 1, viewport.get(origin + 1) + (ny * 0.5f + 0.5f) * viewport.get(origin + 3));
        out.put(out.position() + 2, nz * 0.5f + 0.5f);
        return true;
    }

    private static float[] transform(FloatBuffer matrix, float x, float y, float z, float w) {
        int origin = matrix.position();
        float[] out = new float[4];
        for (int row = 0; row < 4; ++row) {
            out[row] = matrix.get(origin + row) * x + matrix.get(origin + 4 + row) * y
                    + matrix.get(origin + 8 + row) * z + matrix.get(origin + 12 + row) * w;
        }
        return out;
    }
}
