package cobble.module;

import cobble.platform.Platform;
import net.minecraft.client.Minecraft;

/** A module written straight against 1.8.9's MCP-named game. */
public abstract class LegacyModule extends Module {
    protected static final Minecraft mc = Minecraft.getMinecraft();

    protected LegacyModule(String name, String description, Category category, Subcategory subcategory) {
        super(name, description, category, subcategory);
    }

    protected LegacyModule(String name, String description, Category category, Subcategory subcategory,
                           int defaultKey) {
        super(name, description, category, subcategory, defaultKey);
    }

    /* Where this code's GL calls are recorded and replayed later, they land in the game's frame whatever the layer does. */
    @Override
    protected boolean canStreamProof() {
        return Platform.get().drawsImmediately() && super.canStreamProof();
    }
}
