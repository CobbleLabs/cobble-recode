package cobble.module.combat;

import cobble.boot.GameVersion;
import cobble.core.Maths;
import cobble.core.Rng;
import cobble.core.Time;
import cobble.core.Vec3;
import cobble.event.AttackEntityEvent;
import cobble.event.Subscribe;
import cobble.game.CombatTarget;
import cobble.game.EntityView;
import cobble.game.FrameState;
import cobble.game.Game;
import cobble.game.ItemKind;
import cobble.game.LocalPlayerView;
import cobble.game.Raycast;
import cobble.game.RotationEngine;
import cobble.game.RotationTarget;
import cobble.game.TargetFinder;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.Setting;
import cobble.module.Subcategory;
import java.util.List;

/**
 * Hits with a mace from the hotbar whatever is in your hand. The slot change
 * goes out in front of the attack and the server only swaps the attack damage
 * attribute when it next ticks the player, so the hit keeps the held weapon's
 * damage and adds the mace's smash and enchantments; the slot goes back before
 * the tick is over. That covers every attack, yours and other modules'.
 *
 * <p>A smash needs more than 1.5 blocks of fall and no elytra flight. It adds
 * 4 a block for the first 3 blocks, 2 a block up to 8 and 1 a block after,
 * and Density half a block's worth more a level; Breach takes 15% a level off
 * the share the target's armour stops. Auto weighs every mace against the fall
 * and the target's armour the way the server does.
 *
 * <p>Falling, it also aims at the target below and hits it the tick it comes
 * into reach. Against a raised shield, Stun slam hits with an axe first,
 * which takes the shield down, and slams with the mace the next tick.
 */
public final class AutoMace extends Module {
    private static final int AUTO = 0;
    private static final int MANUAL = 1;
    private static final int DENSITY = 0;
    private static final double SMASH_FALL = 1.5;
    private static final double DENSITY_PER_LEVEL = 0.5;
    private static final double BREACH_PER_LEVEL = 0.15;
    private static final double REACH = 3.0;
    /* How far inside the hitbox the aim point sits, so a tick of movement keeps the ray on it. */
    private static final float AIM_INSET = 0.1f;
    private static final int AIM_PRIORITY = 60;
    /* A player falling without input, as the game moves one each tick. */
    private static final double GRAVITY = 0.08;
    private static final double AIR_DRAG = 0.98;
    private static final double AIR_FRICTION = 0.91;
    private static final double STANDING_EYE = 1.62;
    private static final int FALL_TICKS = 60;
    private static final String ELYTRA = "elytra";

    private final Setting mobs = addBoolean("Mobs", "Aim at and hit mobs as well as players", false);
    private final Setting invisibles = addBoolean("Invisibles", "Aim at and hit invisible entities", false);
    private final Setting silentAim = addBoolean("Silent aim", "Aim the fall without moving the camera", true);
    private final Setting aimRange = addNumber("Aim range",
            "Farthest sideways a target below may be for a fall to aim at it", 5, 1, 12, 1);
    private final Setting aimSpeed = addNumber("Aim speed", "Degrees the aim turns each tick", 60, 5, 180, 0);
    private final Setting extraDelay = addNumber("Extra delay",
            "Ticks past a full attack cooldown before an automatic hit that will not smash; a smash never waits",
            0, -5, 10, 0);
    private final Setting unequipElytra = addBoolean("Unequip elytra",
            "Put on a hotbar chestplate while gliding once letting go would fall within reach of a target", false);
    private final Setting reequipElytra = addBoolean("Re-equip elytra",
            "Put the elytra back on when a smash sends you upward", true).visibleWhen(unequipElytra::bool);
    private final Setting smashOnly = addBoolean("Smash only", "Only swap to a mace when the hit will smash", true);
    private final Setting selection = addChoice("Mace selection",
            "Auto takes the mace that hits hardest for the fall and the target's armour; Manual looks for Mace type",
            List.of("Auto", "Manual"), AUTO);
    private final Setting maceType = addChoice("Mace type", "The enchantment Manual looks for",
            List.of("Density", "Breach"), DENSITY).visibleWhen(() -> selection.choice() == MANUAL);
    private final Setting stunSlam = addBoolean("Stun slam",
            "Against a raised shield, hit with an axe first and slam with the mace the next tick", false);
    private final Setting stunChance = addNumber("Stun slam chance", "Percent of shielded smashes that stun slam",
            100, 0, 100, 0).visibleWhen(stunSlam::bool);
    private final Setting limitToItems = addBoolean("Limit to items", "Only while holding a sword, an axe or a mace",
            false);

