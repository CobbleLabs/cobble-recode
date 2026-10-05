package cobble.module.movement;

import cobble.game.FrameState;
import cobble.game.Game;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.LegacyModule;
import cobble.module.Setting;
import cobble.module.Subcategory;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.potion.Potion;

public final class Sprint extends LegacyModule {
    private final Setting cancelInvis = addBoolean("Cancel Invis",
            "Don't sprint while invisible, so no sprint particles give you away", false);

    public Sprint() {
        super("Sprint", "Keeps sprinting while moving", Category.MOVEMENT, Subcategory.BASIC);
    }

    @Override
    public void onDisable(Context context) {
        if (context.state.player.valid) {
            Game.get().setSprinting(false);
        }
    }

    @Override
    public void onTick(Context context) {
        FrameState state = context.state;
        if (!state.inWorld || !state.player.valid || state.screenOpen || state.player.sneaking) {
            return;
        }
        EntityPlayerSP player = mc.thePlayer;
        if (cancelInvis.bool() && player.isPotionActive(Potion.invisibility) && !player.capabilities.allowFlying) {
            return;
        }
        // The game's view of the bindings, so scripted input counts exactly like a held key.
        boolean moving = Game.get().bindingPressed("forward") || Game.get().bindingPressed("back")
                || Game.get().bindingPressed("left") || Game.get().bindingPressed("right");
        if (moving && !state.player.sprinting) {
            Game.get().setSprinting(true);
        }
    }
}
