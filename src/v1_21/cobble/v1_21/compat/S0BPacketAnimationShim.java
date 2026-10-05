package cobble.v1_21.compat;

import net.minecraft.network.packet.s2c.play.EntityAnimationS2CPacket;

/** 1.8.9's S0BPacketAnimation on 1.21.11's EntityAnimationS2CPacket. */
public final class S0BPacketAnimationShim {
    private S0BPacketAnimationShim() {}

    public static int getAnimationType(EntityAnimationS2CPacket packet) {
        return packet.getAnimationId();
    }

    public static int getEntityID(EntityAnimationS2CPacket packet) {
        return packet.getEntityId();
    }
}
