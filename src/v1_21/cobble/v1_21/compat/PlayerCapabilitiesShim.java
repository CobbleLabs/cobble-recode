package cobble.v1_21.compat;

import net.minecraft.entity.player.PlayerAbilities;

/** 1.8.9's PlayerCapabilities on 1.21.11's PlayerAbilities. */
public final class PlayerCapabilitiesShim {
    private PlayerCapabilitiesShim() {}

    public static boolean allowEdit(PlayerAbilities abilities) {
        return abilities.allowModifyWorld;
    }

    public static boolean isCreativeMode(PlayerAbilities abilities) {
        return abilities.creativeMode;
    }

    public static boolean isFlying(PlayerAbilities abilities) {
        return abilities.flying;
    }

    public static void isFlying(PlayerAbilities abilities, boolean flying) {
        abilities.flying = flying;
    }
}
