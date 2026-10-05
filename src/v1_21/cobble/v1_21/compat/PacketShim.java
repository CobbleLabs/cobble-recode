package cobble.v1_21.compat;

import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.packet.Packet;

/** 1.8.9's Packet on 1.21.11's. */
public final class PacketShim {
    private PacketShim() {}

    /* A packet is handled by the listener of its side, which is the one the legacy code always hands it. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void processPacket(Packet packet, PacketListener listener) {
        packet.apply(listener);
    }
}
