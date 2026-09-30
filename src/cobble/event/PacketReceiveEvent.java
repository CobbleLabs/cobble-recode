package cobble.event;

import net.minecraft.network.Packet;

public final class PacketReceiveEvent extends PacketEvent {
    public PacketReceiveEvent(Packet<?> packet) {
        super(packet);
    }
}
