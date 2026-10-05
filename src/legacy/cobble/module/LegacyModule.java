package cobble.module;

import net.minecraft.client.Minecraft;

/** A module written straight against the MCP-named game, which 1.7.10 and 1.8.9 share. */
public abstract class LegacyModule extends Module {
    protected static final Minecraft mc = Minecraft.getMinecraft();

    protected LegacyModule(String name, String description, Category category, Subcategory subcategory) {
        super(name, description, category, subcategory);
    }

    protected LegacyModule(String name, String description, Category category, Subcategory subcategory,
                           int defaultKey) {
        super(name, description, category, subcategory, defaultKey);
    }
}
