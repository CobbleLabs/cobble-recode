package cobble.v1_21.compat;

import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;

/** 1.8.9's C07PacketPlayerDigging on 1.21.11's PlayerActionC2SPacket, whose actions keep 1.8's names. */
public final class C07PacketPlayerDiggingShim {
    private C07PacketPlayerDiggingShim() {}

    public static PlayerActionC2SPacket.Action getStatus(PlayerActionC2SPacket packet) {
        return packet.getAction();
    }
}
