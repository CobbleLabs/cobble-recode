package cobble.v1_21.compat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.Window;

/** 1.8.9's ScaledResolution as 1.21.11's Window, which keeps the scaled size itself. */
public final class ScaledResolutionShim {
    private ScaledResolutionShim() {}

    public static Window create(MinecraftClient mc) {
        return mc.getWindow();
    }
}
