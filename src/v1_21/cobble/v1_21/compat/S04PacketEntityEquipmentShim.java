package cobble.v1_21.compat;

import com.mojang.datafixers.util.Pair;
import java.util.List;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.EntityEquipmentUpdateS2CPacket;

/**
 * 1.8.9's S04PacketEntityEquipment on 1.21.11's, which carries every slot
 * that changed where 1.8's carried one: the held item when it is there, as
 * that is what the legacy code watches, else the first slot.
 */
public final class S04PacketEntityEquipmentShim {
    private S04PacketEntityEquipmentShim() {}

    public static int getEntityID(EntityEquipmentUpdateS2CPacket packet) {
        return packet.getEntityId();
    }

    public static ItemStack getItemStack(EntityEquipmentUpdateS2CPacket packet) {
        Pair<EquipmentSlot, ItemStack> slot = primary(packet);
        return slot == null ? null : ItemStacks.orNull(slot.getSecond());
    }

    /** 1.8's numbering: the held item, then the boots up to the helmet; the off hand had none and is -1. */
    public static int getEquipmentSlot(EntityEquipmentUpdateS2CPacket packet) {
        Pair<EquipmentSlot, ItemStack> slot = primary(packet);
        if (slot == null) {
            return -1;
        }
        return switch (slot.getFirst()) {
            case MAINHAND -> 0;
            case FEET -> 1;
            case LEGS -> 2;
            case CHEST -> 3;
            case HEAD -> 4;
            default -> -1;
        };
    }

    private static Pair<EquipmentSlot, ItemStack> primary(EntityEquipmentUpdateS2CPacket packet) {
        List<Pair<EquipmentSlot, ItemStack>> slots = packet.getEquipmentList();
        for (Pair<EquipmentSlot, ItemStack> slot : slots) {
            if (slot.getFirst() == EquipmentSlot.MAINHAND) {
                return slot;
            }
        }
        return slots.isEmpty() ? null : slots.get(0);
    }
}
