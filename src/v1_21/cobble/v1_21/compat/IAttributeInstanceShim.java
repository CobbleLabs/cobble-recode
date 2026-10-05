package cobble.v1_21.compat;

import net.minecraft.entity.attribute.EntityAttributeInstance;

/** 1.8.9's IAttributeInstance on 1.21.11's EntityAttributeInstance. */
public final class IAttributeInstanceShim {
    private IAttributeInstanceShim() {}

    public static double getAttributeValue(EntityAttributeInstance instance) {
        return instance.getValue();
    }
}
