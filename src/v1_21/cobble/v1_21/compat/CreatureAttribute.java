package cobble.v1_21.compat;

import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.tag.EntityTypeTags;

/** 1.8.9's EnumCreatureAttribute, which 1.21.11 replaced with entity type tags. */
public enum CreatureAttribute {
    UNDEFINED,
    UNDEAD,
    ARTHROPOD;

    static CreatureAttribute of(LivingEntity entity) {
        if (entity.getType().isIn(EntityTypeTags.UNDEAD)) {
            return UNDEAD;
        }
        return entity.getType().isIn(EntityTypeTags.ARTHROPOD) ? ARTHROPOD : UNDEFINED;
    }
}
