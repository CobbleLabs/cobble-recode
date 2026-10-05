package cobble.v1_21.compat;

import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;

/** 1.8.9's WorldClient on 1.21.11's ClientWorld. */
public final class WorldClientShim {
    private WorldClientShim() {}

    public static Entity getEntityByID(ClientWorld world, int id) {
        return world.getEntityById(id);
    }
}
