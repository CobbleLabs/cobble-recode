package cobble.module.movement;

import cobble.event.MoveEvent;
import cobble.event.Subscribe;
import cobble.event.UpdateWalkingPlayerEvent;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.Setting;
import cobble.module.Subcategory;
import java.util.List;
import net.minecraft.client.entity.EntityPlayerSP;

/**
 * Faster on foot. Bhop jumps on its own and carries a burst of speed through
 * each hop, cut back every other tick the way old NCP allowed; AntiCheat B
 * hops whenever it lands and moves at one flat speed.
 */
public final class Speed extends Module {
    private static final int BHOP = 0;

    private final Setting mode = addChoice("Mode", "Bhop hops with bursts; AntiCheat B hops at one speed",
            List.of("Bhop", "AntiCheat B"), BHOP);

    private double moveSpeed;
    private double lastDistance;
    private int stage;

    public Speed() {
        super("Speed", "Increases your movement speed", Category.MOVEMENT, Subcategory.ADVANCED);
    }

    @Override
    public String suffix() {
        return mode.choiceName();
    }

    @Override
    public void onEnable(Context context) {
        moveSpeed = mc.thePlayer == null ? Strafing.BASE_SPEED : defaultSpeed(mc.thePlayer);
        lastDistance = 0.0;
        stage = 2;
    }

    /** Step sets it back after climbing, so the next hop does not launch off the step. */
    public void setStage(int value) {
        stage = value;
    }

    @Subscribe(priority = Subscribe.Priority.HIGH)
    public void onUpdateWalkingPlayer(UpdateWalkingPlayerEvent event) {
        EntityPlayerSP player = mc.thePlayer;
        lastDistance = Math.hypot(player.posX - player.prevPosX, player.posZ - player.prevPosZ);
    }

    @Subscribe
    public void onMove(MoveEvent event) {
        EntityPlayerSP player = mc.thePlayer;
        if (player.isInWater()) {
            return;
        }
        if (mode.choice() == BHOP) {
            bhop(event, player);
        } else {
            if (Strafing.moving(player) && player.isCollidedVertically) {
                hop(event, player);
            }
            Strafing.move(event, 0.4199999976158142);
        }
    }

    private void bhop(MoveEvent event, EntityPlayerSP player) {
        boolean moving = Strafing.moving(player);
        double base = defaultSpeed(player);
        if (!moving) {
            moveSpeed = base;
        }
        if (stage == 1 && player.isCollidedVertically && moving) {
            moveSpeed = 0.25 + base - 0.01;
        }
        if (stage == 2 && moving && player.onGround) {
            hop(event, player);
            moveSpeed *= 2.149;
        } else if (stage == 3) {
            moveSpeed = lastDistance - 0.66 * (lastDistance - base);
        } else {
            boolean blocked = !mc.theWorld.getCollidingBoundingBoxes(player,
                    player.getEntityBoundingBox().offset(0.0, player.motionY, 0.0)).isEmpty();
            if ((blocked || player.isCollidedVertically) && stage > 0) {
                stage = 1.35 * base - 0.01 > moveSpeed ? 0 : moving ? 1 : 0;
            }
            moveSpeed = lastDistance - lastDistance / 159.0;
        }
        if (stage > 0) {
            moveSpeed = Math.max(moveSpeed, base);
            Strafing.move(event, moveSpeed);
        }
        if (moving) {
            ++stage;
        }
    }

    private static void hop(MoveEvent event, EntityPlayerSP player) {
        double motion = Strafing.jumpMotion(player);
        event.setY(motion);
        player.motionY = motion;
    }

    private static double defaultSpeed(EntityPlayerSP player) {
        return Strafing.baseSpeed(player, 0.15, 10);
    }
}
