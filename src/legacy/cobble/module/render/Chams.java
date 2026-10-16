package cobble.module.render;

import cobble.event.RenderPlayerPostEvent;
import cobble.event.RenderPlayerPreEvent;
import cobble.event.RenderWorldLastEvent;
import cobble.event.Subscribe;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.LegacyModule;
import cobble.module.Subcategory;
import cobble.module.other.AntiBot;
import cobble.platform.Platform;
import cobble.util.render.FakePlayerRenderer;
import net.minecraft.entity.player.EntityPlayer;
import org.lwjgl.opengl.GL11;

public class Chams extends LegacyModule {
    private boolean polygonOffsetActive;
    private boolean LAAWtE;
    private float savedPolygonOffsetFactor;
    private float IGauQ1;

    public Chams() {
        super("Chams", "Draws players through walls", Category.VISUALS, Subcategory.MEDIUM);
    }

    private void applyPolygonOffset() {
        this.LAAWtE = GL11.glIsEnabled(32823);
        this.savedPolygonOffsetFactor = GL11.glGetFloat(32824);
        this.IGauQ1 = GL11.glGetFloat(10752);
        this.polygonOffsetActive = true;
        GL11.glEnable(32823);
        GL11.glPolygonOffset(1.0F, -4000000.0F);
    }

    private void restorePolygonOffset() {
        if (this.polygonOffsetActive) {
            GL11.glPolygonOffset(this.savedPolygonOffsetFactor, this.IGauQ1);
            if (this.LAAWtE) {
                GL11.glEnable(32823);
            } else {
                GL11.glDisable(32823);
            }

            this.polygonOffsetActive = false;
        }
    }

    private static boolean shown(EntityPlayer player) {
        return player != mc.thePlayer && !AntiBot.shouldHideEntity(player);
    }

    @Subscribe
    public void onRenderPlayerPre(RenderPlayerPreEvent var1) {
        this.restorePolygonOffset();
        if (!this.streamProof() && shown(var1.entityPlayer)) {
            this.applyPolygonOffset();
        }
    }

    @Subscribe
    public void onRenderPlayerPost(RenderPlayerPostEvent var1) {
        this.restorePolygonOffset();
    }

    @Subscribe
    public void onRenderWorldLast(RenderWorldLastEvent var1) {
        this.restorePolygonOffset();
        if (this.streamProof()) {
            this.renderOccluded(var1.partialTicks);
        }
    }

    /*
     * Kept off captures, the game draws every player as it normally would and
     * the layer gets only the parts of them a wall hides: drawn again behind
     * the depth already there, pulled a step closer so the parts in view fail.
     */
    private void renderOccluded(float partialTicks) {
        GL11.glPushAttrib(GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_POLYGON_BIT | GL11.GL_ENABLE_BIT);
        try {
            for (EntityPlayer player : mc.theWorld.playerEntities) {
                if (!player.isDead && shown(player)) {
                    // Set per player: the renderer leaves its own depth state behind.
                    GL11.glEnable(GL11.GL_DEPTH_TEST);
                    GL11.glDepthMask(false);
                    GL11.glDepthFunc(GL11.GL_GREATER);
                    GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
                    GL11.glPolygonOffset(-1.0F, -1.0F);
                    Platform.get().resyncGlState();
                    FakePlayerRenderer.renderAgain(player, partialTicks);
                }
            }
        } finally {
            GL11.glPopAttrib();
        }
    }

    @Override
    public void onDisable(Context context) {
        this.restorePolygonOffset();
    }
}
