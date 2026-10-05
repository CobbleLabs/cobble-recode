package cobble.v1_21.compat;

import net.minecraft.network.packet.s2c.play.EntitySetHeadYawS2CPacket;

/** 1.8.9's S19PacketEntityHeadLook on 1.21.11's EntitySetHeadYawS2CPacket; 1.8 sent the angle in 1/256 turns. */
public final class S19PacketEntityHeadLookShim {
    private S19PacketEntityHeadLookShim() {}

    public static byte getYaw(EntitySetHeadYawS2CPacket packet) {
        return (byte) Math.floor(packet.getHeadYaw() * 256.0f / 360.0f);
    }
}
