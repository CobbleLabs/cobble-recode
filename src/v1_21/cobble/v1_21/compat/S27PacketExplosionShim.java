package cobble.v1_21.compat;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.util.math.Vec3d;

/**
 * 1.8.9's S27PacketExplosion on 1.21.11's, which is a record: the push 1.8
 * modules wrote into the packet is kept beside it, and the explosion hook
 * hands it to the game in place of the packet's own.
 */
public final class S27PacketExplosionShim {
    private static final Map<ExplosionS2CPacket, Vec3d> PUSHES = Collections.synchronizedMap(new WeakHashMap<>());

    private S27PacketExplosionShim() {}

    public static Vec3d push(ExplosionS2CPacket packet) {
        Vec3d written = PUSHES.get(packet);
        return written != null ? written : packet.playerKnockback().orElse(Vec3d.ZERO);
    }

    public static boolean pushWritten(ExplosionS2CPacket packet) {
        return PUSHES.containsKey(packet);
    }

    public static float func_149149_c(ExplosionS2CPacket packet) {
        return (float) push(packet).x;
    }

    public static float func_149144_d(ExplosionS2CPacket packet) {
        return (float) push(packet).y;
    }

    public static float func_149147_e(ExplosionS2CPacket packet) {
        return (float) push(packet).z;
    }

    public static float field_149152_f(ExplosionS2CPacket packet) {
        return func_149149_c(packet);
    }

    public static float field_149153_g(ExplosionS2CPacket packet) {
        return func_149144_d(packet);
    }

    public static float field_149159_h(ExplosionS2CPacket packet) {
        return func_149147_e(packet);
    }

    public static void field_149152_f(ExplosionS2CPacket packet, float x) {
        Vec3d push = push(packet);
        PUSHES.put(packet, new Vec3d(x, push.y, push.z));
    }

    public static void field_149153_g(ExplosionS2CPacket packet, float y) {
        Vec3d push = push(packet);
        PUSHES.put(packet, new Vec3d(push.x, y, push.z));
    }

    public static void field_149159_h(ExplosionS2CPacket packet, float z) {
        Vec3d push = push(packet);
        PUSHES.put(packet, new Vec3d(push.x, push.y, z));
    }
}
