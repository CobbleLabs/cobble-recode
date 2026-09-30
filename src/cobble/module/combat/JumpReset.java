package cobble.module.combat;

import cobble.core.Maths;
import cobble.core.Rng;
import cobble.game.CrosshairTarget;
import cobble.game.EntityView;
import cobble.game.FrameState;
import cobble.game.Game;
import cobble.game.RotationEngine;
import cobble.hook.Input;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.Setting;
import cobble.module.Subcategory;
import cobble.render.Icon;

/**
 * Raven-b's Jump Reset. Leaving the ground on the tick a hit lands trades the
 * ground friction that would eat the horizontal push for a full arc away from
 * whoever threw it. It presses the game's own jump key rather than calling
 * jump(), so the movement code, the sprint logic and the server all agree the
 * jump happened.
 */
public final class JumpReset extends Module {
    private static final float FOV_CONE = 165.0f;

    private final Setting chance = addNumber("Chance", "Percent of hits to reset on", 80, 0, 100, 0);
    private final Setting requireAttack = addBoolean("Require mouse down", "Only while attacking", false);
    private final Setting requireForward = addBoolean("Require moving forward", "Only while walking forward", true);
    private final Setting requireAim = addBoolean("Require aim", "Only while the crosshair is on a player", true);

    private final Rng random = new Rng(0x51ED2701);
    private int lastHurtTime;
    private double lastFallDistance;
    private boolean ignoreNext;
    private boolean holdingJump;
    // Releasing a jump key the player is still leaning on would be worse than never pressing it.
    private boolean jumpWasTheirs;

    public JumpReset() {
        super("JumpReset", "Jumps on the tick a hit lands so the knockback carries", Category.COMBAT,
                Subcategory.BASIC);
        setIcon(Icon.ACTIVITY);
    }

    @Override
    public String suffix() {
        int percent = (int) chance.number();
        return percent >= 100 ? "" : percent + "%";
    }

    @Override
    public void onEnable(Context context) {
        lastHurtTime = 0;
        lastFallDistance = 0.0;
        ignoreNext = false;
        holdingJump = false;
        jumpWasTheirs = false;
    }

    @Override
    public void onDisable(Context context) {
        if (holdingJump && !jumpWasTheirs) {
            Game.setBinding("jump", false);
        }
        holdingJump = false;
        jumpWasTheirs = false;
    }

    // Jump boost changes the arc the server expects; poison and wither keep ticking while airborne.
    private static boolean badEffect(FrameState state) {
        for (Game.EffectView effect : Game.statusEffects(state.player.handle)) {
            switch (effect.id()) {
                case "jump_boost", "poison", "wither" -> {
                    return true;
                }
                default -> {
                }
            }
        }
        return false;
    }

    private static boolean aimingAtPlayer(FrameState state) {
        CrosshairTarget target = Game.crosshair();
        if (target.kind() != CrosshairTarget.Kind.ENTITY) {
            return false;
        }
        for (EntityView entity : state.entities) {
            if (entity.id == target.entityId()) {
                return entity.isPlayer;
            }
        }
        return false;
    }

    // Knockback pushes away from the attacker, so the useful case is still broadly facing them.
    private static boolean pushedBackwards(FrameState state) {
        if (state.player.velocity.horizontalLength() < 0.02) {
            return true;
        }
        // Minecraft's yaw: -z is zero and it grows clockwise, which is atan2(-x, z).
        float motionYaw = (float) Math.toDegrees(Math.atan2(-state.player.velocity.x(), state.player.velocity.z()));
        return Math.abs(Maths.angleDifference(RotationEngine.sentYaw(), motionYaw)) <= FOV_CONE;
    }

    @Override
    public void onTick(Context context) {
        FrameState state = context.state;
        if (!state.inWorld || !state.player.valid || state.screenOpen) {
            onDisable(context);
            return;
        }

        // One tick of jump is the whole press; any longer commits the player to an arc.
        if (holdingJump) {
            holdingJump = false;
            if (!jumpWasTheirs) {
                Game.setBinding("jump", false);
            }
        }

        int hurtTime = state.player.hurtTime;
        // Landing a long fall raises hurtTime like a hit; jumping out of it is a second fall.
        if (state.player.onGround && lastFallDistance > 3.0) {
            ignoreNext = true;
        }

        if (hurtTime > lastHurtTime) {
            boolean attacking = !requireAttack.bool() || Input.attackHeld();
            boolean forward = !requireForward.bool() || Game.bindingPressed("forward");
            boolean aimed = !requireAim.bool() || aimingAtPlayer(state);
            boolean rolled = chance.number() >= 100.0f || random.range(0.0f, 100.0f) < chance.number();
            if (!ignoreNext && state.player.onGround && attacking && forward && aimed && rolled
                    && pushedBackwards(state) && !badEffect(state)) {
                jumpWasTheirs = Game.bindingPressed("jump");
                Game.setBinding("jump", true);
                holdingJump = true;
            }
            ignoreNext = false;
        }

        lastHurtTime = hurtTime;
        lastFallDistance = state.player.fallDistance;
    }
}
