package cobble.v1_21.compat;

import net.minecraft.util.math.Box;

/** 1.8.9's view frustum, tested against the world pass's matrices: a box is in view when any corner is. */
public final class Frustum {
    private double x;
    private double y;
    private double z;

    public Frustum() {}

    public void setPosition(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public boolean isBoundingBoxInFrustum(Box box) {
        float[] mvp = GlEmu.worldViewProjection();
        for (int corner = 0; corner < 8; ++corner) {
            double px = ((corner & 1) == 0 ? box.minX : box.maxX) - x;
            double py = ((corner & 2) == 0 ? box.minY : box.maxY) - y;
            double pz = ((corner & 4) == 0 ? box.minZ : box.maxZ) - z;
            float cx = (float) (mvp[0] * px + mvp[4] * py + mvp[8] * pz + mvp[12]);
            float cy = (float) (mvp[1] * px + mvp[5] * py + mvp[9] * pz + mvp[13]);
            float cw = (float) (mvp[3] * px + mvp[7] * py + mvp[11] * pz + mvp[15]);
            if (cw > 0.0f && Math.abs(cx) <= cw && Math.abs(cy) <= cw) {
                return true;
            }
        }
        return false;
    }
}
