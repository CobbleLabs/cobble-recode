package cobble.v1_21.compat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

/** 1.8.9's C03PacketPlayer on 1.21.11's PlayerMoveC2SPacket, which also reports a horizontal collision. */
public final class C03PacketPlayerShim {
    private C03PacketPlayerShim() {}

    static boolean collided() {
        return MinecraftClient.getInstance().player.horizontalCollision;
    }

    public static PlayerMoveC2SPacket create(boolean onGround) {
        return new PlayerMoveC2SPacket.OnGroundOnly(onGround, collided());
    }

    public static float getYaw(PlayerMoveC2SPacket packet) {
        return packet.yaw;
    }

    public static float getPitch(PlayerMoveC2SPacket packet) {
        return packet.pitch;
    }

    public static boolean getRotating(PlayerMoveC2SPacket packet) {
        return packet.changesLook();
    }
}