    private final Rng rng = new Rng();
    private FrameState state;
    /* Set while the second half of a stun slam goes out, which picks its own slot. */
    private boolean striking;
    private Object slamTarget;
    private int restoreSlot = -1;
    /*
     * The client's fall distance when the last smash left. The server lands the
     * attacker on a smash and the client never hears of it, so the fall the
     * server counts is what the client fell since then.
     */
    private double fallAtSmash;
    /* Where Unequip put the elytra, or -1. */
    private int elytraSlot = -1;
    private float lastCharge = 1.0f;
    private float chargePerTick;
    /* Ticks since the attack cooldown filled, negative for the ticks it still needs. */
    private int ticksPastFull;

    public AutoMace() {
        super("AutoMace", "Smashes with a mace from the hotbar", Category.COMBAT, Subcategory.ADVANCED);
    }

    @Override
    public boolean supported() {
        return GameVersion.current().atLeast(GameVersion.V1_21_11);
    }

    @Override
    public String suffix() {
        return selection.choice() == AUTO ? selection.choiceName() : maceType.choiceName();
    }

    @Override
    public void onDisable(Context context) {
        if (restoreSlot >= 0 && context.state.player.valid) {
            Game.get().setSelectedSlot(restoreSlot);
        }
        restoreSlot = -1;
        slamTarget = null;
        elytraSlot = -1;
    }

    @Subscribe(priority = Subscribe.Priority.LOW)
    public void onAttack(AttackEntityEvent event) {
        if (striking || event.cancelled() || state == null) {
            return;
        }
        Game game = Game.get();
        EntityView target = find(event.entity);
        if (target == null || !allowedInHand(game)) {
            return;
        }
        boolean smash = smashes();
        int mace = maceFor(game, target, smash);
        if (mace < 0 && !game.holdingItem(ItemKind.MACE)) {
            return;
        }
        int axe = game.findSlot(ItemKind.AXE, 0, 8);
        if (stunSlam.bool() && smash && target.blocking && axe >= 0 && rng.unit() * 100.0f < stunChance.number()) {
            swap(game, axe);
            slamTarget = target.handle;
            return;
        }
        if (mace >= 0) {
            swap(game, mace);
        }
        if (smash) {
            fallAtSmash = state.player.fallDistance;
        }
    }

    @Override
    public void onTick(Context context) {
        state = context.state;
        LocalPlayerView player = state.player;
        if (!player.valid) {
            return;
        }
        if (player.onGround) {
            fallAtSmash = 0.0;
        }
        trackCharge(player.attackCharge);
        Game game = Game.get();
        if (slamTarget != null) {
            slam(game);
            return;
        }
        if (state.screenOpen) {
            return;
        }
        elytra(game, player);
        fall(game, player);
    }

    /*
     * Back at the end of the tick the hit left in, which is before the server
     * ticks the player in all but a few: it then never sees the slot change at
     * all, so neither the attribute nor the held weapon's cooldown moves.
     */
    @Override
    public void onUpdate() {
        if (restoreSlot >= 0 && slamTarget == null) {
            Game.get().setSelectedSlot(restoreSlot);
            restoreSlot = -1;
        }
    }

    /* The axe took the shield down last tick; the mace follows on the same target. */
    private void slam(Game game) {
        EntityView target = find(slamTarget);
        slamTarget = null;
        int mace = target == null ? -1 : maceFor(game, target, smashes());
        if (mace < 0 || TargetFinder.hitboxDistance(state.player.eyePos, target.tickBoxMin(), target.tickBoxMax())
                > REACH) {
            return;
        }
        swap(game, mace);
        striking = true;
        game.attackEntity(target.handle);
        game.swingHand();
        striking = false;
        fallAtSmash = state.player.fallDistance;
    }

