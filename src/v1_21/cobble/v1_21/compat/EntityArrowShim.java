package cobble.v1_21.compat;

import net.minecraft.entity.projectile.PersistentProjectileEntity;

/** 1.8.9's EntityArrow on 1.21.11's PersistentProjectileEntity, which counts its time in the ground. */
public final class EntityArrowShim {
    private EntityArrowShim() {}

    public static boolean inGround(PersistentProjectileEntity arrow) {
        return arrow.inGroundTime > 0;
    }
}
