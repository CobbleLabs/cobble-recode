package cobble.v1_21.compat;

import net.minecraft.network.packet.s2c.play.EntityS2CPacket;

/**
 * 1.8.9's S14PacketEntity on 1.21.11's EntityS2CPacket. 1.8 moved entities
 * in bytes of 1/32 block, 1.21 in shorts of 1/4096; angles are both 1/256 turn.
 */
public final class S14PacketEntityShim {
    private static final int SHORT_PER_BYTE = 4096 / 32;

    private S14PacketEntityShim() {}

    public static int entityId(EntityS2CPacket packet) {
        return packet.id;
    }

    public static byte func_149062_c(EntityS2CPacket packet) {
        return (byte) (packet.getDeltaX() / SHORT_PER_BYTE);
    }

    public static byte func_149061_d(EntityS2CPacket packet) {
        return (byte) (packet.getDeltaY() / SHORT_PER_BYTE);
    }

    public static byte func_149064_e(EntityS2CPacket packet) {
        return (byte) (packet.getDeltaZ() / SHORT_PER_BYTE);
    }

    public static byte func_149066_f(EntityS2CPacket packet) {
        return packet.yaw;
    }

    public static byte func_149063_g(EntityS2CPacket packet) {
        return packet.pitch;
    }

    public static boolean func_149060_h(EntityS2CPacket packet) {
        return packet.hasRotation();
    }
}
