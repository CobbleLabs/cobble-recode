package cobble.v1_21.compat;

import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.ClientStatusC2SPacket;

/**
 * 1.8.9's C16PacketClientStatus on 1.21.11's ClientStatusC2SPacket. 1.21's
 * player inventory needs no opening, so that status makes no packet at all,
 * which the send shim drops.
 */
public final class C16PacketClientStatusShim {
    private C16PacketClientStatusShim() {}

    public static boolean isClientStatus(Object packet) {
        return packet instanceof ClientStatusC2SPacket;
    }

    public static Packet<?> create(ClientStatusMode mode) {
        return switch (mode) {
            case PERFORM_RESPAWN -> new ClientStatusC2SPacket(ClientStatusC2SPacket.Mode.PERFORM_RESPAWN);
            case REQUEST_STATS -> new ClientStatusC2SPacket(ClientStatusC2SPacket.Mode.REQUEST_STATS);
            case OPEN_INVENTORY_ACHIEVEMENT -> null;
        };
    }

    public static ClientStatusMode getStatus(Packet<?> packet) {
        return ((ClientStatusC2SPacket) packet).getMode() == ClientStatusC2SPacket.Mode.PERFORM_RESPAWN
                ? ClientStatusMode.PERFORM_RESPAWN : ClientStatusMode.REQUEST_STATS;
    }
}