    /* Aims the fall at the target below and hits it the tick it comes into reach. */
    private void fall(Game game, LocalPlayerView player) {
        if (player.onGround || player.gliding || player.velocity.y() >= 0.0
                || game.findSlot(ItemKind.MACE, 0, 8) < 0 || !allowedInHand(game)) {
            return;
        }
        EntityView target = below(player);
        if (target == null) {
            return;
        }
        Vec3 eye = player.eyePos;
        RotationTarget aim = RotationEngine.aimAt(eye, TargetFinder.aimPoint(eye, target, AIM_INSET),
                RotationEngine.pendingYaw());
        aim.speed = aimSpeed.number();
        aim.smoothing = 0.0f;
        aim.silent = silentAim.bool();
        aim.priority = AIM_PRIORITY;
        RotationEngine.submit(aim);
        CombatTarget.publish(target.id, Time.nowSeconds());
        if (TargetFinder.hitboxDistance(eye, target.tickBoxMin(), target.tickBoxMax()) > REACH
                || !looksAt(eye, target) || !smashes() && ticksPastFull < extraDelay.intValue()) {
            return;
        }
        game.attackEntity(target.handle);
        game.swingHand();
    }

    /*
     * Wearing an armour piece from the hotbar swaps it with what is worn, so
     * Unequip leaves the elytra in the chestplate's slot and Re-equip finds it
     * there.
     */
    private void elytra(Game game, LocalPlayerView player) {
        if (elytraSlot >= 0 && !game.stack(elytraSlot).id().equals(ELYTRA)) {
            elytraSlot = -1;
        }
        if (unequipElytra.bool() && player.gliding
                && game.equipment(player.handle, Game.Slot.CHEST).id().equals(ELYTRA)) {
            int chestplate = chestplateSlot(game);
            if (chestplate >= 0 && fallReaches(player)) {
                wear(game, chestplate);
                elytraSlot = chestplate;
            }
        } else if (reequipElytra.bool() && elytraSlot >= 0 && fallAtSmash > 0.0 && !player.onGround
                && player.velocity.y() > 0.0) {
            wear(game, elytraSlot);
            elytraSlot = -1;
        }
    }

    private void wear(Game game, int slot) {
        swap(game, slot);
        game.queueClick(true);
    }

    private static int chestplateSlot(Game game) {
        for (int slot = 0; slot < 9; ++slot) {
            if (game.stack(slot).id().endsWith("_chestplate")) {
                return slot;
            }
        }
        return -1;
    }

    /** Whether letting go of the glide now falls within reach of a target. */
    private boolean fallReaches(LocalPlayerView player) {
        for (EntityView target : state.entities) {
            if (!targetable(target)) {
                continue;
            }
            Vec3 at = player.pos;
            Vec3 motion = player.velocity;
            for (int tick = 0; tick < FALL_TICKS && at.y() >= target.tickPos.y(); ++tick) {
                at = at.add(motion);
                motion = new Vec3(motion.x() * AIR_FRICTION, (motion.y() - GRAVITY) * AIR_DRAG,
                        motion.z() * AIR_FRICTION);
                if (TargetFinder.hitboxDistance(at.add(0.0, STANDING_EYE, 0.0), target.tickBoxMin(),
                        target.tickBoxMax()) <= REACH) {
                    return true;
                }
            }
        }
        return false;
    }

    private EntityView below(LocalPlayerView player) {
        EntityView best = null;
        double bestDistance = aimRange.number();
        for (EntityView target : state.entities) {
            double sideways = target.tickPos.sub(player.pos).horizontalLength();
            /* Their feet, not their head: a fast fall goes from out of reach to below their head in one tick. */
            if (targetable(target) && target.tickPos.y() < player.pos.y() && sideways <= bestDistance) {
                best = target;
                bestDistance = sideways;
            }
        }
        return best;
    }

    private boolean targetable(EntityView target) {
        return (target.isPlayer || mobs.bool()) && (!target.invisible || invisibles.bool());
    }

