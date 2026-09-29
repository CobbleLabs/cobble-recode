package cobble.module.movement;

import cobble.game.FrameState;
import cobble.game.Game;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;

public final class Sprint extends Module {
    public Sprint() {
        super("Sprint", "Keeps sprinting while moving", Category.MOVEMENT);
    }

    @Override
    public void onDisable(Context context) {
        if (context.state.player.valid) {
            Game.setSprinting(false);
        }
    }

    @Override
    public void onTick(Context context) {
        FrameState state = context.state;
        if (!state.inWorld || !state.player.valid || state.screenOpen || state.player.sneaking) {
            return;
        }
        // The game's view of the bindings, so scripted input counts exactly like a held key.
        boolean moving = Game.bindingPressed("forward") || Game.bindingPressed("back")
                || Game.bindingPressed("left") || Game.bindingPressed("right");
        if (moving && !state.player.sprinting) {
            Game.setSprinting(true);
        }
    }
}
