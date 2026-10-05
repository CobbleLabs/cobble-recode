package cobble.v1_21.compat;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.Identifier;

/** 1.8.9's GuiContainer on 1.21.11's HandledScreen. */
public final class GuiContainerShim {
    private GuiContainerShim() {}

    /* 1.8's inventory sheet, whose effect icons the legacy HUD would have drawn; 1.21 keeps them elsewhere. */
    public static Identifier inventoryBackground() {
        return Identifier.ofVanilla("textures/gui/container/inventory.png");
    }

    public static int guiLeft(HandledScreen<?> screen) {
        return screen.x;
    }

    public static int guiTop(HandledScreen<?> screen) {
        return screen.y;
    }

    public static ScreenHandler inventorySlots(HandledScreen<?> screen) {
        return screen.getScreenHandler();
    }

    public static Slot theSlot(HandledScreen<?> screen) {
        return screen.focusedSlot;
    }
}
