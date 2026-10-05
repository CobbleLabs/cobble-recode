package cobble.v1_21.compat;

import net.minecraft.entity.projectile.AbstractFireballEntity;

/**
 * 1.8.9's EntityFireball on 1.21.11's. 1.8 pushed a fireball by a fixed
 * acceleration; 1.21 pushes it along its velocity, which is the direction
 * the legacy code reads the acceleration for.
 */
public final class EntityFireballShim {
    private EntityFireballShim() {}

    public static float getCollisionBorderSize(AbstractFireballEntity fireball) {
        return fireball.getTargetingMargin();
    }

    public static double accelerationX(AbstractFireballEntity fireball) {
        return fireball.getVelocity().x;
    }

    public static double accelerationY(AbstractFireballEntity fireball) {
        return fireball.getVelocity().y;
    }

    public static double accelerationZ(AbstractFireballEntity fireball) {
        return fireball.getVelocity().z;
    }
}
