package cobble.module.movement;

import cobble.event.MoveEvent;
import cobble.event.PostMoveEvent;
import cobble.event.Subscribe;
import cobble.module.Category;
import cobble.module.Module;
import cobble.module.Setting;
import cobble.module.Subcategory;
import net.minecraft.client.entity.EntityPlayerSP;

/**
 * Keeps you from walking off edges. The game stops a sneaking player at an
 * edge while it moves them, so the sneak key reads as held for that move only.
 */
public final class SafeWalk extends Module {
    private final Setting directionCheck = addBoolean("Direction Check",
            "Walking straight forward still lets you step off the edge", true);

    private boolean savedSneak;
    private boolean overridden;

    public SafeWalk() {
        super("SafeWalk", "Stops you from falling off edges", Category.MOVEMENT, Subcategory.ADVANCED);
    }

    @Subscribe
    public void onMove(MoveEvent event) {
        EntityPlayerSP player = mc.thePlayer;
        if (directionCheck.bool() && !player.isSneaking() && player.moveForward > 0.0f
                && player.moveStrafing == 0.0f) {
            return;
        }
        savedSneak = player.movementInput.sneak;
        player.movementInput.sneak = true;
        overridden = true;
    }

    @Subscribe
    public void onPostMove(PostMoveEvent event) {
        if (overridden) {
            mc.thePlayer.movementInput.sneak = savedSneak;
            overridden = false;
        }
    }
}
