package cobble.v1_21.compat;

import net.minecraft.network.packet.s2c.play.EntityPositionS2CPacket;

/** 1.8.9's S18PacketEntityTeleport on 1.21.11's EntityPositionS2CPacket; 1.8 placed entities in 1/32 blocks. */
public final class S18PacketEntityTeleportShim {
    private static final double UNITS = 32.0;

    private S18PacketEntityTeleportShim() {}

    public static int getEntityId(EntityPositionS2CPacket packet) {
        return packet.entityId();
    }

    public static int getX(EntityPositionS2CPacket packet) {
        return (int) Math.floor(packet.change().position().x * UNITS);
    }

    public static int getY(EntityPositionS2CPacket packet) {
        return (int) Math.floor(packet.change().position().y * UNITS);
    }

    public static int getZ(EntityPositionS2CPacket packet) {
        return (int) Math.floor(packet.change().position().z * UNITS);
    }

    public static byte getYaw(EntityPositionS2CPacket packet) {
        return (byte) Math.floor(packet.change().yaw() * 256.0f / 360.0f);
    }
}
