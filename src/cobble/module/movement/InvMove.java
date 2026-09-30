package cobble.module.movement;

import cobble.game.FrameState;
import cobble.game.Game;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;

/**
 * A screen swallows key presses, so the physical state of the movement keys is
 * forwarded to their bindings every tick. Only the inventory counts: typing in
 * chat must never walk the player.
 */
public final class InvMove extends Module {
    private static final String[] BINDINGS = {"forward", "back", "left", "right", "jump", "sneak"};

    private boolean active;

    public InvMove() {
        super("InvMove", "Walks while the inventory is open", Category.MOVEMENT);
    }

    private void releaseAll() {
        for (String binding : BINDINGS) {
            Game.setBinding(binding, false);
        }
        active = false;
    }

    @Override
    public void onDisable(Context context) {
        if (active) {
            releaseAll();
        }
    }

    @Override
    public void onTick(Context context) {
        FrameState state = context.state;
        if (!state.inWorld || !state.player.valid || !state.screenOpen || !Game.inventoryOpen()) {
            if (active) {
                releaseAll();
            }
            return;
        }
        active = true;
        for (String binding : BINDINGS) {
            Game.setBinding(binding, Game.bindingPhysicallyHeld(binding));
        }
    }
}
