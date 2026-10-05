package cobble.v1_21.compat;

import net.minecraft.util.Identifier;

/** 1.8.9's ResourceLocation on 1.21.11's Identifier. */
public final class ResourceLocationShim {
    private ResourceLocationShim() {}

    public static Identifier create(String location) {
        return Identifier.of(location);
    }

    public static String getResourcePath(Identifier id) {
        return id.getPath();
    }
}
