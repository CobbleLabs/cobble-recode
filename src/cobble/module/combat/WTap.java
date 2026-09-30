package cobble.module.combat;

import cobble.core.Log;
import cobble.core.Rng;
import cobble.core.Time;
import cobble.game.FrameState;
import cobble.game.Game;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.ModuleManager;
import cobble.module.Setting;

/**
 * W-tap, from Argon's AutoWTap. Every hit TriggerBot lands releases W for a
 * couple hundred milliseconds, so sprint drops and rebuilds around the swing
 * the way a manual w-tap does. It presses the game's own key, so the sprint
 * packets that follow are the ones vanilla sends for the same input.
 */
public final class WTap extends Module {
    private final Setting delayMin = addNumber("Delay min", "Shortest W release in milliseconds", 230, 0, 1000, 0);
    private final Setting delayMax = addNumber("Delay max", "Longest W release in milliseconds", 270, 0, 1000, 0);
    private final Setting inAir = addBoolean("In air", "Also tap while airborne", false);

    private final Rng random = new Rng(0x7AC401);
    private int lastHits;
    private boolean tapping;
    private boolean logged;
    private long tapEnd;

    public WTap() {
        super("WTap", "W-taps every landed hit", Category.COMBAT);
    }

    // The binding is ours while tapping, so only the physical key says whether W is still held.
    private void endTap() {
        if (Game.bindingPhysicallyHeld("forward")) {
            Game.setBinding("forward", true);
        }
        tapping = false;
    }

    @Override
    public void onEnable(Context context) {
        tapping = false;
        logged = false;
        lastHits = 0;
    }

    @Override
    public void onDisable(Context context) {
        if (tapping) {
            endTap();
        }
        TriggerBot trigger = ModuleManager.get(TriggerBot.class);
        if (trigger != null) {
            lastHits = trigger.hits();
        }
    }

    @Override
    public void onTick(Context context) {
        FrameState state = context.state;
        TriggerBot trigger = ModuleManager.get(TriggerBot.class);
        if (!state.inWorld || !state.player.valid || state.screenOpen || trigger == null) {
            if (tapping) {
                endTap();
            }
            return;
        }

        boolean holdingW = Game.bindingPhysicallyHeld("forward");
        if (tapping) {
            boolean jumped = Game.bindingPressed("jump");
            if (!holdingW || jumped && !inAir.bool() || Time.nowMillis() >= tapEnd) {
                endTap();
            }
            lastHits = trigger.hits();
            return;
        }

        boolean struck = trigger.hits() != lastHits;
        lastHits = trigger.hits();
        if (!struck || !holdingW || !state.player.sprinting || !inAir.bool() && !state.player.onGround) {
            return;
        }
        float span = Math.max(delayMax.number() - delayMin.number(), 0.0f);
        tapEnd = Time.nowMillis() + (long) (delayMin.number() + random.unit() * span);
        Game.setBinding("forward", false);
        tapping = true;
        if (!logged) {
            logged = true;
            Log.debug("wtap", "first tap %dms after strike", tapEnd - Time.nowMillis());
        }
    }
}
