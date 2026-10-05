package cobble.v1_21.compat;

import net.minecraft.world.World;

/** 1.8.9's WorldProvider, on the world it belonged to; see WorldShim.provider. */
public final class WorldProviderShim {
    private WorldProviderShim() {}

    public static String getDimensionName(World world) {
        if (world.getRegistryKey() == World.NETHER) {
            return "Nether";
        }
        return world.getRegistryKey() == World.END ? "The End" : "Overworld";
    }
}
