package cobble.module.player;

import cobble.core.Time;
import cobble.event.PacketReceiveEvent;
import cobble.event.Subscribe;
import cobble.event.UpdateWalkingPlayerEvent;
import cobble.module.Category;
import cobble.module.Module;
import cobble.module.ModuleManager;
import cobble.module.Setting;
import cobble.module.Subcategory;
import cobble.module.movement.Fly;
import cobble.module.movement.Speed;
import net.minecraft.block.material.Material;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.util.BlockPos;

/**
 * Keeps you out of the void. Falling with nothing under you, the move is taken
 * back up by the distance fallen, which the server answers by setting you back
 * where you came from.
 */
public final class AntiFall extends Module {
    private static final long LAGBACK_TIMEOUT_MS = 250;

    private final Setting speedCheck = addBoolean("Speed Check", "Leave falls alone while Speed is on", false);
    private final Setting fallDistance = addNumber("Fall Dist", "Blocks to fall before pulling you back", 2.0f, 0.1f,
            5.0f, 1);

    private boolean lagbackPending;
    private long lagbackSince;

    public AntiFall() {
        super("AntiFall", "Prevents you from falling into the void", Category.PLAYER, Subcategory.ADVANCED);
    }

    @Subscribe
    public void onPacketReceive(PacketReceiveEvent event) {
        EntityPlayerSP player = mc.thePlayer;
        if (player != null && event.getPacket() instanceof S08PacketPlayerPosLook) {
            player.fallDistance = 0.0f;
            player.motionX = 0.0;
            player.motionZ = 0.0;
            lagbackPending = false;
            lagbackSince = Time.nowMillis();
        }
    }

    @Subscribe
    public void onUpdateWalkingPlayer(UpdateWalkingPlayerEvent event) {
        EntityPlayerSP player = mc.thePlayer;
        if (player.isDead || player.isOnLadder() || player.capabilities.isCreativeMode || player.capabilities.isFlying
                || ModuleManager.get(Freecam.class).enabled()
                || ModuleManager.get(Fly.class).enabled()
                || speedCheck.bool() && ModuleManager.get(Speed.class).enabled()) {
            return;
        }
        if (!lagbackPending && groundBelow(player)) {
            return;
        }
        long now = Time.nowMillis();
        if (lagbackPending && now - lagbackSince >= LAGBACK_TIMEOUT_MS || player.isCollidedVertically) {
            lagbackPending = false;
            lagbackSince = now;
            return;
        }
        if (player.fallDistance < fallDistance.number() || !air(player, player.posY - 1.0)) {
            return;
        }
        if (!lagbackPending) {
            lagbackPending = true;
            lagbackSince = now;
            return;
        }
        player.movementInput.moveForward = 0.0f;
        player.movementInput.moveStrafe = 0.0f;
        player.motionX = 0.0;
        player.motionZ = 0.0;
        player.setPosition(player.posX, player.posY + player.fallDistance, player.posZ);
        player.fallDistance = 0.0f;
    }

    private boolean groundBelow(EntityPlayerSP player) {
        for (double y = player.posY - 1.0; y > 0.0; y -= 1.0) {
            if (!air(player, y)) {
                return true;
            }
        }
        return false;
    }

    private boolean air(EntityPlayerSP player, double y) {
        return mc.theWorld.getBlockState(new BlockPos(player.posX, y, player.posZ)).getBlock().getMaterial()
                == Material.air;
    }
}
