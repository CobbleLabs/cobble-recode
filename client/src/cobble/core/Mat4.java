package cobble.core;

/** Column-major, matching OpenGL memory order: m[column * 4 + row]. */
public final class Mat4 {
    public final float[] m = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};

    public float at(int column, int row) {
        return m[column * 4 + row];
    }

    public void set(int column, int row, float value) {
        m[column * 4 + row] = value;
    }

    public Mat4 mul(Mat4 rhs) {
        Mat4 result = new Mat4();
        for (int column = 0; column < 4; ++column) {
            for (int row = 0; row < 4; ++row) {
                float sum = 0.0f;
                for (int k = 0; k < 4; ++k) {
                    sum += at(k, row) * rhs.at(column, k);
                }
                result.set(column, row, sum);
            }
        }
        return result;
    }

    /**
     * Transforms a point into {@code out}, keeping the homogeneous w so callers
     * can reject anything behind the near plane.
     */
    public void transform(double px, double py, double pz, float[] out) {
        float x = (float) px;
        float y = (float) py;
        float z = (float) pz;
        for (int row = 0; row < 4; ++row) {
            out[row] = at(0, row) * x + at(1, row) * y + at(2, row) * z + at(3, row);
        }
    }

    public static Mat4 orthographic(float left, float right, float bottom, float top) {
        Mat4 result = new Mat4();
        result.set(0, 0, 2.0f / (right - left));
        result.set(1, 1, 2.0f / (top - bottom));
        result.set(2, 2, -1.0f);
        result.set(3, 0, -(right + left) / (right - left));
        result.set(3, 1, -(top + bottom) / (top - bottom));
        return result;
    }

    public static Mat4 translation(float x, float y, float z) {
        Mat4 result = new Mat4();
        result.set(3, 0, x);
        result.set(3, 1, y);
        result.set(3, 2, z);
        return result;
    }

    public static Mat4 fromQuaternion(float x, float y, float z, float w) {
        Mat4 result = new Mat4();
        float w2 = w * w;
        float x2 = x * x;
        float y2 = y * y;
        float z2 = z * z;
        float zw = z * w;
        float xy = x * y;
        float xz = x * z;
        float yw = y * w;
        float yz = y * z;
        float xw = x * w;
        result.set(0, 0, w2 + x2 - z2 - y2);
        result.set(0, 1, xy + zw + zw + xy);
        result.set(0, 2, xz - yw + xz - yw);
        result.set(1, 0, -zw + xy - zw + xy);
        result.set(1, 1, y2 - z2 + w2 - x2);
        result.set(1, 2, yz + yz + xw + xw);
        result.set(2, 0, yw + xz + xz + yw);
        result.set(2, 1, yz + yz - xw - xw);
        result.set(2, 2, z2 - y2 - x2 + w2);
        return result;
    }

    public static Mat4 rotationDegrees(float degrees, float ax, float ay, float az) {
        float half = degrees * Maths.DEG_TO_RAD * 0.5f;
        float s = (float) Math.sin(half);
        return fromQuaternion(ax * s, ay * s, az * s, (float) Math.cos(half));
    }
}
