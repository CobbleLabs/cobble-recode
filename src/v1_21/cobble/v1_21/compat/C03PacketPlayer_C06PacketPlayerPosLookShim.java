package cobble.v1_21.compat;

import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

/** 1.8.9's C06PacketPlayerPosLook on 1.21.11's Full. */
public final class C03PacketPlayer_C06PacketPlayerPosLookShim {
    private C03PacketPlayer_C06PacketPlayerPosLookShim() {}

    public static PlayerMoveC2SPacket.Full create(double x, double y, double z, float yaw, float pitch,
                                                  boolean onGround) {
        return new PlayerMoveC2SPacket.Full(x, y, z, yaw, pitch, onGround, C03PacketPlayerShim.collided());
    }
}
