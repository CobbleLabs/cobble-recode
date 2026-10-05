package cobble.v1_21.compat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/** 1.8.9's C02PacketUseEntity on 1.21.11's PlayerInteractEntityC2SPacket; everything is the main hand's. */
public final class C02PacketUseEntityShim {
    private C02PacketUseEntityShim() {}

    private static boolean sneaking() {
        return MinecraftClient.getInstance().player.isSneaking();
    }

    public static PlayerInteractEntityC2SPacket create(Entity entity, UseEntityAction action) {
        return action == UseEntityAction.ATTACK ? PlayerInteractEntityC2SPacket.attack(entity, sneaking())
                : PlayerInteractEntityC2SPacket.interact(entity, sneaking(), Hand.MAIN_HAND);
    }

    public static PlayerInteractEntityC2SPacket create(Entity entity, Vec3d hit) {
        return PlayerInteractEntityC2SPacket.interactAt(entity, sneaking(), Hand.MAIN_HAND, hit);
    }

    public static UseEntityAction getAction(PlayerInteractEntityC2SPacket packet) {
        UseEntityAction[] action = new UseEntityAction[1];
        packet.handle(new PlayerInteractEntityC2SPacket.Handler() {
            @Override
            public void interact(Hand hand) {
                action[0] = UseEntityAction.INTERACT;
            }

            @Override
            public void interactAt(Hand hand, Vec3d pos) {
                action[0] = UseEntityAction.INTERACT_AT;
            }

            @Override
            public void attack() {
                action[0] = UseEntityAction.ATTACK;
            }
        });
        return action[0];
    }

    public static Entity getEntityFromWorld(PlayerInteractEntityC2SPacket packet, World world) {
        return world.getEntityById(packet.entityId);
    }
}
