package cobble.v1_21.compat;

import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;

/**
 * 1.8.9's InventoryPlayer on 1.21.11's PlayerInventory. The arrays 1.8 kept
 * the stacks in are copies here: reading through them works, writing into
 * them changes nothing.
 */
public final class InventoryPlayerShim {
    private static final EquipmentSlot[] ARMOR = {
        EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD
    };

    private InventoryPlayerShim() {}

    public static int currentItem(PlayerInventory inventory) {
        return inventory.getSelectedSlot();
    }

    public static void currentItem(PlayerInventory inventory, int slot) {
        inventory.setSelectedSlot(slot);
    }

    public static ItemStack[] mainInventory(PlayerInventory inventory) {
        ItemStack[] stacks = new ItemStack[inventory.getMainStacks().size()];
        for (int slot = 0; slot < stacks.length; ++slot) {
            stacks[slot] = ItemStacks.orNull(inventory.getMainStacks().get(slot));
        }
        return stacks;
    }

    public static ItemStack[] armorInventory(PlayerInventory inventory) {
        ItemStack[] stacks = new ItemStack[ARMOR.length];
        for (int slot = 0; slot < stacks.length; ++slot) {
            stacks[slot] = armorItemInSlot(inventory, slot);
        }
        return stacks;
    }

    public static ItemStack armorItemInSlot(PlayerInventory inventory, int slot) {
        return ItemStacks.orNull(inventory.player.getEquippedStack(ARMOR[slot]));
    }

    /* The scroll wheel's step: one slot against the direction, wrapping round the hotbar. */
    public static void changeCurrentItem(PlayerInventory inventory, int direction) {
        int step = Integer.signum(direction);
        int size = PlayerInventory.getHotbarSize();
        inventory.setSelectedSlot(Math.floorMod(inventory.getSelectedSlot() - step, size));
    }

    public static ItemStack getCurrentItem(PlayerInventory inventory) {
        return ItemStacks.orNull(inventory.getSelectedStack());
    }

    public static int getFirstEmptyStack(PlayerInventory inventory) {
        return inventory.getEmptySlot();
    }

    /** What the cursor holds while a container is open. */
    public static ItemStack getItemStack(PlayerInventory inventory) {
        return ItemStacks.orNull(inventory.player.currentScreenHandler.getCursorStack());
    }

    public static ItemStack getStackInSlot(PlayerInventory inventory, int slot) {
        return ItemStacks.orNull(inventory.getStack(slot));
    }
}
