package cobble.v1_21.compat;

import net.minecraft.entity.attribute.EntityAttributeModifier;

/** 1.8.9's AttributeModifier on 1.21.11's EntityAttributeModifier, which is a record. */
public final class AttributeModifierShim {
    private AttributeModifierShim() {}

    public static double getAmount(EntityAttributeModifier modifier) {
        return modifier.value();
    }
}
