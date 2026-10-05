package cobble.v1_21.compat;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
import net.minecraft.util.StringHelper;

/**
 * 1.8.9's Potion as 1.21.11's status effect registry entries. Ids are 1.21's
 * raw registry ids throughout, which is all the legacy code ever compares
 * them with.
 */
public final class PotionShim {
    private PotionShim() {}

    static RegistryEntry<StatusEffect> byId(int id) {
        return Registries.STATUS_EFFECT.getEntry(id).orElse(null);
    }

    public static int id(RegistryEntry<StatusEffect> potion) {
        return Registries.STATUS_EFFECT.getRawId(potion.value());
    }

    public static RegistryEntry<StatusEffect> moveSpeed() {
        return StatusEffects.SPEED;
    }

    public static RegistryEntry<StatusEffect> moveSlowdown() {
        return StatusEffects.SLOWNESS;
    }

    public static RegistryEntry<StatusEffect> digSpeed() {
        return StatusEffects.HASTE;
    }

    public static RegistryEntry<StatusEffect> digSlowdown() {
        return StatusEffects.MINING_FATIGUE;
    }

    public static RegistryEntry<StatusEffect> damageBoost() {
        return StatusEffects.STRENGTH;
    }

    public static RegistryEntry<StatusEffect> jump() {
        return StatusEffects.JUMP_BOOST;
    }

    public static RegistryEntry<StatusEffect> confusion() {
        return StatusEffects.NAUSEA;
    }

    public static RegistryEntry<StatusEffect> resistance() {
        return StatusEffects.RESISTANCE;
    }

    public static RegistryEntry<StatusEffect> invisibility() {
        return StatusEffects.INVISIBILITY;
    }

    public static RegistryEntry<StatusEffect> blindness() {
        return StatusEffects.BLINDNESS;
    }

    public static RegistryEntry<StatusEffect> nightVision() {
        return StatusEffects.NIGHT_VISION;
    }

    public static RegistryEntry<StatusEffect> poison() {
        return StatusEffects.POISON;
    }

    public static RegistryEntry<StatusEffect> wither() {
        return StatusEffects.WITHER;
    }

    /* 1.21 draws effect icons from its own sprite atlas, which 1.8's icon grid does not address. */
    public static boolean hasStatusIcon(RegistryEntry<StatusEffect> potion) {
        return false;
    }

    public static int getStatusIconIndex(RegistryEntry<StatusEffect> potion) {
        return 0;
    }

    public static String getDurationString(StatusEffectInstance effect) {
        return effect.isInfinite() ? "**:**" : StringHelper.formatTicks(effect.getDuration(), 20.0f);
    }

    public static int getId(RegistryEntry<StatusEffect> potion) {
        return id(potion);
    }

    /** Indexed by raw id, as 1.8's array is by its own ids. */
    public static RegistryEntry<StatusEffect>[] potionTypes() {
        @SuppressWarnings("unchecked")
        RegistryEntry<StatusEffect>[] types = new RegistryEntry[Registries.STATUS_EFFECT.size()];
        for (int id = 0; id < types.length; ++id) {
            types[id] = byId(id);
        }
        return types;
    }

    public static RegistryEntry<StatusEffect> getPotionFromResourceLocation(String location) {
        Identifier id = Identifier.tryParse(location);
        return id == null ? null : Registries.STATUS_EFFECT.getEntry(id).orElse(null);
    }

    public static Set<Identifier> getPotionLocations() {
        return new HashSet<>(Registries.STATUS_EFFECT.getIds());
    }
}
