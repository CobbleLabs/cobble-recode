package cobble.v1_21.compat;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;

/**
 * 1.8.9's Enchantment as 1.21.11's enchantment registry keys: enchantments
 * are data on 1.21, loaded with the world, so only their keys are constants.
 * An id is the key's place in {@link #KNOWN}, the enchantments the legacy
 * code names, which is all it ever asks a stack about.
 */
public final class EnchantmentShim {
    static final List<RegistryKey<Enchantment>> KNOWN = List.of(Enchantments.PROTECTION,
            Enchantments.FIRE_PROTECTION, Enchantments.BLAST_PROTECTION, Enchantments.PROJECTILE_PROTECTION,
            Enchantments.THORNS, Enchantments.SHARPNESS, Enchantments.KNOCKBACK, Enchantments.FIRE_ASPECT,
            Enchantments.EFFICIENCY, Enchantments.UNBREAKING, Enchantments.POWER, Enchantments.PUNCH,
            Enchantments.FLAME);

    private EnchantmentShim() {}

    static int level(ItemStack stack, RegistryKey<Enchantment> key) {
        if (stack == null) {
            return 0;
        }
        for (Object2IntMap.Entry<RegistryEntry<Enchantment>> entry : stack.getEnchantments().getEnchantmentEntries()) {
            if (entry.getKey().matchesKey(key)) {
                return entry.getIntValue();
            }
        }
        return 0;
    }

    public static int effectId(RegistryKey<Enchantment> key) {
        return KNOWN.indexOf(key);
    }

    public static RegistryKey<Enchantment> protection() {
        return Enchantments.PROTECTION;
    }

    public static RegistryKey<Enchantment> fireProtection() {
        return Enchantments.FIRE_PROTECTION;
    }

    public static RegistryKey<Enchantment> blastProtection() {
        return Enchantments.BLAST_PROTECTION;
    }

    public static RegistryKey<Enchantment> projectileProtection() {
        return Enchantments.PROJECTILE_PROTECTION;
    }

    public static RegistryKey<Enchantment> thorns() {
        return Enchantments.THORNS;
    }

    public static RegistryKey<Enchantment> sharpness() {
        return Enchantments.SHARPNESS;
    }

    public static RegistryKey<Enchantment> knockback() {
        return Enchantments.KNOCKBACK;
    }

    public static RegistryKey<Enchantment> fireAspect() {
        return Enchantments.FIRE_ASPECT;
    }

    public static RegistryKey<Enchantment> efficiency() {
        return Enchantments.EFFICIENCY;
    }

    public static RegistryKey<Enchantment> unbreaking() {
        return Enchantments.UNBREAKING;
    }

    public static RegistryKey<Enchantment> power() {
        return Enchantments.POWER;
    }

    public static RegistryKey<Enchantment> punch() {
        return Enchantments.PUNCH;
    }

    public static RegistryKey<Enchantment> flame() {
        return Enchantments.FLAME;
    }

    public static RegistryKey<Enchantment> getEnchantmentByLocation(String location) {
        Identifier id = Identifier.tryParse(location);
        return id == null ? null : RegistryKey.of(RegistryKeys.ENCHANTMENT, id);
    }

    public static Set<Identifier> func_181077_c() {
        Set<Identifier> locations = new HashSet<>();
        KNOWN.forEach(key -> locations.add(key.getValue()));
        return locations;
    }
}
