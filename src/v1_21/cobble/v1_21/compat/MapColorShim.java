package cobble.v1_21.compat;

import net.minecraft.block.MapColor;

/** 1.8.9's MapColor on 1.21.11's. */
public final class MapColorShim {
    private MapColorShim() {}

    public static int colorValue(MapColor color) {
        return color.color;
    }
}
