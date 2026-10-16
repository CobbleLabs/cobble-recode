package cobble.v1_7.compat;

import net.minecraft.entity.player.EntityPlayer;

/** A player's eyes above its feet the 1.8.9 way; 1.7.10 answers 0.12 above posY, which yOffset already lifts. */
public final class EntityPlayerShim {
    private EntityPlayerShim() {}

    public static float getEyeHeight(EntityPlayer player) {
        float height = player.isPlayerSleeping() ? 0.2F : 1.62F;
        return player.isSneaking() ? height - 0.08F : height;
    }
}
