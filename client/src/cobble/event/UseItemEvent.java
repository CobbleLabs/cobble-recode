package cobble.event;

import net.minecraft.item.ItemStack;

public final class UseItemEvent extends Event {
    public final ItemStack itemStack;

    public UseItemEvent(ItemStack itemStack) {
        this.itemStack = itemStack;
    }
}
