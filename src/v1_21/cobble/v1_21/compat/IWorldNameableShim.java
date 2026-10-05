package cobble.v1_21.compat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.util.Nameable;

/**
 * 1.8.9's IWorldNameable on 1.21.11's Nameable. 1.8's inventories were
 * named, and the legacy code asks a chest's for what its screen is titled;
 * 1.21's are not, so an inventory answers with the title of the container
 * screen open. The receiver is typed Nameable for the calls 1.8 made through
 * IWorldNameable, and as an interface parameter it takes an inventory too.
 */
public final class IWorldNameableShim {
    private IWorldNameableShim() {}

    public static String getName(Nameable nameable) {
        return nameable.getName().getString();
    }

    public static Text getDisplayName(Nameable nameable) {
        Object self = nameable;
        if (self instanceof Nameable) {
            return nameable.getDisplayName();
        }
        return MinecraftClient.getInstance().currentScreen instanceof HandledScreen<?> screen ? screen.getTitle()
                : Text.empty();
    }

    /* A title the game made from its own translation is the default name, as an unnamed chest's was. */
    public static boolean hasCustomName(Nameable nameable) {
        Object self = nameable;
        if (self instanceof Nameable) {
            return nameable.hasCustomName();
        }
        return MinecraftClient.getInstance().currentScreen instanceof HandledScreen<?> screen
                && !(screen.getTitle().getContent() instanceof TranslatableTextContent);
    }
}
