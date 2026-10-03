package cobble.module.combat;

import cobble.core.Rng;
import cobble.core.Time;
import cobble.event.AttackEntityEvent;
import cobble.event.Subscribe;
import cobble.game.FrameState;
import cobble.game.Game;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.Setting;
import cobble.module.Subcategory;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;

/**
 * W-tap: a hit on a player, from any source, releases W for a couple hundred
 * milliseconds, so sprint drops and rebuilds around the swing the way a manual
 * w-tap does. It presses the game's own key, so the sprint packets that follow
 * are the ones vanilla sends for the same input.
 */
public final class WTap extends Module {
    /* A target this far into its hurt cooldown takes no knockback from the hit, so a tap would be wasted. */
    private static final int VULNERABLE_AFTER = 14;

    private final Setting chance = addNumber("Chance", "Chance of w-tapping a hit", 90, 0, 100, 0);
    private final Setting releaseDelay = addNumber("Release delay", "Milliseconds between the hit and letting go of W",
            0, 0, 500, 0);
    private final Setting delayMin = addNumber("Delay min", "Shortest W release in milliseconds", 230, 0, 1000, 0);
    private final Setting delayMax = addNumber("Delay max", "Longest W release in milliseconds", 270, 0, 1000, 0);
    private final Setting selectHits = addBoolean("Select hits", "Only tap hits on a target that can take knockback",
            true);
    private final Setting inAir = addBoolean("In air", "Also tap while airborne", false);

    private final Rng random = new Rng(0x7AC401);
    private boolean pending;
    private boolean tapping;
    private long releaseAt;
    private long tapEnd;

    public WTap() {
        super("WTap", "W-taps every landed hit", Category.COMBAT, Subcategory.BASIC);
    }

    public boolean tapping() {
        return tapping;
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
        pending = false;
    }

    @Override
    public void onDisable(Context context) {
        if (tapping) {
            endTap();
        }
        pending = false;
    }

    /* Last, so an attack another module called off does not tap. */
    @Subscribe(priority = Subscribe.Priority.LOWEST)
    public void onAttack(AttackEntityEvent event) {
        EntityPlayerSP player = mc.thePlayer;
        if (event.cancelled() || pending || tapping || !(event.entity instanceof EntityPlayer target)
                || event.entityPlayer != player) {
            return;
        }
        if (selectHits.bool() && target.hurtResistantTime > VULNERABLE_AFTER) {
            return;
        }
        if (!Game.bindingPhysicallyHeld("forward") || !player.isSprinting() || !inAir.bool() && !player.onGround) {
            return;
        }
        if (random.unit() * 100.0 >= chance.number()) {
            return;
        }
        pending = true;
        releaseAt = Time.nowMillis() + releaseDelay.intValue();
    }

    @Override
    public void onTick(Context context) {
        FrameState state = context.state;
        if (!state.inWorld || !state.player.valid || state.screenOpen) {
            if (tapping) {
                endTap();
            }
            pending = false;
            return;
        }
        long now = Time.nowMillis();
        if (pending && now >= releaseAt) {
            pending = false;
            float span = Math.max(delayMax.number() - delayMin.number(), 0.0f);
            tapEnd = now + (long) (delayMin.number() + random.unit() * span);
            Game.setBinding("forward", false);
            tapping = true;
            return;
        }
        if (tapping) {
            boolean jumped = Game.bindingPressed("jump");
            if (!Game.bindingPhysicallyHeld("forward") || jumped && !inAir.bool() || now >= tapEnd) {
                endTap();
            }
        }
    }
}
