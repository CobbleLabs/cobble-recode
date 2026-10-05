package cobble.module.combat;

import cobble.boot.GameVersion;
import cobble.core.Maths;
import cobble.core.Rng;
import cobble.core.Timer;
import cobble.core.Vec3;
import cobble.game.EntityView;
import cobble.game.FrameState;
import cobble.game.Game;
import cobble.game.Raycast;
import cobble.game.RotationEngine;
import cobble.game.RotationTarget;
import cobble.game.TargetFinder;
import cobble.hook.Input;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.Setting;
import cobble.module.Subcategory;
import java.util.List;

/**
 * A KillAura that aims. The rotation the server sees turns
 * to the nearest point of the target, the camera stays where it is, and a hit
 * goes out, on a clicker's rhythm, only once the rotation about to be sent
 * looks at the target with nothing in the way: a server rebuilding the hit
 * from the rotation finds the same one.
 */
public final class SilentAura extends Module {
    private static final int AIM_PRIORITY = 50;
    /* How far inside the hitbox the aim point sits, so a tick of movement keeps the ray on it. */
    private static final float AIM_INSET = 0.1f;
    /* Past 0.9 a hit keeps its sprint knockback and can crit; this is just past that. */
    private static final float FULL_CHARGE = 0.95f;

    private final Setting minAps = addNumber("Min APS", "Fewest attacks per second", 8, 1, 20, 0);
    private final Setting maxAps = addNumber("Max APS", "Most attacks per second", 12, 1, 20, 0);
    private final Setting range = addNumber("Range", "Hit targets this close", 3.0f, 2.0f, 6.0f, 1);
    private final Setting aimRange = addNumber("Aim range", "Start turning to targets this close", 4.5f, 2.0f, 8.0f, 1);
    private final Setting maxAngle = addNumber("Max angle", "Only targets within this many degrees of your aim",
            120, 1, 360, 0);
    private final Setting aimSpeed = addNumber("Aim speed", "Degrees the rotation turns each tick", 40, 5, 180, 0);
    private final Setting targetMode = addChoice("Target mode", "Which target goes first",
            List.of("Distance", "Health", "Angle"), 0);
    private final Setting mobs = addBoolean("Mobs", "Attack mobs as well as players", false);
    private final Setting invisibles = addBoolean("Invisibles", "Attack invisible entities", false);
    private final Setting requireMouseDown = addBoolean("Require mouse down", "Only while you hold left", false);
    private final Setting weaponOnly = addBoolean("Weapon only", "Only while holding a sword or an axe", false);
    private final Setting disableOnDeath = addBoolean("Disable on death", "Turn off when you die", false);
    private final Setting perfectSwing = addBoolean("Perfect swing", "Only hit once the attack cooldown is full", true)
            .visibleWhen(() -> GameVersion.current().combatUpdate());

    private final Rng rng = new Rng();
    private final Timer clickTimer = new Timer();
    private long clickDelay;

    public SilentAura() {
        super("SilentAura", "Aims at and hits nearby players without moving your view", Category.COMBAT,
                Subcategory.ADVANCED);
    }

    @Override
    public String suffix() {
        return targetMode.choiceName();
    }

    @Override
    public void onEnable(Context context) {
        rollDelay();
    }

    private void rollDelay() {
        clickTimer.reset();
        float low = Math.min(minAps.number(), maxAps.number());
        float high = Math.max(minAps.number(), maxAps.number());
        clickDelay = (long) (1000.0f / rng.range(low, high));
    }

    @Override
    public void onTick(Context context) {
        FrameState state = context.state;
        if (!state.inWorld || !state.player.valid || state.screenOpen) {
            return;
        }
        if (state.player.health <= 0.0f) {
            if (disableOnDeath.bool()) {
                setEnabled(false, context);
            }
            return;
        }
        Game game = Game.get();
        if (requireMouseDown.bool() && !Input.attackHeld() || weaponOnly.bool() && !game.holdingWeapon()) {
            return;
        }
        EntityView target = target(state);
        if (target == null) {
            return;
        }
        Vec3 eye = state.player.eyePos;
        RotationTarget aim = RotationEngine.aimAt(eye, TargetFinder.aimPoint(eye, target, AIM_INSET),
                RotationEngine.pendingYaw());
        aim.speed = aimSpeed.number();
        aim.silent = true;
        aim.priority = AIM_PRIORITY;
        RotationEngine.submit(aim);

        boolean charged = !perfectSwing.bool() || state.player.attackCharge >= FULL_CHARGE;
        if (!charged || !clickTimer.elapsed(clickDelay) || !looksAt(eye, target)) {
            return;
        }
        game.attackEntity(target.handle);
        game.swingHand();
        rollDelay();
    }

    private EntityView target(FrameState state) {
        TargetFinder.Filter filter = new TargetFinder.Filter();
        filter.range = aimRange.number();
        filter.mobs = mobs.bool();
        filter.invisibles = invisibles.bool();
        TargetFinder.Sort sort = switch (targetMode.choice()) {
            case 1 -> TargetFinder.Sort.HEALTH;
            case 2 -> TargetFinder.Sort.ANGLE;
            default -> TargetFinder.Sort.DISTANCE;
        };
        EntityView target = TargetFinder.selectTarget(state, filter, sort);
        if (target == null) {
            return null;
        }
        Vec3 eye = state.player.eyePos;
        float yaw = RotationEngine.aimAt(eye, target.tickPos, state.player.yaw).yaw;
        return Math.abs(Maths.angleDifference(state.player.yaw, yaw)) <= maxAngle.number() / 2.0f ? target : null;
    }

    /* The rotation this tick's movement packet carries, against the hitbox and the blocks before it. */
    private boolean looksAt(Vec3 eye, EntityView target) {
        Vec3 look = Raycast.lookVector(RotationEngine.pendingYaw(), RotationEngine.pendingPitch());
        double distance = Raycast.rayBox(eye, look, target.tickBoxMin(), target.tickBoxMax(), range.number());
        if (distance < 0.0) {
            return false;
        }
        Raycast.BlockHit wall = Raycast.raycastBlocks(eye, look, distance);
        return wall == null;
    }
}
