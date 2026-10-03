package cobble.module.movement;

import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.ModuleManager;
import cobble.module.Setting;
import cobble.module.Subcategory;
import cobble.module.combat.Criticals;
import cobble.module.combat.WTap;
import net.minecraft.client.entity.EntityPlayerSP;

/**
 * Cobble's FastStop: kills the leftover slide the moment the movement keys are
 * released. Full strength stops on that tick; less bleeds it off over a few.
 */
public final class FastStop extends Module {
    private final Setting strength = addNumber("Stop Strength", "Share of the momentum removed each tick",
            100, 10, 100, 0);
    private final Setting onlyOnGround = addBoolean("Only On Ground", "Leave momentum alone in the air", true);

    private boolean wasMoving;

    public FastStop() {
        super("FastStop", "Stops the slide when you let go of the keys", Category.MOVEMENT, Subcategory.BASIC);
    }

    @Override
    public void onEnable(Context context) {
        wasMoving = false;
    }

    @Override
    public void onTick(Context context) {
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.currentScreen != null) {
            wasMoving = false;
            return;
        }
        /* A hop or a w-tap in progress is momentum the player meant to keep. */
        if (ModuleManager.get(Criticals.class).recentlyCritical(400) || ModuleManager.get(WTap.class).tapping()) {
            wasMoving = false;
            return;
        }
        if (onlyOnGround.bool() && !player.onGround) {
            return;
        }
        if (player.movementInput.moveForward != 0.0f || player.movementInput.moveStrafe != 0.0f) {
            wasMoving = true;
            return;
        }
        if (!wasMoving) {
            return;
        }
        double kept = 1.0 - strength.number() / 100.0;
        player.motionX = Math.abs(player.motionX * kept) < 0.001 ? 0.0 : player.motionX * kept;
        player.motionZ = Math.abs(player.motionZ * kept) < 0.001 ? 0.0 : player.motionZ * kept;
        if (player.motionX == 0.0 && player.motionZ == 0.0) {
            wasMoving = false;
        }
    }
}
