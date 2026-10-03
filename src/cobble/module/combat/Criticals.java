package cobble.module.combat;

import cobble.event.AttackEntityEvent;
import cobble.event.Subscribe;
import cobble.game.Game;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.Setting;
import cobble.module.Subcategory;
import java.util.List;
import java.util.SplittableRandom;
import net.minecraft.entity.player.EntityPlayer;

/**
 * Cobble's Criticals: a real hop on the hit, so the swings that follow land
 * while falling. Chance rolls on every hit; Hits fires on every Nth hit of an
 * unbroken combo.
 */
public final class Criticals extends Module {
    /* Well above a 1 CPS rhythm, so slow deliberate combos still build up. */
    private static final long COMBO_TIMEOUT_MS = 3000;

    private final Setting mode = addChoice("Mode", "Height of the hop", List.of("MiniJump", "Jump"), 0);
    private final Setting trigger = addChoice("Trigger", "What decides when a critical fires",
            List.of("Chance", "Hits"), 0);
    private final Setting chance = addNumber("Chance", "Chance per hit", 80, 0, 100, 0)
            .visibleWhen(() -> trigger.is("Chance"));
    private final Setting comboHits = addNumber("Combo Hits", "Fire on every Nth hit of a combo", 2, 1, 10, 0)
            .visibleWhen(() -> trigger.is("Hits"));
    private final Setting delay = addNumber("Delay", "Shortest time between criticals in milliseconds",
            100, 50, 500, 0);
    private final Setting lookingAtPlayer = addBoolean("Looking At Player", "Only on hits against players", true);
    private final Setting onlyWithSword = addBoolean("Only With Sword", "Only while holding a sword", false);

    private final SplittableRandom random = new SplittableRandom();
    private long lastCritical;
    private int hitCount;
    private long lastHit;

    public Criticals() {
        super("Criticals", "Hops on the hit so the next swings crit", Category.COMBAT, Subcategory.MEDIUM);
    }

    @Override
    public void onEnable(Context context) {
        lastCritical = 0;
        hitCount = 0;
        lastHit = 0;
    }

    /** FastStop leaves the momentum alone for this long after a hop. */
    public boolean recentlyCritical(long windowMs) {
        return enabled() && lastCritical != 0 && System.currentTimeMillis() - lastCritical < windowMs;
    }

    @Subscribe
    public void onAttack(AttackEntityEvent event) {
        if (event.entityPlayer != mc.thePlayer || mc.currentScreen != null) {
            return;
        }
        boolean againstPlayer = event.entity instanceof EntityPlayer;
        if (trigger.is("Hits")) {
            countComboHit(againstPlayer);
        } else if ((againstPlayer || !lookingAtPlayer.bool()) && swordAllowed() && ready()
                && random.nextInt(100) < chance.intValue()) {
            hop();
        }
    }

    /* A streak only means something if the hit landed on a player; anything
     * else, or too long a gap, starts it over. */
    private void countComboHit(boolean againstPlayer) {
        long now = System.currentTimeMillis();
        if (!againstPlayer) {
            hitCount = 0;
            return;
        }
        if (hitCount > 0 && now - lastHit > COMBO_TIMEOUT_MS) {
            hitCount = 0;
        }
        lastHit = now;
        hitCount = Math.min(hitCount + 1, comboHits.intValue());
        /* Not ready yet keeps the streak at the target, so the next hit fires
         * instead of losing the beat. */
        if (hitCount < comboHits.intValue() || !swordAllowed() || !ready()) {
            return;
        }
        hop();
        hitCount = 0;
    }

    private boolean swordAllowed() {
        return !onlyWithSword.bool() || Game.holdingSword();
    }

    private boolean ready() {
        return mc.thePlayer.onGround && !mc.thePlayer.isSneaking()
                && System.currentTimeMillis() - lastCritical >= delay.intValue();
    }

    private void hop() {
        mc.thePlayer.motionY = mode.is("Jump") ? 0.42 : 0.12;
        mc.thePlayer.onGround = false;
        lastCritical = System.currentTimeMillis();
    }
}
