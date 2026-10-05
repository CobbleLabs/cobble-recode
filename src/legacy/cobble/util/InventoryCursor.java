package cobble.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Slot;
import org.lwjgl.input.Mouse;

/**
 * Glides the real cursor onto an inventory slot, for the legit modes that
 * show the move a hand would make before the click. Points are window pixels
 * with LWJGL's origin at the bottom left.
 */
public final class InventoryCursor {
    private int startX;
    private int startY;
    private int targetX;
    private int targetY;
    private long startedAt;
    private long duration;
    private boolean eased;

    /** Starts a move to the middle of a slot of the open container, nudged by up to jitter pixels; false without one. */
    public boolean begin(int containerSlot, long durationMs, int jitterX, int jitterY, boolean easeInOut) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!(mc.currentScreen instanceof GuiContainer gui) || containerSlot < 0
                || containerSlot >= gui.inventorySlots.inventorySlots.size()) {
            return false;
        }
        Slot slot = gui.inventorySlots.getSlot(containerSlot);
        int guiX = gui.guiLeft + slot.xDisplayPosition + 8;
        int guiY = gui.guiTop + slot.yDisplayPosition + 8;
        targetX = guiX * mc.displayWidth / gui.width + jitterX;
        targetY = mc.displayHeight - guiY * mc.displayHeight / gui.height - 1 + jitterY;
        startX = Mouse.getX();
        startY = Mouse.getY();
        startedAt = System.currentTimeMillis();
        duration = Math.max(1L, durationMs);
        eased = easeInOut;
        return true;
    }

    /** Moves the cursor along; true once it has arrived. */
    public boolean step() {
        float progress = Math.min(1.0f, (System.currentTimeMillis() - startedAt) / (float) duration);
        float travelled = eased ? progress * progress * (3.0f - 2.0f * progress) : progress;
        Mouse.setCursorPosition(startX + Math.round((targetX - startX) * travelled),
                startY + Math.round((targetY - startY) * travelled));
        return progress >= 1.0f;
    }
}
