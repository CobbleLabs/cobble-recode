package cobble.v1_21.compat;

import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;

/** 1.8.9's S19PacketEntityStatus on 1.21.11's EntityStatusS2CPacket. */
public final class S19PacketEntityStatusShim {
    private S19PacketEntityStatusShim() {}

    public static byte getOpCode(EntityStatusS2CPacket packet) {
        return packet.getStatus();
    }
}
