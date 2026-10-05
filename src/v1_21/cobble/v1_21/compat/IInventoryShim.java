package cobble.v1_21.compat;

import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;

/** 1.8.9's IInventory on 1.21.11's Inventory. */
public final class IInventoryShim {
    private IInventoryShim() {}

    public static int getSizeInventory(Inventory inventory) {
        return inventory.size();
    }

    public static ItemStack getStackInSlot(Inventory inventory, int slot) {
        return ItemStacks.orNull(inventory.getStack(slot));
    }
}
