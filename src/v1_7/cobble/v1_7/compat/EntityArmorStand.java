package cobble.v1_7.compat;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.world.World;

/** Armour stands came with 1.8; on 1.7.10 no entity is one. */
public abstract class EntityArmorStand extends EntityLivingBase {
    private EntityArmorStand(World world) {
        super(world);
    }
}
