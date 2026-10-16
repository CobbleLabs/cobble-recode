package cobble.v1_7.compat;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;

/**
 * Entity's height the 1.8.9 way. 1.7.10 keeps an entity's posY yOffset above
 * its feet: 1.62 for the client's own player, half the height for items,
 * minecarts and the like, nothing for the rest. 1.8.9 dropped yOffset and puts
 * every entity's posY at its feet, which is what the legacy code reads and
 * writes; tools/Retarget.java sends its Entity reads, writes and distances
 * here.
 */
public final class EntityShim {
    private EntityShim() {}

    public static double posY(Entity entity) {
        return entity.posY - entity.yOffset;
    }

    public static double prevPosY(Entity entity) {
        return entity.prevPosY - entity.yOffset;
    }

    public static void prevPosY(Entity entity, double y) {
        entity.prevPosY = y + entity.yOffset;
    }

    public static double lastTickPosY(Entity entity) {
        return entity.lastTickPosY - entity.yOffset;
    }

    public static void lastTickPosY(Entity entity, double y) {
        entity.lastTickPosY = y + entity.yOffset;
    }

    public static void setPosition(Entity entity, double x, double y, double z) {
        entity.setPosition(x, y + entity.yOffset, z);
    }

    public static void setPositionAndRotation(Entity entity, double x, double y, double z, float yaw, float pitch) {
        entity.setPositionAndRotation(x, y + entity.yOffset, z, yaw, pitch);
    }

    /* 1.8.9's: living entities answer their own, the rest from their height, as 1.8.9's Entity does. */
    public static float getEyeHeight(Entity entity) {
        if (entity instanceof EntityPlayer player) {
            return EntityPlayerShim.getEyeHeight(player);
        }
        return entity instanceof EntityLivingBase ? entity.getEyeHeight() : entity.height * 0.85F;
    }

    public static double getDistance(Entity entity, double x, double y, double z) {
        return Math.sqrt(squared(entity.posX - x, posY(entity) - y, entity.posZ - z));
    }

    public static double getDistanceSqToEntity(Entity entity, Entity other) {
        return squared(entity.posX - other.posX, posY(entity) - posY(other), entity.posZ - other.posZ);
    }

    public static float getDistanceToEntity(Entity entity, Entity other) {
        return MathHelper.sqrt_double(getDistanceSqToEntity(entity, other));
    }

    private static double squared(double x, double y, double z) {
        return x * x + y * y + z * z;
    }
}
