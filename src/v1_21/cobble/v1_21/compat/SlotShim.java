package cobble.v1_21.compat;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;

/** 1.8.9's Slot on 1.21.11's. */
public final class SlotShim {
    private SlotShim() {}

    public static int slotNumber(Slot slot) {
        return slot.id;
    }

    public static int xDisplayPosition(Slot slot) {
        return slot.x;
    }

    public static int yDisplayPosition(Slot slot) {
        return slot.y;
    }

    public static boolean canTakeStack(Slot slot, PlayerEntity player) {
        return slot.canTakeItems(player);
    }

    public static boolean getHasStack(Slot slot) {
        return slot.hasStack();
    }

    public static ItemStack getStack(Slot slot) {
        return ItemStacks.orNull(slot.getStack());
    }
}
