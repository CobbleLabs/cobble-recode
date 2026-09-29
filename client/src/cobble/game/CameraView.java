package cobble.game;

import cobble.core.Vec3;

/** The origin world-space overlays are drawn relative to, and where it faces. */
public final class CameraView {
    public Vec3 pos = Vec3.ZERO;
    public float yaw;
    public float pitch;
}
