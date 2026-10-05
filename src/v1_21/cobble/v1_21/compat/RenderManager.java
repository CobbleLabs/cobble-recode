package cobble.v1_21.compat;

import net.minecraft.entity.Entity;

/**
 * 1.8.9's RenderManager: the camera position and angles the legacy drawing
 * code offsets the world by, set at the start of each world pass.
 */
public final class RenderManager {
    static final RenderManager INSTANCE = new RenderManager();

    public double viewerPosX;
    public double viewerPosY;
    public double viewerPosZ;
    public double renderPosX;
    public double renderPosY;
    public double renderPosZ;
    public float playerViewX;
    public float playerViewY;
    private boolean renderShadow = true;

    private RenderManager() {}

    void setCamera(double x, double y, double z, float pitch, float yaw) {
        viewerPosX = renderPosX = x;
        viewerPosY = renderPosY = y;
        viewerPosZ = renderPosZ = z;
        playerViewX = pitch;
        playerViewY = yaw;
    }

    public boolean isRenderShadow() {
        return renderShadow;
    }

    public void setRenderShadow(boolean shadow) {
        renderShadow = shadow;
    }

    /* Entity models go through 1.21's own render pipeline, which the overlay cannot drive. */
    public boolean renderEntityWithPosYaw(Entity entity, double x, double y, double z, float yaw, float partialTicks) {
        return false;
    }
}
