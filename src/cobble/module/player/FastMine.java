package cobble.module.player;

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
import java.util.Locale;

/**
 * The speed half calls the game's own damage-progress step extra times, since
 * each call is worth exactly one more tick of progress.
 */
public final class FastMine extends Module {
    private final Setting delay = addNumber("Break delay",
            "Milliseconds between finishing one block and starting the next; vanilla is 250", 250, 0, 250, 0);
    private final Setting speed = addNumber("Break speed", "Multiplies how fast a block breaks", 1, 1, 4, 2);
    private final Setting requireAttack = addBoolean("Require mouse down", "Only while the attack button is held",
            true);

    // Carries the fractional multiplier between ticks, so 1.5x is an extra call every other tick.
    private float accumulator;

    public FastMine() {
        super("FastMine", "Shortens the wait between blocks and speeds up the break", Category.PLAYER,
                Subcategory.ADVANCED);
        setIcon(Icon.ZAP);
    }

    @Override
    public String suffix() {
        return speed.number() > 1.0f ? String.format(Locale.ROOT, "%.2gx", speed.number()) : "";
    }

    @Override
    public void onTick(Context context) {
        FrameState state = context.state;
        if (!state.inWorld || !state.player.valid || state.screenOpen) {
            return;
        }
        if (requireAttack.bool() && !Input.attackHeld()) {
            return;
        }

        // Vanilla parks this at five the instant a block breaks; lowered, not written, or it never reaches zero.
        int ticks = (int) Math.floor(delay.number() / 50.0f);
        if (ticks < 5 && Game.blockBreakingCooldown() > ticks) {
            Game.setBlockBreakingCooldown(ticks);
        }

        float multiplier = speed.number();
        if (multiplier <= 1.0f || !Game.breakingBlock()) {
            return;
        }
        CrosshairTarget target = Game.crosshair();
        if (target.kind() != CrosshairTarget.Kind.BLOCK) {
            return;
        }
        float extra = multiplier - 1.0f;
        int calls = (int) extra;
        accumulator += extra - calls;
        if (accumulator >= 1.0f) {
            accumulator -= 1.0f;
            ++calls;
        }
        for (int index = 0; index < calls; ++index) {
            Game.attackBlock(target.x(), target.y(), target.z(), target.face(), true);
        }
    }
}
