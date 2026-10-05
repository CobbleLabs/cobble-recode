package cobble.v1_21.compat;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.DamageTypeTags;

/** 1.8.9's EnchantmentHelper on 1.21.11's enchantment components, by the ids {@link EnchantmentShim} gives out. */
public final class EnchantmentHelperShim {
    private EnchantmentHelperShim() {}

    public static int getEnchantmentLevel(int id, ItemStack stack) {
        return id >= 0 && id < EnchantmentShim.KNOWN.size() ? EnchantmentShim.level(stack, EnchantmentShim.KNOWN.get(id))
                : 0;
    }

    public static Map<Integer, Integer> getEnchantments(ItemStack stack) {
        Map<Integer, Integer> levels = new HashMap<>();
        for (int id = 0; id < EnchantmentShim.KNOWN.size(); ++id) {
            int level = EnchantmentShim.level(stack, EnchantmentShim.KNOWN.get(id));
            if (level > 0) {
                levels.put(id, level);
            }
        }
        return levels;
    }

    public static boolean getAquaAffinityModifier(LivingEntity entity) {
        return EnchantmentShim.level(ItemStacks.orNull(entity.getEquippedStack(EquipmentSlot.HEAD)),
                Enchantments.AQUA_AFFINITY) > 0;
    }

    /* 1.8's sweep of the damage enchantments: sharpness on anything, smite and bane against their kind. */
    public static float getModifierForCreature(ItemStack stack, CreatureAttribute attribute) {
        float modifier = EnchantmentShim.level(stack, Enchantments.SHARPNESS) * 1.25f;
        if (attribute == CreatureAttribute.UNDEAD) {
            modifier += EnchantmentShim.level(stack, Enchantments.SMITE) * 2.5f;
        } else if (attribute == CreatureAttribute.ARTHROPOD) {
            modifier += EnchantmentShim.level(stack, Enchantments.BANE_OF_ARTHROPODS) * 2.5f;
        }
        return modifier;
    }

    /* 1.21's protection points: a level's worth for protection, twice that for the kind the damage is, three times for feather falling. */
    public static int getEnchantmentModifierDamage(ItemStack[] stacks, DamageSource source) {
        int points = 0;
        for (ItemStack stack : stacks) {
            points += EnchantmentShim.level(stack, Enchantments.PROTECTION);
            if (source.isIn(DamageTypeTags.IS_FALL)) {
                points += EnchantmentShim.level(stack, Enchantments.FEATHER_FALLING) * 3;
            }
            if (source.isIn(DamageTypeTags.IS_FIRE)) {
                points += EnchantmentShim.level(stack, Enchantments.FIRE_PROTECTION) * 2;
            }
            if (source.isIn(DamageTypeTags.IS_EXPLOSION)) {
                points += EnchantmentShim.level(stack, Enchantments.BLAST_PROTECTION) * 2;
            }
            if (source.isIn(DamageTypeTags.IS_PROJECTILE)) {
                points += EnchantmentShim.level(stack, Enchantments.PROJECTILE_PROTECTION) * 2;
            }
        }
        return points;
    }

    public static int getKnockbackModifier(LivingEntity entity) {
        return EnchantmentShim.level(ItemStacks.orNull(entity.getMainHandStack()), Enchantments.KNOCKBACK);
    }
}
