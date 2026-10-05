package cobble.module.render;

import cobble.event.RenderWorldLastEvent;
import cobble.event.Subscribe;
import cobble.module.Category;
import cobble.module.LegacyModule;
import cobble.module.Setting;
import cobble.module.Subcategory;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityTNTPrimed;
import org.lwjgl.opengl.GL11;

/**
 * Shows how far lit TNT will reach: a red sphere where it hurts players and a
 * yellow ring where it breaks blocks, both pulsing faster as the fuse runs.
 */
public final class Explosions extends LegacyModule {
    private static final double BLOCK_RADIUS = 4.0;
    private static final double ENTITY_RADIUS = 8.0;

    private final Setting blastRing = addBoolean("Blast ring", "Show the radius where blocks will break", true);

    public Explosions() {
        super("Explosions", "Shows the explosion radius of TNT", Category.VISUALS, Subcategory.BASIC);
    }

    @Subscribe
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        if (mc.theWorld == null) {
            return;
        }
        RenderManager view = mc.getRenderManager();
        float partial = event.partialTicks;
        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.disableCull();
        GlStateManager.depthMask(false);
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        for (Entity entity : mc.theWorld.loadedEntityList) {
            if (!(entity instanceof EntityTNTPrimed)) {
                continue;
            }
            double x = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partial - view.viewerPosX;
            double y = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partial - view.viewerPosY;
            double z = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partial - view.viewerPosZ;
            /* Solid for five ticks, then fading for five, so it blinks like the TNT itself. */
            float progress = (entity.ticksExisted % 5 - partial + 1.0f) / 5.0f;
            float pulse = entity.ticksExisted / 5 % 2 == 0 ? 1.0f : progress * progress;
            float alpha = Math.max(0.0f, Math.min(1.0f, pulse));
            GlStateManager.pushMatrix();
            GlStateManager.translate(x, y, z);
            if (blastRing.bool()) {
                GlStateManager.color(1.0f, 1.0f, 0.0f, alpha * 0.75f);
                sphere(BLOCK_RADIUS, 24, true);
            }
            GlStateManager.color(1.0f, 0.0f, 0.0f, alpha * 0.4f);
            sphere(ENTITY_RADIUS, 32, false);
            GlStateManager.popMatrix();
        }
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        GlStateManager.depthMask(true);
        GlStateManager.enableCull();
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.popMatrix();
    }

    /* Latitude bands from pole to pole, as lines or as filled strips. */
    private static void sphere(double radius, int segments, boolean wire) {
        for (int band = 0; band < segments; ++band) {
            double upper = Math.PI * band / segments;
            double lower = Math.PI * (band + 1) / segments;
            GL11.glBegin(wire ? GL11.GL_LINE_LOOP : GL11.GL_QUAD_STRIP);
            for (int step = 0; step <= segments; ++step) {
                double around = 2.0 * Math.PI * step / segments;
                double cos = Math.cos(around);
                double sin = Math.sin(around);
                GL11.glVertex3d(radius * Math.sin(upper) * cos, radius * Math.cos(upper),
                        radius * Math.sin(upper) * sin);
                if (!wire) {
                    GL11.glVertex3d(radius * Math.sin(lower) * cos, radius * Math.cos(lower),
                            radius * Math.sin(lower) * sin);
                }
            }
            GL11.glEnd();
        }
    }
}
