package cobble.v1_21.compat;

import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

/** 1.8.9's C05PacketPlayerLook on 1.21.11's LookAndOnGround. */
public final class C03PacketPlayer_C05PacketPlayerLookShim {
    private C03PacketPlayer_C05PacketPlayerLookShim() {}

    public static PlayerMoveC2SPacket.LookAndOnGround create(float yaw, float pitch, boolean onGround) {
        return new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, onGround, C03PacketPlayerShim.collided());
    }
}
