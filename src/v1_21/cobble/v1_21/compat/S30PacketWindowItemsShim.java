package cobble.v1_21.compat;

import java.util.List;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;

/** 1.8.9's S30PacketWindowItems on 1.21.11's InventoryS2CPacket. */
public final class S30PacketWindowItemsShim {
    private S30PacketWindowItemsShim() {}

    public static int windowId(InventoryS2CPacket packet) {
        return packet.syncId();
    }

    public static ItemStack[] getItemStacks(InventoryS2CPacket packet) {
        List<ItemStack> contents = packet.contents();
        ItemStack[] stacks = new ItemStack[contents.size()];
        for (int slot = 0; slot < stacks.length; ++slot) {
            stacks[slot] = ItemStacks.orNull(contents.get(slot));
        }
        return stacks;
    }
}
