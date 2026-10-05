package cobble.v1_21.compat;

import java.util.Collection;
import java.util.UUID;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.network.packet.Packet;

/** 1.8.9's NetHandlerPlayClient on 1.21.11's ClientPlayNetworkHandler. */
public final class NetHandlerPlayClientShim {
    private NetHandlerPlayClientShim() {}

    /* A 1.8 packet with no 1.21 counterpart comes out of its shim as null and is not sent. */
    public static void addToSendQueue(ClientPlayNetworkHandler handler, Packet<?> packet) {
        if (packet != null) {
            handler.sendPacket(packet);
        }
    }

    public static PlayerListEntry getPlayerInfo(ClientPlayNetworkHandler handler, UUID id) {
        return handler.getPlayerListEntry(id);
    }

    public static Collection<PlayerListEntry> getPlayerInfoMap(ClientPlayNetworkHandler handler) {
        return handler.getPlayerList();
    }
}
