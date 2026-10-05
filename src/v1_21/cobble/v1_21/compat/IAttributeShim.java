package cobble.v1_21.compat;

import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.registry.entry.RegistryEntry;

/** 1.8.9's IAttribute as a 1.21.11 attribute registry entry, named by its id. */
public final class IAttributeShim {
    private IAttributeShim() {}

    public static String getAttributeUnlocalizedName(RegistryEntry<EntityAttribute> attribute) {
        return attribute.getIdAsString();
    }
}