    /* The rotation this tick's movement packet carries, which the server judges the hit by. */
    private static boolean looksAt(Vec3 eye, EntityView target) {
        Vec3 look = Raycast.lookVector(RotationEngine.pendingYaw(), RotationEngine.pendingPitch());
        double distance = Raycast.rayBox(eye, look, target.tickBoxMin(), target.tickBoxMax(), REACH);
        return distance >= 0.0 && Raycast.raycastBlocks(eye, look, distance) == null;
    }

    private boolean smashes() {
        return !state.player.gliding && serverFall() > SMASH_FALL;
    }

    /** The fall the server counts. */
    private double serverFall() {
        return state.player.fallDistance - fallAtSmash;
    }

    private boolean allowedInHand(Game game) {
        return !limitToItems.bool() || game.holdingSword() || game.holdingItem(ItemKind.AXE)
                || game.holdingItem(ItemKind.MACE);
    }

    /** The hotbar mace a hit on {@code target} should go out with, or -1 to keep the hand as it is. */
    private int maceFor(Game game, EntityView target, boolean smash) {
        if (smashOnly.bool() && !smash) {
            return -1;
        }
        if (selection.choice() == MANUAL) {
            boolean density = maceType.choice() == DENSITY;
            /* Density does nothing without a fall. */
            if (density && !smash) {
                return -1;
            }
            int any = -1;
            for (int slot = 0; slot < 9; ++slot) {
                if (game.findSlot(ItemKind.MACE, slot, slot) == slot) {
                    if (game.enchantmentLevel(slot, density ? "density" : "breach") > 0) {
                        return slot;
                    }
                    any = any < 0 ? slot : any;
                }
            }
            return smash ? any : -1;
        }
        int best = -1;
        double bestDamage = damage(game, target, game.selectedSlot(), smash);
        for (int slot = 0; slot < 9; ++slot) {
            if (game.findSlot(ItemKind.MACE, slot, slot) == slot) {
                double damage = damage(game, target, slot, smash);
                if (damage > bestDamage) {
                    best = slot;
                    bestDamage = damage;
                }
            }
        }
        return best;
    }

    /** What the hit does through the target's armour with the stack in {@code slot} as the weapon. */
    private double damage(Game game, EntityView target, int slot, boolean smash) {
        int sharpness = game.enchantmentLevel(slot, "sharpness");
        double damage = state.player.attackDamage + (sharpness > 0 ? 0.5 * sharpness + 0.5 : 0.0);
        if (smash && game.findSlot(ItemKind.MACE, slot, slot) == slot) {
            double fall = serverFall();
            double perBlock = fall <= 3.0 ? 4.0 * fall : fall <= 8.0 ? 12.0 + 2.0 * (fall - 3.0) : 22.0 + fall - 8.0;
            damage += perBlock + DENSITY_PER_LEVEL * game.enchantmentLevel(slot, "density") * fall;
        }
        double armour = target.armor;
        double stopped = Maths.clamp(armour - damage / (2.0 + target.armorToughness / 4.0), armour * 0.2, 20.0) / 25.0;
        stopped = Maths.clamp(stopped - BREACH_PER_LEVEL * game.enchantmentLevel(slot, "breach"), 0.0, 1.0);
        return damage * (1.0 - stopped);
    }

    private void swap(Game game, int slot) {
        if (slot == game.selectedSlot()) {
            return;
        }
        if (restoreSlot < 0) {
            restoreSlot = game.selectedSlot();
        }
        game.setSelectedSlot(slot);
    }

    /* The cooldown fills at a steady rate, so its last step says how many ticks are left. */
    private void trackCharge(float charge) {
        if (charge < 1.0f) {
            if (charge > lastCharge) {
                chargePerTick = charge - lastCharge;
            }
            ticksPastFull = chargePerTick > 0.0f ? -(int) Math.ceil((1.0f - charge) / chargePerTick) : Integer.MIN_VALUE;
        } else {
            ticksPastFull = Math.max(ticksPastFull, -1) + 1;
        }
        lastCharge = charge;
    }

    private EntityView find(Object handle) {
        for (EntityView entity : state.entities) {
            if (entity.handle == handle) {
                return entity;
            }
        }
        return null;
    }
}
