package cobble.v1_21.compat;

import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;

/** 1.8.9's S12PacketEntityVelocity on 1.21.11's; 1.8 sent velocity in 1/8000 blocks a tick. */
public final class S12PacketEntityVelocityShim {
    private static final double UNITS = 8000.0;

    private S12PacketEntityVelocityShim() {}

    public static int getEntityID(EntityVelocityUpdateS2CPacket packet) {
        return packet.getEntityId();
    }

    public static int getMotionX(EntityVelocityUpdateS2CPacket packet) {
        return (int) (packet.getVelocity().x * UNITS);
    }

    public static int getMotionY(EntityVelocityUpdateS2CPacket packet) {
        return (int) (packet.getVelocity().y * UNITS);
    }

    public static int getMotionZ(EntityVelocityUpdateS2CPacket packet) {
        return (int) (packet.getVelocity().z * UNITS);
    }
}
