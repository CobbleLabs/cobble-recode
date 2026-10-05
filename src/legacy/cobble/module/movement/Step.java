package cobble.module.movement;

import cobble.core.Time;
import cobble.event.MoveEvent;
import cobble.event.PostMoveEvent;
import cobble.event.Subscribe;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.LegacyModule;
import cobble.module.ModuleManager;
import cobble.module.Subcategory;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.network.play.client.C03PacketPlayer.C04PacketPlayerPosition;

/**
 * Walks straight up full blocks. A climb of most of a block is reported as the
 * two positions a jump would pass through on its way up, and the game runs at
 * a bit under half speed for a tenth of a second while the server catches up.
 */
public final class Step extends LegacyModule {
    private static final float STEP_HEIGHT = 1.0f;
    private static final float TIMER_SPEED = 0.45f;
    private static final long TIMER_MILLIS = 100;

    private float vanillaHeight;
    private boolean raised;
    private double startY;
    private long timerUntil = -1;

    public Step() {
        super("Step", "Steps up full blocks", Category.MOVEMENT, Subcategory.ADVANCED);
    }

    @Override
    public void onDisable(Context context) {
        if (raised) {
            mc.thePlayer.stepHeight = vanillaHeight;
            raised = false;
        }
        if (timerUntil >= 0) {
            mc.timer.timerSpeed = 1.0f;
            timerUntil = -1;
        }
    }

    @Subscribe
    public void onMove(MoveEvent event) {
        EntityPlayerSP player = mc.thePlayer;
        if (player.movementInput.jump || !player.isCollidedVertically) {
            return;
        }
        vanillaHeight = player.stepHeight;
        player.stepHeight = STEP_HEIGHT;
        startY = player.getEntityBoundingBox().minY;
        raised = true;
    }

    @Subscribe
    public void onPostMove(PostMoveEvent event) {
        if (!raised) {
            return;
        }
        EntityPlayerSP player = mc.thePlayer;
        player.stepHeight = vanillaHeight;
        raised = false;
        /* A jump rises 0.42 in its first tick, so more than half a block in one move is a step. */
        double height = player.getEntityBoundingBox().minY - startY;
        if (height <= 0.5) {
            return;
        }
        ModuleManager.get(Speed.class).setStage(-4);
        if (height >= 0.87) {
            mc.getNetHandler().addToSendQueue(
                    new C04PacketPlayerPosition(player.posX, startY + height * 0.42, player.posZ, player.onGround));
            mc.getNetHandler().addToSendQueue(
                    new C04PacketPlayerPosition(player.posX, startY + height * 0.75, player.posZ, player.onGround));
        }
        mc.timer.timerSpeed = TIMER_SPEED;
        timerUntil = Time.nowMillis() + TIMER_MILLIS;
    }

    @Override
    public void onRender(Context context) {
        if (timerUntil >= 0 && Time.nowMillis() >= timerUntil) {
            mc.timer.timerSpeed = 1.0f;
            timerUntil = -1;
        }
    }
}
