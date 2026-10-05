package cobble.v1_21.compat;

import net.minecraft.item.ItemStack;

/** 1.8 spells an empty slot as null, 1.21 as {@link ItemStack#EMPTY}; every shim crossing the line converts. */
final class ItemStacks {
    private ItemStacks() {}

    static ItemStack orNull(ItemStack stack) {
        return stack.isEmpty() ? null : stack;
    }

    static ItemStack orEmpty(ItemStack stack) {
        return stack != null ? stack : ItemStack.EMPTY;
    }
}
