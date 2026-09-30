package cobble.module.player;

import cobble.core.Timer;
import cobble.game.CrosshairTarget;
import cobble.game.FrameState;
import cobble.game.Game;
import cobble.hook.Input;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.Setting;
import cobble.module.Subcategory;
import cobble.render.Icon;

/**
 * Raven-b's Auto Tool. Swaps the hotbar to whatever breaks the block under the
 * crosshair fastest, and swaps back when the crosshair leaves it. The swap is a
 * real slot change, so the server is told about it exactly as it would be for
 * a scroll.
 */
public final class AutoTool extends Module {
    private final Setting activation = addNumber("Activation time",
            "Milliseconds the attack button must be held first", 0, 0, 1000, 0);
    private final Setting hoverDelay = addNumber("Hover delay", "Milliseconds the crosshair must rest on the block",
            0, 0, 1000, 0);
    private final Setting onlyCrouching = addBoolean("Only while crouching",
            "Restrict the swap to a sneaking player", false);
    private final Setting requireAttack = addBoolean("Require mouse down", "Only while the attack button is held",
            true);
    private final Setting switchBack = addBoolean("Switch back when done", "Return to the previous slot afterwards",
            true);
    private final Setting weapons = addBoolean("Keep weapons", "Never swap away from a sword or an axe", false);

    private final Timer attackHeldSince = new Timer();
    private final Timer hoverSince = new Timer();
    private boolean wasAttacking;
    private boolean hovering;
    private int previousSlot = -1;

    public AutoTool() {
        super("AutoTool", "Swaps to the fastest tool for whatever you are breaking", Category.PLAYER,
                Subcategory.BASIC);
        setIcon(Icon.WRENCH);
    }

    @Override
    public void onEnable(Context context) {
        wasAttacking = false;
        hovering = false;
        previousSlot = -1;
    }

    @Override
    public void onDisable(Context context) {
        restore(context);
    }

    private void restore(Context context) {
        if (previousSlot >= 0 && switchBack.bool() && context.state.player.valid) {
            Game.setSelectedSlot(previousSlot);
        }
        previousSlot = -1;
    }

    private void equip(int slot) {
        int current = Game.selectedSlot();
        if (slot == current) {
            return;
        }
        /* Remembered once. Swapping between two tools while breaking a wall
         * must not overwrite the slot the player started on. */
        if (previousSlot < 0) {
            previousSlot = current;
        }
        Game.setSelectedSlot(slot);
    }

    /** Hotbar slot that breaks the block fastest, or -1 when nothing in the hotbar is better than a fist. */
    private static int bestSlot(CrosshairTarget target) {
        int best = -1;
        float bestSpeed = 1.0f;
        for (int slot = 0; slot <= 8; ++slot) {
            float speed = Game.toolSpeed(slot, target.x(), target.y(), target.z());
            if (speed > bestSpeed) {
                bestSpeed = speed;
                best = slot;
            }
        }
        return best;
    }

    @Override
    public void onTick(Context context) {
        FrameState state = context.state;
        if (!state.inWorld || !state.player.valid || state.screenOpen) {
            restore(context);
            hovering = false;
            wasAttacking = false;
            return;
        }

        boolean attacking = Input.attackHeld();
        if (attacking && !wasAttacking) {
            attackHeldSince.reset();
        }
        wasAttacking = attacking;

        CrosshairTarget target = Game.crosshair();
        if (target.kind() != CrosshairTarget.Kind.BLOCK) {
            hovering = false;
            restore(context);
            return;
        }
        if (!hovering) {
            hovering = true;
            hoverSince.reset();
        }

        if (onlyCrouching.bool() && !state.player.sneaking) {
            restore(context);
            return;
        }
        if (requireAttack.bool() && (!attacking || !attackHeldSince.elapsed((long) activation.number()))) {
            restore(context);
            return;
        }
        if (!hoverSince.elapsed((long) hoverDelay.number())) {
            restore(context);
            return;
        }
        /* Using an item is a different intent entirely - eating, drawing a bow,
         * placing - and a slot change would cancel it outright. */
        if (state.player.usingItem || Input.useHeld()) {
            restore(context);
            return;
        }
        if (weapons.bool() && previousSlot < 0 && Game.holdingWeapon()) {
            return;
        }

        int slot = bestSlot(target);
        if (slot >= 0) {
            equip(slot);
        }
    }
}
