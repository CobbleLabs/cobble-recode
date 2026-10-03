package cobble.module.player;

import cobble.event.PostWalkingUpdateEvent;
import cobble.event.Subscribe;
import cobble.event.UpdateWalkingPlayerEvent;
import cobble.module.Category;
import cobble.module.Module;
import cobble.module.ModuleManager;
import cobble.module.Setting;
import cobble.module.Subcategory;
import cobble.module.movement.Fly;
import java.util.List;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.network.play.client.C03PacketPlayer;

/**
 * Takes no fall damage. Normal tells the server you are on the ground for the
 * whole fall; AntiCheat lands you for one packet every three blocks fallen, so
 * no single landing is ever far enough to hurt.
 */
public final class NoFall extends Module {
    private static final int NORMAL = 0;
    /* Falls shorter than this never deal damage. */
    private static final float SAFE_FALL = 2.224f;

    private final Setting mode = addChoice("Mode", "Normal for vanilla servers; AntiCheat for most anticheats",
            List.of("Normal", "AntiCheat"), NORMAL);

    private float lastLanding;

    public NoFall() {
        super("NoFall", "Prevents fall damage", Category.PLAYER, Subcategory.ADVANCED);
    }

    @Override
    public String suffix() {
        return mode.choiceName();
    }

    @Subscribe
    public void onUpdateWalkingPlayer(UpdateWalkingPlayerEvent event) {
        EntityPlayerSP player = mc.thePlayer;
        if (skipped(player)) {
            return;
        }
        if (mode.choice() == NORMAL) {
            if (falling(player, SAFE_FALL)) {
                player.onGround = true;
                /* On the ground for the packet only: a jump now would start from mid-air. */
                player.jumpTicks = 1;
                if (mc.gameSettings.keyBindJump.isPressed()) {
                    KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), false);
                }
            }
            return;
        }
        if (lastLanding > player.fallDistance) {
            lastLanding = 0.0f;
        }
        if (falling(player, 3.0f) && player.fallDistance - lastLanding > 3.0f) {
            lastLanding = player.fallDistance;
            mc.getNetHandler().addToSendQueue(new C03PacketPlayer(true));
            mc.getNetHandler().addToSendQueue(new C03PacketPlayer(false));
        }
    }

    @Subscribe
    public void onPostWalkingUpdate(PostWalkingUpdateEvent event) {
        EntityPlayerSP player = mc.thePlayer;
        if (mode.choice() == NORMAL && !skipped(player) && falling(player, SAFE_FALL)) {
            player.onGround = false;
        }
    }

    private static boolean falling(EntityPlayerSP player, float distance) {
        return player.fallDistance > distance && player.motionY < 0.0;
    }

    private static boolean skipped(EntityPlayerSP player) {
        return player == null || player.isDead || player.capabilities.isCreativeMode || player.capabilities.isFlying
                || ModuleManager.get(Fly.class).enabled();
    }
}
