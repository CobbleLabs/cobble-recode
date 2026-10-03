package cobble.module.shared;

import cobble.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;

/** Who is squared up with whom: a non-friend within six blocks, looking within sixty degrees of the other. */
public final class Facing {
    private static final double RANGE = 6.0;
    private static final int ANGLE = 60;

    private Facing() {}

    public static boolean opponentFacing(EntityPlayerSP player) {
        for (EntityPlayer other : Minecraft.getMinecraft().theWorld.playerEntities) {
            if (opponent(player, other) && angleTo(other, player) < ANGLE) {
                return true;
            }
        }
        return false;
    }

    public static boolean facingOpponent(EntityPlayerSP player) {
        for (EntityPlayer other : Minecraft.getMinecraft().theWorld.playerEntities) {
            if (opponent(player, other) && angleTo(player, other) < ANGLE) {
                return true;
            }
        }
        return false;
    }

    private static boolean opponent(EntityPlayerSP player, EntityPlayer other) {
        return other != player && !other.isDead && other.getHealth() > 0.0f && !ClientUtils.isFriend(other)
                && player.getDistanceToEntity(other) < RANGE;
    }

    /* Degrees between where the viewer looks and where the target stands, on the yaw alone. */
    public static int angleTo(Entity viewer, Entity target) {
        double yaw = -Math.toDegrees(Math.atan2(target.posX - viewer.posX, target.posZ - viewer.posZ));
        int difference = (int) (Math.abs(yaw - viewer.rotationYaw) % 360.0);
        return difference > 180 ? 360 - difference : difference;
    }
}
