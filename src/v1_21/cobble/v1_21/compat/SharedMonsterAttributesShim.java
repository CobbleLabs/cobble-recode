package cobble.v1_21.compat;

import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.registry.entry.RegistryEntry;

/** 1.8.9's SharedMonsterAttributes as 1.21.11's attribute registry entries. */
public final class SharedMonsterAttributesShim {
    private SharedMonsterAttributesShim() {}

    public static RegistryEntry<EntityAttribute> attackDamage() {
        return EntityAttributes.ATTACK_DAMAGE;
    }
}
