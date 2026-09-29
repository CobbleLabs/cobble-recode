package cobble.game;

import cobble.core.Vec3;
import net.minecraft.entity.Entity;
import net.minecraft.util.EnumFacing;

/**
 * What Minecraft's own crosshair pick says the player is pointing at. The
 * server reconstructs an interaction from the rotation it was sent, so acting
 * on anything else is acting on something the server will not agree with.
 */
public record CrosshairTarget(Kind kind, int x, int y, int z, EnumFacing face, Vec3 point,
                              Entity entity, boolean crystal, boolean slime) {
    public enum Kind { MISS, BLOCK, ENTITY }

    public static final CrosshairTarget MISS =
            new CrosshairTarget(Kind.MISS, 0, 0, 0, EnumFacing.UP, Vec3.ZERO, null, false, false);

    public int entityId() {
        return entity != null ? entity.getEntityId() : -1;
    }
}
