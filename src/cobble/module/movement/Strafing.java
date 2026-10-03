package cobble.module.movement;

import cobble.event.MoveEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.potion.Potion;

/** The movement modules' shared moves: this tick's step where the keys point, and vanilla's ground speed. */
final class Strafing {
    /* Vanilla sprint speed on the ground. */
    static final double BASE_SPEED = 0.28730000691562896;

    private Strafing() {}

    /** Points the move along the held keys at {@code distance}, or stops it when none is held. */
    static void move(MoveEvent event, double distance) {
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        double forward = player.movementInput.moveForward;
        double strafe = player.movementInput.moveStrafe;
        float yaw = player.rotationYaw;
        if (forward == 0.0 && strafe == 0.0) {
            event.setX(0.0);
            event.setZ(0.0);
            return;
        }
        if (forward != 0.0) {
            if (strafe > 0.0) {
                yaw += forward > 0.0 ? -45.0f : 45.0f;
            } else if (strafe < 0.0) {
                yaw += forward > 0.0 ? 45.0f : -45.0f;
            }
            strafe = 0.0;
            forward = Math.signum(forward);
        }
        double cos = Math.cos(Math.toRadians(yaw + 90.0f));
        double sin = Math.sin(Math.toRadians(yaw + 90.0f));
        event.setX(forward * distance * cos + strafe * distance * sin);
        event.setZ(forward * distance * sin - strafe * distance * cos);
    }

    static boolean moving(EntityPlayerSP player) {
        return player.moveForward != 0.0f || player.moveStrafing != 0.0f;
    }

    /* Speed raises it by a share per level once its effect lasts more than the given ticks. */
    static double baseSpeed(EntityPlayerSP player, double perLevel, int minimumTicks) {
        if (!player.isPotionActive(Potion.moveSpeed)
                || player.getActivePotionEffect(Potion.moveSpeed).getDuration() <= minimumTicks) {
            return BASE_SPEED;
        }
        return BASE_SPEED * (1.0 + perLevel * (player.getActivePotionEffect(Potion.moveSpeed).getAmplifier() + 1));
    }

    /* What a jump starts with, Jump Boost included. */
    static double jumpMotion(EntityPlayerSP player) {
        double motion = 0.42f;
        if (player.isPotionActive(Potion.jump)) {
            motion += (player.getActivePotionEffect(Potion.jump).getAmplifier() + 1) * 0.1f;
        }
        return motion;
    }
}
