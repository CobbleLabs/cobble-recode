package cobble.module.movement;

import cobble.event.PreUpdateEvent;
import cobble.event.Subscribe;
import cobble.game.Game;
import cobble.module.Category;
import cobble.module.Module;
import cobble.module.ModuleManager;
import cobble.module.Subcategory;
import cobble.module.player.Freecam;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.AxisAlignedBB;

/**
 * Jumps at the last moment off the edge of a block: walking forward on the
 * ground with nothing under your feet and nothing a block ahead, it taps jump
 * for one tick.
 */
public final class Parkour extends Module {
    private boolean jumpPending;
    private boolean jumpWasHeld;

    public Parkour() {
        super("Parkour", "Jumps for you at the edge of blocks", Category.MOVEMENT, Subcategory.BASIC);
    }

    @Subscribe
    public void onPreUpdate(PreUpdateEvent event) {
        if (ModuleManager.get(Freecam.class).enabled()) {
            return;
        }
        KeyBinding jump = mc.gameSettings.keyBindJump;
        if (jumpPending) {
            if (!jumpWasHeld) {
                KeyBinding.setKeyBindState(jump.getKeyCode(), false);
            }
            jumpPending = false;
            jumpWasHeld = false;
            return;
        }
        EntityPlayerSP player = mc.thePlayer;
        boolean forward = player.movementInput.moveForward > 0.0f || Game.bindingPhysicallyHeld("forward");
        if (jump.isKeyDown() || !forward || !player.onGround) {
            return;
        }
        if (unsupported(player, 0.0) && unsupported(player, 1.0)) {
            jumpWasHeld = jump.isKeyDown();
            KeyBinding.setKeyBindState(jump.getKeyCode(), true);
            KeyBinding.onTick(jump.getKeyCode());
            jumpPending = true;
        }
    }

    /* Nothing to stand on a tenth of a block down, this far ahead along the look. */
    private boolean unsupported(EntityPlayerSP player, double ahead) {
        double yaw = Math.toRadians(player.rotationYaw + 90.0);
        AxisAlignedBB box = player.getEntityBoundingBox().offset(Math.cos(yaw) * ahead, -0.1, Math.sin(yaw) * ahead);
        return mc.theWorld.getCollidingBoundingBoxes(player, box).isEmpty();
    }
}
