package cobble.v1_21.compat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.damage.DamageSource;

/** 1.8.9's DamageSource constants, which 1.21.11 makes from the world's damage types. */
public final class DamageSourceShim {
    private DamageSourceShim() {}

    public static DamageSource fall() {
        return MinecraftClient.getInstance().world.getDamageSources().fall();
    }
}
