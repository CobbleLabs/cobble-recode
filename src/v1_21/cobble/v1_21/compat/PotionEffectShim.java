package cobble.v1_21.compat;

import net.minecraft.entity.effect.StatusEffectInstance;

/** 1.8.9's PotionEffect on 1.21.11's StatusEffectInstance, by {@link PotionShim}'s ids. */
public final class PotionEffectShim {
    private PotionEffectShim() {}

    public static StatusEffectInstance create(int id, int duration, int amplifier) {
        return new StatusEffectInstance(PotionShim.byId(id), duration, amplifier);
    }

    public static String getEffectName(StatusEffectInstance effect) {
        return effect.getTranslationKey();
    }

    public static int getPotionID(StatusEffectInstance effect) {
        return PotionShim.id(effect.getEffectType());
    }
}
