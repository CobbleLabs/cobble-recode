package cobble.v1_21.compat;

import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.FishingBobberEntity;

/** 1.8.9's EntityFishHook on 1.21.11's FishingBobberEntity. */
public final class EntityFishHookShim {
    private EntityFishHookShim() {}

    public static Entity caughtEntity(FishingBobberEntity bobber) {
        return bobber.getHookedEntity();
    }
}
