package cobble.v1_21.compat;

import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.item.ItemStack;

/** 1.8.9's GuiIngame on 1.21.11's InGameHud. */
public final class GuiIngameShim {
    private GuiIngameShim() {}

    public static ItemStack highlightingItemStack(InGameHud hud) {
        return ItemStacks.orNull(hud.currentStack);
    }

    public static void highlightingItemStack(InGameHud hud, ItemStack stack) {
        hud.currentStack = ItemStacks.orEmpty(stack);
    }

    public static int remainingHighlightTicks(InGameHud hud) {
        return hud.heldItemTooltipFade;
    }

    public static void remainingHighlightTicks(InGameHud hud, int ticks) {
        hud.heldItemTooltipFade = ticks;
    }
}
