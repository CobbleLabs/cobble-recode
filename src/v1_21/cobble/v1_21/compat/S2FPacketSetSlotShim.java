package cobble.v1_21.compat;

import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;

/** 1.8.9's S2FPacketSetSlot on 1.21.11's ScreenHandlerSlotUpdateS2CPacket. */
public final class S2FPacketSetSlotShim {
    private S2FPacketSetSlotShim() {}

    public static int func_149175_c(ScreenHandlerSlotUpdateS2CPacket packet) {
        return packet.getSyncId();
    }

    public static int func_149173_d(ScreenHandlerSlotUpdateS2CPacket packet) {
        return packet.getSlot();
    }

    public static ItemStack func_149174_e(ScreenHandlerSlotUpdateS2CPacket packet) {
        return ItemStacks.orNull(packet.getStack());
    }
}
