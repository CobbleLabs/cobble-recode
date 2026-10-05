package cobble.v1_21.compat;

import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

/** 1.8.9's C04PacketPlayerPosition on 1.21.11's PositionAndOnGround. */
public final class C03PacketPlayer_C04PacketPlayerPositionShim {
    private C03PacketPlayer_C04PacketPlayerPositionShim() {}

    public static PlayerMoveC2SPacket.PositionAndOnGround create(double x, double y, double z, boolean onGround) {
        return new PlayerMoveC2SPacket.PositionAndOnGround(x, y, z, onGround, C03PacketPlayerShim.collided());
    }
}
