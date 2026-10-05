package cobble.v1_21.compat;

import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;

/** 1.8.9's S13PacketDestroyEntities on 1.21.11's EntitiesDestroyS2CPacket. */
public final class S13PacketDestroyEntitiesShim {
    private S13PacketDestroyEntitiesShim() {}

    public static int[] getEntityIDs(EntitiesDestroyS2CPacket packet) {
        return packet.getEntityIds().toIntArray();
    }
}
