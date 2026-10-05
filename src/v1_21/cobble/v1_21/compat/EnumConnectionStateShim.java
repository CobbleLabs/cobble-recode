package cobble.v1_21.compat;

import net.minecraft.network.NetworkPhase;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.packet.Packet;

/**
 * 1.8.9's EnumConnectionState on 1.21.11's NetworkPhase. The legacy code only
 * asks for an id to learn whether the packet belongs to the phase and side;
 * 1.21 packets name their side, and any id stands for yes.
 */
public final class EnumConnectionStateShim {
    private EnumConnectionStateShim() {}

    public static Integer getPacketId(NetworkPhase phase, NetworkSide side, Packet<?> packet) {
        return packet.getPacketType().side() == side ? 0 : null;
    }
}
